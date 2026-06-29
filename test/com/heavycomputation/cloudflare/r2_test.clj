(ns com.heavycomputation.cloudflare.r2-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.heavycomputation.cloudflare.internal.s3 :as s3]
            [com.heavycomputation.cloudflare.r2 :as r2])
  (:import (java.io ByteArrayInputStream)
           (java.nio.charset StandardCharsets)))

;; ---------------------------------------------------------------------------
;; Stub the HTTP seam (s3/send-request). The stub records each request and
;; replies with the shape R2 expects, so the put strategy logic is exercised
;; without signing or touching the network.
;; ---------------------------------------------------------------------------

(defn- xml-body ^bytes [s] (.getBytes ^String s StandardCharsets/UTF_8))

(defn- req->op
  "Classify a recorded request the way the real S3 API would name the op."
  [{:keys [method query]}]
  (cond
    (and (= method "POST")   (contains? query "uploads"))    :CreateMultipartUpload
    (and (= method "POST")   (contains? query "uploadId"))   :CompleteMultipartUpload
    (and (= method "POST")   (contains? query "delete"))     :DeleteObjects
    (and (= method "PUT")    (contains? query "partNumber")) :UploadPart
    (= method "PUT")                                         :PutObject
    (and (= method "DELETE") (contains? query "uploadId"))   :AbortMultipartUpload
    (= method "DELETE")                                      :DeleteObject
    (and (= method "GET")    (contains? query "list-type"))  :ListObjectsV2
    (= method "HEAD")                                        :HeadObject
    (= method "GET")                                         :GetObject
    :else                                                    :unknown))

(def ^:private object-headers
  {"content-type"     ["text/plain"]
   "content-length"   ["5"]
   "etag"             ["\"obj-etag\""]
   "last-modified"    ["Tue, 15 Nov 1994 08:12:31 GMT"]
   "cache-control"    ["public, max-age=60"]
   "x-amz-meta-owner" ["heavy"]})

(defn- default-response [req]
  (case (req->op req)
    :PutObject   {:status 200 :headers {"etag" ["\"single-etag\""]} :body (byte-array 0)}
    :UploadPart  {:status 200
                  :headers {"etag" [(str "\"part-" (get-in req [:query "partNumber"]) "\"")]}
                  :body (byte-array 0)}
    :CreateMultipartUpload
    {:status 200 :headers {}
     :body (xml-body "<InitiateMultipartUploadResult><UploadId>upload-1</UploadId></InitiateMultipartUploadResult>")}
    :CompleteMultipartUpload
    {:status 200 :headers {}
     :body (xml-body "<CompleteMultipartUploadResult><ETag>\"multipart-etag\"</ETag></CompleteMultipartUploadResult>")}
    :AbortMultipartUpload {:status 204 :headers {} :body (byte-array 0)}
    :HeadObject   {:status 200 :headers object-headers :body (byte-array 0)}
    :GetObject    {:status 200 :headers object-headers :body (.getBytes "hello")}
    :DeleteObject {:status 204 :headers {} :body (byte-array 0)}
    :DeleteObjects
    {:status 200 :headers {}
     :body (xml-body (str "<DeleteResult>"
                          "<Deleted><Key>a.txt</Key></Deleted>"
                          "<Error><Key>b.txt</Key><Code>AccessDenied</Code><Message>no</Message></Error>"
                          "</DeleteResult>"))}
    :ListObjectsV2
    {:status 200 :headers {}
     :body (xml-body (str "<ListBucketResult>"
                          "<Contents><Key>a.txt</Key><Size>10</Size><ETag>\"e1\"</ETag>"
                          "<LastModified>2009-10-12T17:50:30.000Z</LastModified></Contents>"
                          "<Contents><Key>b.txt</Key><Size>20</Size><ETag>\"e2\"</ETag>"
                          "<LastModified>2009-10-12T17:50:31.000Z</LastModified></Contents>"
                          "<CommonPrefixes><Prefix>sub/</Prefix></CommonPrefixes>"
                          "<IsTruncated>false</IsTruncated>"
                          "</ListBucketResult>"))}
    {:status 200 :headers {} :body (byte-array 0)}))

(defn- recording-client
  "Returns [calls-atom stub-fn]. `responder` may override the canned reply for
  specific ops; it receives the request and returns a response map or nil to
  fall through to the default."
  ([] (recording-client (constantly nil)))
  ([responder]
   (let [calls (atom [])]
     [calls (fn [_client req]
              (swap! calls conj req)
              (or (responder req) (default-response req)))])))

(defn- ops [calls] (mapv req->op @calls))

(def ^:private handle {:client ::stub :bucket "uploads"})

;; ---------------------------------------------------------------------------

(deftest content-type-inference
  (testing "known extensions"
    (is (= "application/zip"  (r2/content-type-for "archives/backup.zip")))
    (is (= "application/json" (r2/content-type-for "data.JSON")))
    (is (= "image/png"        (r2/content-type-for "logo.png")))
    (is (= "text/html"        (r2/content-type-for "a/b/index.html"))))
  (testing "unknown / missing extensions fall back to octet-stream"
    (is (= "application/octet-stream" (r2/content-type-for "no-extension")))
    (is (= "application/octet-stream" (r2/content-type-for "weird.xyz")))
    (is (= "application/octet-stream" (r2/content-type-for "trailing.")))))

(deftest small-object-uses-single-put
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (let [res (r2/put handle "small.txt" (.getBytes "hello world")
                        {:multipart-threshold 1024})]
        (is (= [:PutObject] (ops calls)))
        (is (= {:key "small.txt" :bucket "uploads"
                :etag "\"single-etag\"" :bytes 11}
               res))
        (let [req (first @calls)]
          (is (= "PUT" (:method req)))
          (is (= "/uploads/small.txt" (:path req)))
          (is (= "text/plain" (get-in req [:headers "Content-Type"]))))))))

(deftest large-object-uses-multipart
  (let [[calls stub] (recording-client)
        body (byte-array 25)]                  ; 25 bytes, 10-byte parts -> 3 parts
    (with-redefs [s3/send-request stub]
      (let [res (r2/put handle "big.bin" body
                        {:multipart-threshold 10 :part-size 10 :concurrency 2})]
        (is (= [:CreateMultipartUpload :UploadPart :UploadPart :UploadPart
                :CompleteMultipartUpload]
               (ops calls)))
        (is (= "\"multipart-etag\"" (:etag res)))
        (is (= 25 (:bytes res)))
        (testing "the completion body lists parts sorted with their etags"
          (let [complete (->> @calls (filter #(= :CompleteMultipartUpload (req->op %))) first)
                xml      (String. ^bytes (:body complete) StandardCharsets/UTF_8)]
            (is (= (str "<CompleteMultipartUpload>"
                        "<Part><PartNumber>1</PartNumber><ETag>\"part-1\"</ETag></Part>"
                        "<Part><PartNumber>2</PartNumber><ETag>\"part-2\"</ETag></Part>"
                        "<Part><PartNumber>3</PartNumber><ETag>\"part-3\"</ETag></Part>"
                        "</CompleteMultipartUpload>")
                   xml))))))))

(deftest threshold-boundary
  (testing "just below the threshold is single, at the threshold is multipart"
    (doseq [[size expected] [[9 :PutObject] [10 :CreateMultipartUpload]]]
      (let [[calls stub] (recording-client)]
        (with-redefs [s3/send-request stub]
          (r2/put handle "f.bin" (byte-array size)
                  {:multipart-threshold 10 :part-size 10})
          (is (= expected (first (ops calls))) (str size " bytes")))))))

(deftest unknown-length-stream-small-falls-back-to-single
  (testing "a stream that fits in one part goes up as a plain PUT"
    (let [[calls stub] (recording-client)
          stream (ByteArrayInputStream. (.getBytes "tiny"))]
      (with-redefs [s3/send-request stub]
        (let [res (r2/put handle "s.txt" stream {:part-size 1024})]
          (is (= [:PutObject] (ops calls)))
          (is (= 4 (:bytes res))))))))

(deftest unknown-length-stream-large-uses-multipart
  (testing "a stream larger than one part is uploaded multipart"
    (let [[calls stub] (recording-client)
          stream (ByteArrayInputStream. (byte-array 25))]
      (with-redefs [s3/send-request stub]
        (r2/put handle "s.bin" stream {:part-size 10 :concurrency 1})
        (is (= [:CreateMultipartUpload :UploadPart :UploadPart :UploadPart
                :CompleteMultipartUpload]
               (ops calls)))))))

(deftest multipart-failure-aborts-upload
  (testing "a failing part aborts the upload and surfaces the error"
    (let [responder (fn [req]
                      (when (= :UploadPart (req->op req))
                        {:status 403 :headers {}
                         :body (xml-body "<Error><Code>AccessDenied</Code><Message>nope</Message></Error>")}))
          [calls stub] (recording-client responder)]
      (with-redefs [s3/send-request stub]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (r2/put handle "big.bin" (byte-array 25)
                                      {:multipart-threshold 10 :part-size 10
                                       :concurrency 1})))]
          (is (= 403 (:status (ex-data ex))))
          (is (= "AccessDenied" (:code (ex-data ex))))
          (testing "upload was created, then aborted, never completed"
            (is (some #{:CreateMultipartUpload} (ops calls)))
            (is (some #{:AbortMultipartUpload} (ops calls)))
            (is (not-any? #{:CompleteMultipartUpload} (ops calls)))))))))

(deftest transient-failure-is-retried
  (testing "a 503 on a part retries and then succeeds"
    (let [attempts (atom 0)
          responder (fn [req]
                      (when (= :UploadPart (req->op req))
                        (when (= 1 (swap! attempts inc))
                          {:status 503 :headers {} :body (byte-array 0)})))
          [calls stub] (recording-client responder)]
      (with-redefs [s3/send-request stub]
        (let [res (r2/put handle "r.bin" (byte-array 10)
                          {:multipart-threshold 5 :part-size 10 :concurrency 1})]
          (is (= "\"multipart-etag\"" (:etag res)))
          (is (= 2 @attempts))
          (is (some #{:CompleteMultipartUpload} (ops calls))))))))

(deftest metadata-and-cache-control-passthrough
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (r2/put handle "x.txt" (.getBytes "hi")
              {:multipart-threshold 1024
               :content-type "text/plain; charset=utf-8"
               :cache-control "public, max-age=3600"
               :metadata {:owner "heavy" :run-id 42}})
      (let [headers (:headers (first @calls))]
        (is (= "text/plain; charset=utf-8" (get headers "Content-Type")))
        (is (= "public, max-age=3600" (get headers "Cache-Control")))
        (is (= "heavy" (get headers "x-amz-meta-owner")))
        (is (= "42" (get headers "x-amz-meta-run-id")))))))

(deftest request-escape-hatch-builds-path
  (testing "request prefixes the bucket and passes through method/query"
    (let [[calls stub] (recording-client)]
      (with-redefs [s3/send-request stub]
        (let [resp (r2/request handle {:method "GET" :key "a/b.txt"
                                       :query {"versionId" "v1"}})]
          (is (= 200 (:status resp)))
          (let [req (first @calls)]
            (is (= "GET" (:method req)))
            (is (= "/uploads/a/b.txt" (:path req)))
            (is (= {"versionId" "v1"} (:query req)))
            (is (not (contains? req :key)))))))))

;; --- reads -----------------------------------------------------------------

(deftest head-returns-parsed-metadata
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (let [meta (r2/head handle "doc.txt")]
        (is (= :HeadObject (req->op (first @calls))))
        (is (= "/uploads/doc.txt" (:path (first @calls))))
        (is (= "text/plain" (:content-type meta)))
        (is (= 5 (:bytes meta)))
        (is (= "\"obj-etag\"" (:etag meta)))
        (is (= "public, max-age=60" (:cache-control meta)))
        (is (= {"owner" "heavy"} (:metadata meta)))
        (is (= (java.time.Instant/parse "1994-11-15T08:12:31Z") (:last-modified meta)))))))

(deftest head-and-exists-on-missing-object
  (let [responder (fn [req] (when (= :HeadObject (req->op req))
                              {:status 404 :headers {} :body (byte-array 0)}))
        [_ stub] (recording-client responder)]
    (with-redefs [s3/send-request stub]
      (is (nil? (r2/head handle "missing.txt")))
      (is (false? (r2/exists? handle "missing.txt")))))
  (let [[_ stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (is (true? (r2/exists? handle "doc.txt"))))))

(deftest get-bytes-returns-contents-or-nil
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (is (= "hello" (String. ^bytes (r2/get-bytes handle "doc.txt") StandardCharsets/UTF_8)))
      (is (= :GetObject (req->op (first @calls))))))
  (let [responder (fn [_] {:status 404 :headers {} :body (byte-array 0)})
        [_ stub] (recording-client responder)]
    (with-redefs [s3/send-request stub]
      (is (nil? (r2/get-bytes handle "missing.txt"))))))

(deftest get-returns-bytes-with-metadata
  (let [[_ stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (let [obj (r2/get handle "doc.txt")]
        (is (= "hello" (String. ^bytes (:body obj) StandardCharsets/UTF_8)))
        (is (= "text/plain" (:content-type obj)))
        (is (= {"owner" "heavy"} (:metadata obj)))))))

(deftest get-stream-returns-stream-with-metadata
  (let [stub (fn [_client _req]
               {:status 200 :headers object-headers
                :body (ByteArrayInputStream. (.getBytes "hello"))})]
    (with-redefs [s3/send-streaming stub]
      (let [obj (r2/get-stream handle "doc.txt")]
        (is (instance? java.io.InputStream (:body obj)))
        (is (= "text/plain" (:content-type obj)))
        (is (= "hello" (slurp (:body obj)))))))
  (testing "missing object yields nil"
    (let [stub (fn [_client _req] {:status 404 :headers {}
                                   :body (ByteArrayInputStream. (byte-array 0))})]
      (with-redefs [s3/send-streaming stub]
        (is (nil? (r2/get-stream handle "missing.txt")))))))

(deftest get-error-status-throws
  (let [responder (fn [_] {:status 500 :headers {}
                           :body (xml-body "<Error><Code>InternalError</Code></Error>")})
        [_ stub] (recording-client responder)]
    (with-redefs [s3/send-request stub]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo (r2/get-bytes handle "doc.txt")))]
        (is (= 500 (:status (ex-data ex))))))))

;; --- deletes ---------------------------------------------------------------

(deftest delete-sends-idempotent-delete
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (is (true? (r2/delete handle "doc.txt")))
      (let [req (first @calls)]
        (is (= :DeleteObject (req->op req)))
        (is (= "/uploads/doc.txt" (:path req)))
        (is (not (contains? (:query req) "uploadId")))))))

(deftest delete-many-parses-deleted-and-errors
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (let [res (r2/delete-many handle ["a.txt" "b.txt"])]
        (is (= {:deleted ["a.txt"]
                :errors  [{:key "b.txt" :code "AccessDenied" :message "no"}]}
               res))
        (let [req (first @calls)]
          (is (= :DeleteObjects (req->op req)))
          (is (= "/uploads" (:path req)))
          (is (contains? (:headers req) "Content-MD5")))))))

(deftest delete-many-chunks-above-1000-keys
  (let [[calls stub] (recording-client)
        keys (map #(str "k" %) (range 1500))]
    (with-redefs [s3/send-request stub]
      (r2/delete-many handle keys)
      (is (= 2 (count (filter #(= :DeleteObjects (req->op %)) @calls))))))
  (testing "empty key list makes no request"
    (let [[calls stub] (recording-client)]
      (with-redefs [s3/send-request stub]
        (is (= {:deleted [] :errors []} (r2/delete-many handle [])))
        (is (empty? @calls))))))

;; --- listing ---------------------------------------------------------------

(deftest list-parses-a-page
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (let [page (r2/list handle {:prefix "a" :delimiter "/" :max-keys 100})]
        (is (= :ListObjectsV2 (req->op (first @calls))))
        (is (= {"list-type" "2" "prefix" "a" "delimiter" "/" "max-keys" "100"}
               (:query (first @calls))))
        (is (= ["a.txt" "b.txt"] (map :key (:objects page))))
        (is (= [10 20] (map :size (:objects page))))
        (is (= ["sub/"] (:common-prefixes page)))
        (is (false? (:truncated? page)))
        (is (= (java.time.Instant/parse "2009-10-12T17:50:30.000Z")
               (:last-modified (first (:objects page)))))))))

(deftest list-seq-follows-pagination
  (let [calls (atom [])
        responder (fn [req]
                    (swap! calls conj req)
                    (let [page1? (nil? (get-in req [:query "continuation-token"]))]
                      {:status 200 :headers {}
                       :body (xml-body
                              (if page1?
                                (str "<ListBucketResult>"
                                     "<Contents><Key>a.txt</Key></Contents>"
                                     "<IsTruncated>true</IsTruncated>"
                                     "<NextContinuationToken>TOKEN2</NextContinuationToken>"
                                     "</ListBucketResult>")
                                (str "<ListBucketResult>"
                                     "<Contents><Key>b.txt</Key></Contents>"
                                     "<IsTruncated>false</IsTruncated>"
                                     "</ListBucketResult>")))}))]
    (with-redefs [s3/send-request (fn [_ req] (responder req))]
      (is (= ["a.txt" "b.txt"] (map :key (r2/list-seq handle {:prefix "x"}))))
      (is (= 2 (count @calls)))
      (is (= "TOKEN2" (get-in (second @calls) [:query "continuation-token"])))
      (is (= "x" (get-in (second @calls) [:query "prefix"]))))))

;; --- presigned urls --------------------------------------------------------

(deftest presigned-url-structure-and-validation
  (let [bkt (r2/bucket {:account-id "acct" :access-key-id "AK"
                        :secret-access-key "SK" :bucket "uploads"})]
    (testing "GET download URL"
      (let [url (r2/presigned-url bkt "a/b.txt")]
        (is (str/starts-with? url "https://acct.r2.cloudflarestorage.com/uploads/a/b.txt?"))
        (is (str/includes? url "X-Amz-Expires=900"))
        (is (str/includes? url "X-Amz-Signature="))
        (is (str/includes? url "X-Amz-Credential="))))
    (testing "PUT upload URL with custom expiry"
      (let [url (r2/presigned-url bkt "up.bin" {:method :put :expires 3600})]
        (is (str/includes? url "X-Amz-Expires=3600"))
        (is (str/includes? url "X-Amz-Signature="))))
    (testing "expiry bounds are validated"
      (is (thrown? clojure.lang.ExceptionInfo (r2/presigned-url bkt "k" {:expires 0})))
      (is (thrown? clojure.lang.ExceptionInfo (r2/presigned-url bkt "k" {:expires 999999}))))))

;; --- browser-direct multipart uploads --------------------------------------

(deftest create-multipart-upload-returns-handle
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (let [up (r2/create-multipart-upload handle "archives/pack.zip"
                                           {:metadata {:owner "heavy"}})]
        (is (= {:key "archives/pack.zip" :bucket "uploads" :upload-id "upload-1"} up))
        (let [req (first @calls)]
          (is (= :CreateMultipartUpload (req->op req)))
          (is (= "POST" (:method req)))
          (is (= "/uploads/archives/pack.zip" (:path req)))
          (testing "content-type inferred and metadata set on the create request"
            (is (= "application/zip" (get-in req [:headers "Content-Type"])))
            (is (= "heavy" (get-in req [:headers "x-amz-meta-owner"])))))))))

(deftest presigned-upload-part-signs-a-put-url-for-the-part
  (let [bkt (r2/bucket {:account-id "acct" :access-key-id "AK"
                        :secret-access-key "SK" :bucket "uploads"})
        url (r2/presigned-upload-part bkt "pack.zip" "UP123" 2 {:expires 21600})]
    (is (str/starts-with? url "https://acct.r2.cloudflarestorage.com/uploads/pack.zip?"))
    (is (str/includes? url "partNumber=2"))
    (is (str/includes? url "uploadId=UP123"))
    (is (str/includes? url "X-Amz-Expires=21600"))
    (is (str/includes? url "X-Amz-Signature="))
    (testing "expiry bounds are validated"
      (is (thrown? clojure.lang.ExceptionInfo
                   (r2/presigned-upload-part bkt "k" "U" 1 {:expires 0})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (r2/presigned-upload-part bkt "k" "U" 1 {:expires 999999}))))))

(deftest complete-multipart-upload-sorts-parts-and-returns-etag
  (let [[calls stub] (recording-client)]
    (with-redefs [s3/send-request stub]
      (let [res (r2/complete-multipart-upload
                 handle "pack.zip" "UP123"
                 ;; supplied out of order; ETags as the browser reported them
                 [{:part-number 2 :etag "\"etag-2\""}
                  {:part-number 1 :etag "\"etag-1\""}])]
        (is (= {:key "pack.zip" :bucket "uploads" :etag "\"multipart-etag\""} res))
        (let [req (first @calls)]
          (is (= :CompleteMultipartUpload (req->op req)))
          (is (= "UP123" (get-in req [:query "uploadId"])))
          (testing "body lists parts sorted by number with their etags"
            (is (= (str "<CompleteMultipartUpload>"
                        "<Part><PartNumber>1</PartNumber><ETag>\"etag-1\"</ETag></Part>"
                        "<Part><PartNumber>2</PartNumber><ETag>\"etag-2\"</ETag></Part>"
                        "</CompleteMultipartUpload>")
                   (String. ^bytes (:body req) StandardCharsets/UTF_8)))))))))

(deftest abort-multipart-upload-is-idempotent-but-surfaces-real-errors
  (testing "a normal abort sends DELETE with the uploadId and returns true"
    (let [[calls stub] (recording-client)]
      (with-redefs [s3/send-request stub]
        (is (true? (r2/abort-multipart-upload handle "pack.zip" "UP123")))
        (let [req (first @calls)]
          (is (= :AbortMultipartUpload (req->op req)))
          (is (= "/uploads/pack.zip" (:path req)))
          (is (= "UP123" (get-in req [:query "uploadId"])))))))
  (testing "a 404 (upload already gone) is treated as success"
    (let [responder (fn [_] {:status 404 :headers {} :body (byte-array 0)})
          [_ stub] (recording-client responder)]
      (with-redefs [s3/send-request stub]
        (is (true? (r2/abort-multipart-upload handle "pack.zip" "UP123"))))))
  (testing "a real error status throws"
    (let [responder (fn [_] {:status 403 :headers {}
                             :body (xml-body "<Error><Code>AccessDenied</Code></Error>")})
          [_ stub] (recording-client responder)]
      (with-redefs [s3/send-request stub]
        (let [ex (is (thrown? clojure.lang.ExceptionInfo
                              (r2/abort-multipart-upload handle "pack.zip" "UP123")))]
          (is (= 403 (:status (ex-data ex)))))))))
