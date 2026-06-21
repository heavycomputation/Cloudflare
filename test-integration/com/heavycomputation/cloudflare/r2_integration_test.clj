(ns com.heavycomputation.cloudflare.r2-integration-test
  "Live round-trip against a real R2 bucket. Exercises every public R2 function
  end to end — uploads (single + real ≥5 MiB multipart), downloads, metadata,
  listing/pagination, presigned GET/PUT (driven over plain HTTP, the way a
  browser would), the raw `request` escape hatch, and deletes.

  Run with real credentials:

    clojure -X:integration

  Needs CLOUDFLARE_ACCOUNT_ID / CLOUDFLARE_ACCESS_KEY_ID /
  CLOUDFLARE_SECRET_ACCESS_KEY in the environment. Everything is written under a
  unique, timestamped prefix and deleted again in a `finally`, so the test
  cleans up after itself even on failure."
  (:require [clojure.test :refer [deftest is testing]]
            [com.heavycomputation.cloudflare.r2 :as r2])
  (:import (java.io ByteArrayInputStream)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers)
           (java.util Arrays)))

(def config
  {:account-id        (System/getenv "CLOUDFLARE_ACCOUNT_ID")
   :access-key-id     (System/getenv "CLOUDFLARE_ACCESS_KEY_ID")
   :secret-access-key (System/getenv "CLOUDFLARE_SECRET_ACCESS_KEY")
   :bucket            "adexsi"})

(defn- configured? []
  (every? (comp seq config) [:account-id :access-key-id :secret-access-key]))

;; --- helpers ---------------------------------------------------------------

(defn- bytes= [^bytes a ^bytes b]
  (Arrays/equals a b))

(defn- random-bytes ^bytes [n]
  (let [b (byte-array n)]
    (dotimes [i n] (aset-byte b i (unchecked-byte (mod (* i 31) 256))))
    b))

(defn- http-get [url]
  (let [resp (.send (HttpClient/newHttpClient)
                    (-> (HttpRequest/newBuilder (URI/create url)) (.GET) (.build))
                    (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode resp) :body (.body resp)}))

(defn- http-put [url ^bytes body]
  (let [resp (.send (HttpClient/newHttpClient)
                    (-> (HttpRequest/newBuilder (URI/create url))
                        (.PUT (HttpRequest$BodyPublishers/ofByteArray body))
                        (.build))
                    (HttpResponse$BodyHandlers/ofByteArray))]
    {:status (.statusCode resp) :body (.body resp)}))

;; --- the test --------------------------------------------------------------

(deftest ^:integration live-r2-roundtrip
  (if-not (configured?)
    (println (str "\n[skipped] live R2 test — set CLOUDFLARE_ACCOUNT_ID, "
                  "CLOUDFLARE_ACCESS_KEY_ID and CLOUDFLARE_SECRET_ACCESS_KEY\n"))
    (let [bucket  (r2/bucket config)
          prefix  (str "hc-cloudflare-smoke/" (System/currentTimeMillis) "/")
          k       (fn [s] (str prefix s))
          created (atom [])
          track   (fn [key] (swap! created conj key) key)]
      (println "\nRunning live R2 round-trip under prefix" prefix)
      (try
        ;; -- put: byte[] (single PUT) ------------------------------------
        (testing "put byte[] with metadata, then read it back"
          (let [key  (track (k "hello.txt"))
                body (.getBytes "hello, r2!")
                res  (r2/put bucket key body
                             {:metadata      {:owner "heavy" :run "smoke"}
                              :cache-control "public, max-age=42"})]
            (is (= key (:key res)))
            (is (= "adexsi" (:bucket res)))
            (is (= 10 (:bytes res)))
            (is (string? (:etag res)))
            (is (true? (r2/exists? bucket key)))
            (is (bytes= body (r2/get-bytes bucket key)))
            (let [m (r2/head bucket key)]
              (is (= "text/plain" (:content-type m)))
              (is (= 10 (:bytes m)))
              (is (= "public, max-age=42" (:cache-control m)))
              (is (= {"owner" "heavy" "run" "smoke"} (:metadata m)))
              (is (some? (:last-modified m))))
            (testing "get returns bytes + metadata together"
              (let [o (r2/get bucket key)]
                (is (bytes= body (:body o)))
                (is (= "text/plain" (:content-type o)))))
            (testing "get-stream streams the same bytes"
              (with-open [in (:body (r2/get-stream bucket key))]
                (is (bytes= body (.readAllBytes in)))))))

        ;; -- put: File ---------------------------------------------------
        (testing "put a File (content-type inferred from key extension)"
          (let [tmp (java.io.File/createTempFile "r2-smoke" ".json")
                key (track (k "data.json"))]
            (spit tmp "{\"ok\":true}")
            (try
              (r2/put bucket key tmp)
              (is (= "application/json" (:content-type (r2/head bucket key))))
              (is (= "{\"ok\":true}" (String. (r2/get-bytes bucket key) "UTF-8")))
              (finally (.delete tmp)))))

        ;; -- put: InputStream (unknown length, small -> single PUT) ------
        (testing "put an unknown-length InputStream"
          (let [key (track (k "stream.bin"))
                body (.getBytes "from a stream")]
            (r2/put bucket key (ByteArrayInputStream. body))
            (is (bytes= body (r2/get-bytes bucket key)))))

        ;; -- put: real multipart (>5 MiB, parts >=5 MiB) -----------------
        (testing "put a large object via real multipart upload"
          (let [mib  (* 1024 1024)
                body (random-bytes (* 12 mib))
                key  (track (k "big.bin"))
                res  (r2/put bucket key body
                             {:multipart-threshold (* 5 mib)
                              :part-size           (* 5 mib)
                              :concurrency         3})]
            (is (= (* 12 mib) (:bytes res)))
            (is (re-find #"-\d+\"?$" (:etag res))
                "multipart ETag carries a -<partcount> suffix")
            (is (= (* 12 mib) (:bytes (r2/head bucket key))))
            (is (bytes= body (r2/get-bytes bucket key)))))

        ;; -- missing keys ------------------------------------------------
        (testing "absent keys read as nil / false"
          (let [missing (k "does-not-exist")]
            (is (nil? (r2/get-bytes bucket missing)))
            (is (nil? (r2/get-stream bucket missing)))
            (is (nil? (r2/head bucket missing)))
            (is (false? (r2/exists? bucket missing)))))

        ;; -- listing + pagination ----------------------------------------
        (testing "list and list-seq enumerate the prefix, paging transparently"
          (let [page (r2/list bucket {:prefix prefix})
                keys (set (map :key (:objects page)))]
            (is (contains? keys (k "hello.txt")))
            (is (every? (fn [{:keys [size etag last-modified]}]
                          (and size etag last-modified))
                        (:objects page))))
          (testing "delimiter rolls keys up into common prefixes"
            ;; List the parent with "/" as delimiter: our timestamped prefix
            ;; should appear as a single rolled-up common prefix.
            (let [page (r2/list bucket {:prefix "hc-cloudflare-smoke/" :delimiter "/"})]
              (is (contains? (set (:common-prefixes page)) prefix))))
          (testing "list-seq follows continuation tokens (max-keys forces paging)"
            (let [all (r2/list-seq bucket {:prefix prefix :max-keys 2})]
              (is (>= (count all) 4))
              (is (apply distinct? (map :key all))))))

        ;; -- presigned GET (download via plain HTTP) ---------------------
        (testing "presigned GET URL downloads without credentials"
          (let [key  (k "hello.txt")
                url  (r2/presigned-url bucket key {:expires 300})
                resp (http-get url)]
            (is (= 200 (:status resp)))
            (is (= "hello, r2!" (String. ^bytes (:body resp) "UTF-8")))))

        ;; -- presigned PUT (upload via plain HTTP) -----------------------
        (testing "presigned PUT URL accepts a direct upload"
          (let [key  (track (k "presigned-upload.txt"))
                url  (r2/presigned-url bucket key {:method :put :expires 300})
                body (.getBytes "uploaded via presigned PUT")
                resp (http-put url body)]
            (is (#{200 204} (:status resp)) (str "PUT status " (:status resp)))
            (is (bytes= body (r2/get-bytes bucket key)))))

        ;; -- raw request escape hatch ------------------------------------
        (testing "request reaches the raw S3 surface (range GET)"
          (let [resp (r2/request bucket {:method "GET" :key (k "hello.txt")
                                         :headers {"Range" "bytes=0-4"}})]
            (is (= 206 (:status resp)))
            (is (= "hello" (String. ^bytes (:body resp) "UTF-8")))))

        ;; -- delete ------------------------------------------------------
        (testing "delete removes a single object"
          (let [key (k "stream.bin")]
            (is (true? (r2/delete bucket key)))
            (is (false? (r2/exists? bucket key)))
            (testing "delete is idempotent"
              (is (true? (r2/delete bucket key))))))

        ;; -- delete-many -------------------------------------------------
        (testing "delete-many clears the rest of the prefix"
          (let [remaining (map :key (r2/list-seq bucket {:prefix prefix}))
                res       (r2/delete-many bucket remaining)]
            (is (empty? (:errors res)))
            (is (= (set remaining) (set (:deleted res))))
            (is (empty? (r2/list-seq bucket {:prefix prefix})))))

        (println "Live R2 round-trip complete.")
        (finally
          ;; Belt and suspenders: remove anything still around.
          (let [leftover (map :key (r2/list-seq bucket {:prefix prefix}))]
            (when (seq leftover)
              (println "Cleaning up" (count leftover) "leftover object(s)")
              (r2/delete-many bucket leftover))))))))
