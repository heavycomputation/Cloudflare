(ns com.heavycomputation.cloudflare.images-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.heavycomputation.cloudflare.images :as images]
            [com.heavycomputation.cloudflare.internal.api :as api]
            [com.heavycomputation.cloudflare.internal.json :as json])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

;; ---------------------------------------------------------------------------
;; Stub the api seam. The images layer calls api/send-request (JSON), and
;; api/send-bytes / api/send-streaming for blob downloads. Requests carry their
;; high-level :multipart/:json bodies unmodified (prepare only runs in the real
;; transport), so tests can inspect them directly.
;; ---------------------------------------------------------------------------

(defn- ok
  "A success envelope wrapping `result`, as the transport would return it
  (body already a JSON string)."
  [result]
  {:status 200 :headers {}
   :body   (json/write {"success" true "errors" [] "messages" [] "result" result})})

(defn- err [status code message]
  {:status status :headers {}
   :body   (json/write {"success" false
                        "errors"  [{"code" code "message" message}]})})

(def ^:private image-result
  {"id"                "cats/fluffy"
   "filename"          "cat.jpg"
   "uploaded"          "2024-01-02T03:04:05.000Z"
   "requireSignedURLs" false
   "meta"              {"owner" "u42"}
   "variants"          ["https://imagedelivery.net/hash/cats/fluffy/public"]})

(defn- req->op [{:keys [method path]}]
  (let [ends? #(str/ends-with? path %)]
    (cond
      (and (= method "POST") (ends? "/v2/direct_upload")) :direct-upload
      (= method "POST")                                   :upload
      (and (= method "GET") (ends? "/v2"))                :list
      (and (= method "GET") (ends? "/v1/stats"))          :stats
      (and (= method "GET") (ends? "/v1/keys"))           :keys
      (and (= method "GET") (ends? "/blob"))              :blob
      (= method "GET")                                    :get
      (= method "PATCH")                                  :update
      (= method "DELETE")                                 :delete
      :else                                               :unknown)))

(defn- default-response [req]
  (case (req->op req)
    :upload        (ok image-result)
    :direct-upload (ok {"id" "new-id" "uploadURL" "https://upload.imagedelivery.net/one-time"})
    :get           (ok image-result)
    :update        (ok (assoc image-result "requireSignedURLs" true))
    :list          (ok {"images" [image-result] "continuation_token" nil})
    :stats         (ok {"count" {"current" 12 "allowed" 100000}})
    :keys          (ok {"keys" [{"name" "default" "value" "supersecret"}]})
    :delete        {:status 200 :headers {} :body (json/write {"success" true "result" nil})}
    {:status 200 :headers {} :body (json/write {"success" true "result" nil})}))

(defn- recording-client
  ([] (recording-client (constantly nil)))
  ([responder]
   (let [calls (atom [])]
     [calls (fn [_client req]
              (swap! calls conj req)
              (or (responder req) (default-response req)))])))

(defn- ops [calls] (mapv req->op @calls))

(def ^:private handle
  {:api ::stub :account-id "acct" :account-hash "hash" :signing-key "secretkey"})

(defn- part [req name]
  (some #(when (= name (:name %)) %) (:multipart req)))

;; ---------------------------------------------------------------------------

(deftest content-type-inference
  (is (= "image/png"  (images/content-type-for "logo.png")))
  (is (= "image/jpeg" (images/content-type-for "photo.JPG")))
  (is (= "image/webp" (images/content-type-for "a/b/pic.webp")))
  (is (= "application/octet-stream" (images/content-type-for "noext")))
  (is (= "application/octet-stream" (images/content-type-for "weird.xyz"))))

(deftest client-validates-required-config
  (is (thrown? clojure.lang.ExceptionInfo (images/client {:api-token "t"})))
  (is (thrown? clojure.lang.ExceptionInfo (images/client {:account-id "a"})))
  (testing "a full config builds a handle carrying hash + signing key"
    (let [c (images/client {:account-id "a" :api-token "t"
                            :account-hash "h" :signing-key "k"})]
      (is (= "a" (:account-id c)))
      (is (= "h" (:account-hash c)))
      (is (= "k" (:signing-key c))))))

;; --- uploads ---------------------------------------------------------------

(deftest upload-sends-multipart-and-normalizes-result
  (let [[calls stub] (recording-client)]
    (with-redefs [api/send-request stub]
      (let [res (images/upload handle (.getBytes "PNGBYTES")
                               {:id "cats/fluffy" :metadata {:owner "u42"}
                                :require-signed-urls true :filename "cat.jpg"})]
        (is (= [:upload] (ops calls)))
        (is (= {:id "cats/fluffy" :filename "cat.jpg"
                :uploaded (java.time.Instant/parse "2024-01-02T03:04:05.000Z")
                :require-signed-urls false :meta {"owner" "u42"}
                :variants ["https://imagedelivery.net/hash/cats/fluffy/public"]}
               res))
        (let [req (first @calls)]
          (is (= "POST" (:method req)))
          (is (= "/accounts/acct/images/v1" (:path req)))
          (testing "file part carries the bytes, filename, and inferred content-type"
            (let [f (part req "file")]
              (is (= "cat.jpg" (:filename f)))
              (is (= "image/jpeg" (:content-type f)))
              (is (bytes? (:content f)))))
          (testing "metadata is JSON-encoded and flags are string-valued"
            (is (= "{\"owner\":\"u42\"}" (:content (part req "metadata"))))
            (is (= "true" (:content (part req "requireSignedURLs"))))
            (is (= "cats/fluffy" (:content (part req "id"))))))))))

(deftest upload-rejects-a-url-string
  (testing "a String body is a mistake — steer to upload-url"
    (is (thrown? clojure.lang.ExceptionInfo
                 (images/upload handle "https://example.com/x.png")))))

(deftest upload-url-ingests-a-remote-url
  (let [[calls stub] (recording-client)]
    (with-redefs [api/send-request stub]
      (let [res (images/upload-url handle "https://example.com/cat.png"
                                   {:metadata {:src "web"}})]
        (is (= [:upload] (ops calls)))
        (is (= "cats/fluffy" (:id res)))
        (let [req (first @calls)]
          (is (= "https://example.com/cat.png" (:content (part req "url"))))
          (is (nil? (part req "file")))
          (is (= "{\"src\":\"web\"}" (:content (part req "metadata")))))))))

(deftest direct-upload-url-returns-id-and-url
  (let [[calls stub] (recording-client)]
    (with-redefs [api/send-request stub]
      (let [res (images/direct-upload-url handle {:require-signed-urls true
                                                  :expiry-seconds 600})]
        (is (= [:direct-upload] (ops calls)))
        (is (= {:id "new-id" :upload-url "https://upload.imagedelivery.net/one-time"} res))
        (let [req (first @calls)]
          (is (str/ends-with? (:path req) "/v2/direct_upload"))
          (is (= "true" (:content (part req "requireSignedURLs"))))
          (testing "expiry is an absolute RFC-3339 timestamp"
            (is (re-find #"^\d{4}-\d\d-\d\dT" (:content (part req "expiry"))))))))))

;; --- reads / management ----------------------------------------------------

(deftest get-returns-details-or-nil
  (let [[calls stub] (recording-client)]
    (with-redefs [api/send-request stub]
      (is (= "cats/fluffy" (:id (images/get handle "cats/fluffy"))))
      (is (= :get (req->op (first @calls))))
      (is (= "/accounts/acct/images/v1/cats/fluffy" (:path (first @calls))))))
  (testing "404 yields nil (and exists? is false)"
    (let [[_ stub] (recording-client (fn [_] {:status 404 :headers {}
                                              :body (json/write {"success" false "errors" []})}))]
      (with-redefs [api/send-request stub]
        (is (nil? (images/get handle "missing")))
        (is (false? (images/exists? handle "missing")))))))

(deftest list-parses-page-and-list-seq-follows-cursor
  (testing "single page"
    (let [[calls stub] (recording-client)]
      (with-redefs [api/send-request stub]
        (let [page (images/list handle {:per-page 50 :sort-order :desc})]
          (is (= ["cats/fluffy"] (map :id (:images page))))
          (is (nil? (:continuation-token page)))
          (is (= {"per_page" 50 "sort_order" "desc"} (:query (first @calls))))))))
  (testing "list-seq follows continuation_token until blank"
    (let [calls (atom [])
          responder (fn [req]
                      (swap! calls conj req)
                      (let [first? (nil? (get-in req [:query "continuation_token"]))]
                        (ok {"images" [(assoc image-result "id" (if first? "a" "b"))]
                             "continuation_token" (when first? "TOK2")})))]
      (with-redefs [api/send-request (fn [_ req] (responder req))]
        (is (= ["a" "b"] (map :id (images/list-seq handle {:per-page 1}))))
        (is (= 2 (count @calls)))
        (is (= "TOK2" (get-in (second @calls) [:query "continuation_token"])))
        (is (= 1 (get-in (second @calls) [:query "per_page"])))))))

(deftest update-sends-patch-with-json-body
  (let [[calls stub] (recording-client)]
    (with-redefs [api/send-request stub]
      (let [res (images/update handle "cats/fluffy"
                               {:require-signed-urls true :metadata {:owner "new"}})]
        (is (true? (:require-signed-urls res)))
        (let [req (first @calls)]
          (is (= "PATCH" (:method req)))
          (is (= "/accounts/acct/images/v1/cats/fluffy" (:path req)))
          (is (= {"requireSignedURLs" true "metadata" {:owner "new"}} (:json req))))))))

(deftest delete-is-idempotent-but-surfaces-real-errors
  (testing "normal delete returns true"
    (let [[calls stub] (recording-client)]
      (with-redefs [api/send-request stub]
        (is (true? (images/delete handle "cats/fluffy")))
        (is (= :delete (req->op (first @calls)))))))
  (testing "404 (already gone) is success"
    (let [[_ stub] (recording-client (fn [_] {:status 404 :headers {}
                                             :body (json/write {"success" false "errors" []})}))]
      (with-redefs [api/send-request stub]
        (is (true? (images/delete handle "gone"))))))
  (testing "a real error status throws"
    (let [[_ stub] (recording-client (fn [_] (err 403 "1000" "Auth error")))]
      (with-redefs [api/send-request stub]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo (images/delete handle "x")))]
          (is (= 403 (:status (ex-data ex)))))))))

(deftest get-bytes-and-get-stream-read-the-blob
  (testing "bytes"
    (let [calls (atom [])]
      (with-redefs [api/send-bytes (fn [_ req]
                                     (swap! calls conj req)
                                     {:status 200 :headers {} :body (.getBytes "IMG")})]
        (is (= "IMG" (String. ^bytes (images/get-bytes handle "cats/fluffy")
                              StandardCharsets/UTF_8)))
        (is (str/ends-with? (:path (first @calls)) "/v1/cats/fluffy/blob"))))
    (testing "404 -> nil"
      (with-redefs [api/send-bytes (fn [_ _] {:status 404 :headers {}
                                              :body (.getBytes "{}")})]
        (is (nil? (images/get-bytes handle "missing"))))))
  (testing "stream"
    (with-redefs [api/send-streaming (fn [_ _] {:status 200 :headers {}
                                                :body (ByteArrayInputStream. (.getBytes "IMG"))})]
      (is (= "IMG" (slurp (images/get-stream handle "cats/fluffy")))))))

(deftest stats-and-signing-keys
  (let [[_ stub] (recording-client)]
    (with-redefs [api/send-request stub]
      (is (= {:current 12 :allowed 100000} (images/stats handle)))
      (is (= [{:name "default" :value "supersecret"}] (images/signing-keys handle))))))

(deftest error-envelope-becomes-ex-info
  (let [[_ stub] (recording-client (fn [_] (err 400 "5400" "bad request")))]
    (with-redefs [api/send-request stub]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo (images/upload handle (.getBytes "x"))))
            data (ex-data ex)]
        (is (= 400 (:status data)))
        (is (= "5400" (:code data)))
        (is (= "bad request" (:message data)))))))

(deftest transient-failure-is-retried
  (let [attempts (atom 0)
        responder (fn [_] (when (= 1 (swap! attempts inc)) (err 503 "0" "try again")))
        [_ stub] (recording-client responder)]
    (with-redefs [api/send-request stub]
      (is (= "cats/fluffy" (:id (images/upload handle (.getBytes "x")))))
      (is (= 2 @attempts)))))

(deftest request-escape-hatch-prefixes-images-root
  (let [[calls stub] (recording-client)]
    (with-redefs [api/send-request stub]
      (images/request handle {:method "GET" :path "/v1/keys"})
      (is (= "/accounts/acct/images/v1/keys" (:path (first @calls)))))))

;; --- delivery urls (pure) --------------------------------------------------

(deftest delivery-url-named-and-flexible-variants
  (is (= "https://imagedelivery.net/hash/cats/fluffy/public"
         (images/delivery-url handle "cats/fluffy" "public")))
  (is (= "https://imagedelivery.net/hash/cats/fluffy/thumbnail"
         (images/delivery-url handle "cats/fluffy" :thumbnail)))
  (testing "a transform map renders sorted key=value pairs (flexible variant)"
    (is (= "https://imagedelivery.net/hash/id/fit=cover,h=300,w=400"
           (images/delivery-url handle "id" {:w 400 :h 300 :fit "cover"}))))
  (testing "missing account hash is an error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (images/delivery-url (dissoc handle :account-hash) "id" "public")))))

(defn- hmac-hex [key ^String msg]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String key StandardCharsets/UTF_8) "HmacSHA256"))
    (->> (.doFinal mac (.getBytes msg StandardCharsets/UTF_8))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(deftest delivery-url-signed
  (let [url (images/delivery-url handle "cats/fluffy" "public" {:signed true :expires 600})]
    (is (str/starts-with? url "https://imagedelivery.net/hash/cats/fluffy/public?exp="))
    (let [[_ exp sig] (re-matches #".*\?exp=(\d+)&sig=([0-9a-f]{64})$" url)]
      (is (some? exp))
      (testing "signature is HMAC-SHA256 of the path+exp with the signing key"
        (is (= (hmac-hex "secretkey" (str "/hash/cats/fluffy/public?exp=" exp))
               sig))))
    (testing "an explicit :signing-key overrides the client's"
      (let [url2 (images/delivery-url handle "id" "public"
                                      {:signed true :expires 600 :signing-key "otherkey"})
            [_ exp sig] (re-matches #".*\?exp=(\d+)&sig=([0-9a-f]{64})$" url2)]
        (is (= (hmac-hex "otherkey" (str "/hash/id/public?exp=" exp)) sig)))))
  (testing "signing without a key is an error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (images/delivery-url (dissoc handle :signing-key) "id" "public"
                                      {:signed true})))))
