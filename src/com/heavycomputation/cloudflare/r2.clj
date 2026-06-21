(ns com.heavycomputation.cloudflare.r2
  "Cloudflare R2 object storage.

  R2 speaks the S3 API, so this namespace signs requests with AWS Signature V4
  and sends them over the JDK's http client (see the `internal` namespaces) —
  no AWS SDK, no dependencies. The public API:

    (bucket m)                ; build a bucket handle
    (put bucket key body)     ; upload an object, multipart automatically
    (get-bytes bucket key)    ; download; also head/exists?/get/get-stream
    (delete bucket key)       ; remove; also delete-many for batches
    (list bucket opts)        ; enumerate by prefix; also list-seq (lazy)
    (presigned-url bucket k)  ; time-limited download/upload URL
    (request bucket req)      ; escape hatch to the raw signed-S3 surface

  `put` hides the single-PUT vs. multipart decision: small objects go up in one
  request, large objects (and unknown-length streams) are split into parts that
  upload concurrently and retry individually, so a network blip only costs the
  part that failed rather than the whole transfer.

  `get`/`head`/`list` shadow `clojure.core` names, so this namespace excludes
  them; refer to it qualified (e.g. `r2/get`)."
  (:refer-clojure :exclude [get list])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.heavycomputation.cloudflare.internal.s3 :as s3]
            [com.heavycomputation.cloudflare.internal.xml :as xml])
  (:import (java.io InputStream)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Instant)
           (java.time.format DateTimeFormatter)
           (java.util Arrays Base64)
           (java.util.concurrent Callable ExecutionException Executors
                                 Future Semaphore)))

;; ---------------------------------------------------------------------------
;; Defaults
;; ---------------------------------------------------------------------------

(def ^:private mib (* 1024 1024))

;; R2/S3 cap a single PutObject at 5 GiB. We switch to multipart well before
;; that so a failed transfer doesn't have to restart from byte zero.
(def ^:private default-multipart-threshold (* 100 mib))
(def ^:private default-part-size           (* 100 mib))
(def ^:private default-concurrency         4)
(def ^:private max-attempts                4)

;; ---------------------------------------------------------------------------
;; Content-type inference
;; ---------------------------------------------------------------------------

(def ^:private content-types
  "Extension -> Content-Type for the formats we actually serve. Anything not
  here falls back to application/octet-stream."
  {"zip"  "application/zip"     "gz"   "application/gzip"
   "tar"  "application/x-tar"   "bin"  "application/octet-stream"
   "json" "application/json"    "xml"  "application/xml"
   "csv"  "text/csv"            "txt"  "text/plain"
   "md"   "text/markdown"       "html" "text/html"
   "htm"  "text/html"           "css"  "text/css"
   "js"   "text/javascript"     "mjs"  "text/javascript"
   "yaml" "application/yaml"     "yml"  "application/yaml"
   "pdf"  "application/pdf"     "parquet" "application/vnd.apache.parquet"
   "png"  "image/png"           "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"          "gif"  "image/gif"
   "webp" "image/webp"          "svg"  "image/svg+xml"
   "ico"  "image/x-icon"        "mp4"  "video/mp4"
   "mp3"  "audio/mpeg"          "wav"  "audio/wav"
   "woff" "font/woff"           "woff2" "font/woff2"
   "ttf"  "font/ttf"})

(defn content-type-for
  "Infer a Content-Type from the extension of object key `k`, defaulting to
  \"application/octet-stream\" when the extension is unknown or absent."
  [k]
  (let [ext (some-> (re-find #"\.([^./\\]+)$" (str k)) second str/lower-case)]
    (or (content-types ext) "application/octet-stream")))

;; ---------------------------------------------------------------------------
;; Errors and retries
;; ---------------------------------------------------------------------------

(defn- body-string
  "Response body as a string, whether it arrived buffered (byte[]) or streamed
  (InputStream). Used for the small XML bodies S3 returns."
  [resp]
  (let [b (:body resp)]
    (cond
      (nil? b)                  nil
      (bytes? b)                (String. ^bytes b StandardCharsets/UTF_8)
      (instance? InputStream b) (slurp b))))

(defn- s3-error
  "Turn a non-2xx response into an ex-info, carrying the parsed S3 error code
  and whether it is worth retrying (5xx and 429 are transient)."
  [op {:keys [status] :as resp}]
  (let [xml     (body-string resp)
        code    (xml/tag-text xml "Code")
        message (xml/tag-text xml "Message")]
    (ex-info (str "R2 " (name op) " failed: HTTP " status
                  (when code (str " " code))
                  (when message (str " — " message)))
             {:op op :status status :code code :message message
              :retryable? (or (>= status 500) (= status 429))})))

(defn- send!
  "Send a request and return its response, or throw on a 4xx/5xx status."
  [client op req]
  (let [resp (s3/send-request client req)]
    (if (< (:status resp) 400)
      resp
      (throw (s3-error op resp)))))

(defn- retryable-ex? [e]
  (or (instance? java.io.IOException e)
      (and (instance? clojure.lang.ExceptionInfo e)
           (:retryable? (ex-data e)))))

(defn- backoff-ms ^long [attempt]
  (min (* 100 (bit-shift-left 1 (dec attempt))) 2000))

(defn- with-retries
  "Call `f`, retrying transient failures (network errors and 5xx/429 responses)
  up to `max-attempts` times with exponential backoff."
  [f]
  (loop [attempt 1]
    (let [outcome (try
                    {:value (f)}
                    (catch Exception e
                      (if (and (< attempt max-attempts) (retryable-ex? e))
                        {:retry e}
                        (throw e))))]
      (if (:retry outcome)
        (do (Thread/sleep (backoff-ms attempt))
            (recur (inc attempt)))
        (:value outcome)))))

;; ---------------------------------------------------------------------------
;; Body handling
;; ---------------------------------------------------------------------------

(defn- body-length
  "Known length of `body` in bytes, or nil if it can't be determined cheaply
  (i.e. a generic InputStream)."
  [body]
  (cond
    (instance? java.io.File body) (.length ^java.io.File body)
    (bytes? body)                 (alength ^bytes body)
    :else                         nil))

(defn- read-chunk
  "Read up to `n` bytes from `in` into a fresh byte[], sized to the number of
  bytes actually read. Returns a zero-length array at end of stream."
  ^bytes [^InputStream in ^long n]
  (let [buf (byte-array n)]
    (loop [off 0]
      (if (= off n)
        buf
        (let [r (.read in buf off (- n off))]
          (if (neg? r)
            (Arrays/copyOf buf off)
            (recur (+ off r))))))))

;; ---------------------------------------------------------------------------
;; Object requests
;; ---------------------------------------------------------------------------

(defn- object-path [bucket key]
  (str "/" bucket "/" key))

(defn- object-headers
  "Headers that describe the object itself (set on PutObject or, for multipart,
  on CreateMultipartUpload)."
  [key {:keys [content-type metadata cache-control]}]
  (cond-> {"Content-Type" (or content-type (content-type-for key))}
    cache-control  (assoc "Cache-Control" cache-control)
    (seq metadata) (merge (reduce-kv (fn [acc k v]
                                       (assoc acc (str "x-amz-meta-" (name k)) (str v)))
                                     {} metadata))))

(def ^:private meta-prefix "x-amz-meta-")

(defn- user-metadata
  "Custom metadata pulled back out of `x-amz-meta-*` response headers, keyed by
  the bare name (the inverse of how `put` stores `:metadata`)."
  [headers]
  (reduce-kv (fn [acc k v]
               (if (str/starts-with? k meta-prefix)
                 (assoc acc (subs k (count meta-prefix)) (first v))
                 acc))
             {} headers))

(defn- parse-instant [parse-fn s]
  (when s (try (parse-fn s) (catch Exception _ nil))))

(defn- object-meta
  "Object metadata read from a GET/HEAD response's headers. `Last-Modified` is
  an HTTP date (RFC 1123); parsed to an Instant."
  [resp]
  {:content-type  (s3/header resp "Content-Type")
   :bytes         (some-> (s3/header resp "Content-Length") Long/parseLong)
   :etag          (s3/header resp "ETag")
   :last-modified (parse-instant #(Instant/from (.parse DateTimeFormatter/RFC_1123_DATE_TIME %))
                                 (s3/header resp "Last-Modified"))
   :cache-control (s3/header resp "Cache-Control")
   :metadata      (user-metadata (:headers resp))})

;; ---------------------------------------------------------------------------
;; Upload strategies
;; ---------------------------------------------------------------------------

(defn- put-object
  "Upload `data` (a byte[]) in a single request; returns its ETag."
  [client bucket key headers ^bytes data]
  (-> (with-retries
        #(send! client :PutObject
                {:method "PUT" :path (object-path bucket key)
                 :headers headers :body data}))
      (s3/header "ETag")))

(defn- create-multipart [client bucket key headers]
  (-> (send! client :CreateMultipartUpload
             {:method "POST" :path (object-path bucket key)
              :query {"uploads" ""} :headers headers})
      body-string
      (xml/tag-text "UploadId")))

(defn- upload-part
  "Upload one part and return its ETag, retrying transient failures."
  [client bucket key upload-id part-number ^bytes data]
  (-> (with-retries
        #(send! client :UploadPart
                {:method "PUT" :path (object-path bucket key)
                 :query {"partNumber" (str part-number) "uploadId" upload-id}
                 :body data}))
      (s3/header "ETag")))

(defn- complete-multipart [client bucket key upload-id parts]
  (let [^String xml-out (xml/complete-multipart-upload parts)
        body (.getBytes xml-out StandardCharsets/UTF_8)
        resp (send! client :CompleteMultipartUpload
                    {:method "POST" :path (object-path bucket key)
                     :query {"uploadId" upload-id}
                     :headers {"Content-Type" "application/xml"}
                     :body body})
        xml  (body-string resp)]
    ;; CompleteMultipartUpload can answer 200 with an <Error> body — check it.
    (when-let [code (xml/tag-text xml "Code")]
      (throw (ex-info (str "R2 CompleteMultipartUpload failed: " code)
                      {:op :CompleteMultipartUpload :code code
                       :message (xml/tag-text xml "Message")})))
    (xml/tag-text xml "ETag")))

(defn- abort-multipart [client bucket key upload-id]
  (s3/send-request client {:method "DELETE" :path (object-path bucket key)
                           :query {"uploadId" upload-id}}))

(defn- await-part [[part-number ^Future fut]]
  (try
    {:PartNumber part-number :ETag (.get fut)}
    (catch ExecutionException e
      (throw (.getCause e)))))

(defn- put-multipart
  "Stream `in` to R2 as a multipart upload, starting from already-read
  `first-chunk`. Parts upload concurrently (bounded by `concurrency`); any
  failure aborts the upload so no orphaned parts are left billing. Returns
  `{:etag … :bytes …}`."
  [client bucket key headers ^InputStream in part-size concurrency ^bytes first-chunk]
  (let [upload-id (create-multipart client bucket key headers)
        pool (Executors/newFixedThreadPool concurrency)
        sem  (Semaphore. concurrency)]
    (try
      (let [[parts total]
            (loop [part-number 1, chunk first-chunk, pending [], total 0]
              (if (zero? (alength chunk))
                [pending total]
                (let [data chunk
                      pn   part-number]
                  (.acquire sem)
                  (let [fut (.submit pool ^Callable
                                     (fn []
                                       (try
                                         (upload-part client bucket key upload-id pn data)
                                         (finally (.release sem)))))]
                    (recur (inc part-number)
                           (read-chunk in part-size)
                           (conj pending [pn fut])
                           (+ total (alength data)))))))
            completed (->> parts (map await-part) (sort-by :PartNumber) vec)
            etag      (complete-multipart client bucket key upload-id completed)]
        {:etag etag :bytes total})
      (catch Throwable t
        ;; Best-effort cleanup; never mask the original failure.
        (try (abort-multipart client bucket key upload-id)
             (catch Throwable _ nil))
        (throw t))
      (finally
        (.shutdown pool)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn bucket
  "Build an R2 bucket handle from a config map:

    {:account-id        \"abc123\"
     :access-key-id     \"...\"
     :secret-access-key \"...\"
     :bucket            \"uploads\"
     :region            \"auto\"}   ; optional, see note below

  `:region` is the SigV4 *signing* region. R2 accepts only its own region codes
  (`auto`, `weur`, `enam`, `wnam`, `eeur`, `apac`, `oc`) and rejects AWS regions
  like `eu-west-2` with `InvalidRegionName`. Because we sign requests ourselves
  rather than going through an SDK's endpoint resolver, we can hand R2 its own
  code directly; `auto` (the default) works for every bucket regardless of where
  it lives.

  Returns `{:client <s3 client> :bucket <name>}`, suitable for `put` and
  `request`."
  [{:keys [account-id access-key-id secret-access-key bucket region]
    :or   {region "auto"}}]
  (when (str/blank? account-id)
    (throw (ex-info "R2 bucket requires :account-id" {})))
  (when (str/blank? bucket)
    (throw (ex-info "R2 bucket requires :bucket" {})))
  {:client (s3/client
            {:endpoint          (str "https://" account-id ".r2.cloudflarestorage.com")
             :region            region
             :access-key-id     access-key-id
             :secret-access-key secret-access-key})
   :bucket bucket})

(defn request
  "Escape hatch to the raw signed-S3 surface for ops `put` doesn't cover (range
  gets, conditional ops, tagging, listing, ...).

  `req` is `{:method :key :query :headers :body}`: `:method` is the HTTP verb,
  `:key` (optional) the object key, `:query`/`:headers` maps, `:body` a byte[].
  The path is built from the handle's bucket (and `:key` if given). Returns the
  raw response `{:status :headers :body}` without throwing on 4xx/5xx, so the
  caller controls error handling."
  [{:keys [client bucket]} {:keys [key] :as req}]
  (s3/send-request client (-> req
                              (dissoc :key)
                              (assoc :path (if key
                                             (object-path bucket key)
                                             (str "/" bucket))))))

(defn put
  "Upload `body` to `bucket` under `key`, choosing single-PUT or multipart
  automatically.

  `body` may be a `java.io.File`, `byte[]`, or `InputStream`. Content-Type is
  inferred from the key's extension unless overridden.

  Options (all optional):
    :content-type         override the inferred Content-Type
    :multipart-threshold  bytes at/above which multipart kicks in (~100 MB)
    :part-size            multipart part size in bytes (~100 MB)
    :concurrency          number of parts uploaded at once (4)
    :metadata             map of custom metadata (stored as x-amz-meta-*)
    :cache-control        Cache-Control header value

  Returns `{:key key :bucket bucket :etag etag :bytes n}`. Throws `ex-info` on
  failure; an interrupted multipart upload is aborted first."
  [{:keys [client bucket]} key body & [opts]]
  (let [{:keys [multipart-threshold part-size concurrency]
         :or   {multipart-threshold default-multipart-threshold
                part-size           default-part-size
                concurrency         default-concurrency}} opts
        headers (object-headers key opts)
        length  (body-length body)
        result  (fn [etag bytes] {:key key :bucket bucket :etag etag :bytes bytes})]
    (if (and length (< length multipart-threshold))
      ;; Known small object: read it whole (safe to retry) and PUT once.
      (with-open [in (io/input-stream body)]
        (let [^bytes data (read-chunk in length)]
          (result (put-object client bucket key headers data) (alength data))))
      ;; Large or unknown-length: peek one part, then decide. A stream that
      ;; turns out to fit in a single part still goes up as a plain PUT.
      (let [in          (io/input-stream body)
            ^bytes chunk (read-chunk in part-size)]
        (try
          (cond
            (zero? (alength chunk))
            (result (put-object client bucket key headers (byte-array 0)) 0)

            (< (alength chunk) part-size)
            (result (put-object client bucket key headers chunk) (alength chunk))

            :else
            (let [{:keys [etag bytes]}
                  (put-multipart client bucket key headers in part-size concurrency chunk)]
              (result etag bytes)))
          (finally
            (.close ^InputStream in)))))))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn- get-response
  "Send a GET (or HEAD) and return the response on success, `nil` on 404, or
  throw on any other error status. `send` is `s3/send-request` (buffered) or
  `s3/send-streaming`."
  [send client op req]
  (let [resp (send client req)]
    (cond
      (< (:status resp) 300)  resp
      (= 404 (:status resp))  (do (when (instance? InputStream (:body resp))
                                    (.close ^InputStream (:body resp)))
                                  nil)
      :else                   (throw (s3-error op resp)))))

(defn head
  "Object metadata without its body, or `nil` if the object doesn't exist.
  Returns `{:content-type :bytes :etag :last-modified :cache-control
  :metadata}`."
  [{:keys [client bucket]} key]
  (some-> (get-response s3/send-request client :HeadObject
                        {:method "HEAD" :path (object-path bucket key)})
          object-meta))

(defn exists?
  "Whether an object exists under `key`."
  [bucket key]
  (some? (head bucket key)))

(defn get-stream
  "Fetch an object for streaming: returns its metadata (see `head`) plus `:body`
  bound to an `InputStream`, or `nil` if it doesn't exist. The caller must close
  `:body` (e.g. with `with-open`) — it holds the live connection."
  [{:keys [client bucket]} key]
  (when-let [resp (get-response s3/send-streaming client :GetObject
                                {:method "GET" :path (object-path bucket key)})]
    (assoc (object-meta resp) :body (:body resp))))

(defn get-bytes
  "Fetch an object's contents as a `byte[]`, or `nil` if it doesn't exist.
  Reads the whole object into memory — use `get-stream` for large objects."
  [{:keys [client bucket]} key]
  (when-let [resp (get-response s3/send-request client :GetObject
                                {:method "GET" :path (object-path bucket key)})]
    (:body resp)))

(defn get
  "Fetch an object: its metadata (see `head`) plus `:body` bound to a `byte[]`,
  or `nil` if it doesn't exist. Buffers the whole object in memory — use
  `get-stream` for large objects."
  [{:keys [client bucket]} key]
  (when-let [resp (get-response s3/send-request client :GetObject
                                {:method "GET" :path (object-path bucket key)})]
    (assoc (object-meta resp) :body (:body resp))))

;; ---------------------------------------------------------------------------
;; Deletes
;; ---------------------------------------------------------------------------

(defn delete
  "Delete the object at `key`. Idempotent — succeeds whether or not it existed.
  Returns `true`."
  [{:keys [client bucket]} key]
  (send! client :DeleteObject {:method "DELETE" :path (object-path bucket key)})
  true)

(defn- content-md5 [^bytes data]
  (.encodeToString (Base64/getEncoder)
                   (.digest (MessageDigest/getInstance "MD5") data)))

(defn- delete-batch
  [{:keys [client bucket]} keys]
  (let [body (.getBytes ^String (xml/delete-objects keys) StandardCharsets/UTF_8)
        resp (send! client :DeleteObjects
                    {:method "POST" :path (str "/" bucket) :query {"delete" ""}
                     :headers {"Content-Type" "application/xml"
                               "Content-MD5"  (content-md5 body)}
                     :body body})
        xml  (body-string resp)]
    {:deleted (mapv #(xml/tag-text % "Key") (xml/tag-blocks xml "Deleted"))
     :errors  (mapv (fn [b] {:key     (xml/tag-text b "Key")
                             :code    (xml/tag-text b "Code")
                             :message (xml/tag-text b "Message")})
                    (xml/tag-blocks xml "Error"))}))

(defn delete-many
  "Delete many objects in one (or, above 1000 keys, a few) batched requests.
  Returns `{:deleted [key …] :errors [{:key :code :message} …]}`."
  [bucket keys]
  (reduce (fn [acc batch]
            (let [{:keys [deleted errors]} (delete-batch bucket batch)]
              (-> acc
                  (update :deleted into deleted)
                  (update :errors  into errors))))
          {:deleted [] :errors []}
          (partition-all 1000 keys)))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(defn- parse-object [block]
  {:key           (xml/tag-text block "Key")
   :size          (some-> (xml/tag-text block "Size") Long/parseLong)
   :etag          (xml/tag-text block "ETag")
   :last-modified (parse-instant #(Instant/parse %) (xml/tag-text block "LastModified"))})

(defn list
  "List one page of objects. Options:
    :prefix              restrict to keys starting with this string
    :delimiter           roll keys up to common prefixes (e.g. \"/\" for folders)
    :max-keys            page size (S3 default/cap is 1000)
    :continuation-token  fetch the page following a previous truncated result

  Returns `{:objects [{:key :size :etag :last-modified} …]
            :common-prefixes [\"a/\" …]
            :truncated? bool
            :continuation-token <token-or-nil>}`. When `:truncated?` is true,
  pass `:continuation-token` back to get the next page (or use `list-seq`)."
  [{:keys [client bucket]} & [{:keys [prefix delimiter max-keys continuation-token]}]]
  (let [query (cond-> {"list-type" "2"}
                prefix             (assoc "prefix" prefix)
                delimiter          (assoc "delimiter" delimiter)
                max-keys           (assoc "max-keys" (str max-keys))
                continuation-token (assoc "continuation-token" continuation-token))
        xml   (body-string (send! client :ListObjectsV2
                                  {:method "GET" :path (str "/" bucket) :query query}))]
    {:objects            (mapv parse-object (xml/tag-blocks xml "Contents"))
     :common-prefixes    (mapv #(xml/tag-text % "Prefix") (xml/tag-blocks xml "CommonPrefixes"))
     :truncated?         (= "true" (xml/tag-text xml "IsTruncated"))
     :continuation-token (xml/tag-text xml "NextContinuationToken")}))

(defn list-seq
  "Lazily enumerate every object matching `opts` (same options as `list`, minus
  `:continuation-token`), transparently following pagination. Each element is an
  object map `{:key :size :etag :last-modified}`. Pages are fetched as the seq
  is consumed, so realizing it performs I/O and may throw."
  [bucket & [opts]]
  (letfn [(pages [token]
            (let [{:keys [objects truncated? continuation-token]}
                  (list bucket (assoc opts :continuation-token token))]
              (concat objects
                      (when truncated?
                        (lazy-seq (pages continuation-token))))))]
    (lazy-seq (pages nil))))

;; ---------------------------------------------------------------------------
;; Presigned URLs
;; ---------------------------------------------------------------------------

(def ^:private max-expires-seconds (* 7 24 60 60)) ; SigV4 caps presign at 7 days

(defn presigned-url
  "Build a presigned URL for `key` that grants temporary, credential-free access
  — anyone with the URL can use it until it expires. Options:
    :method   :get (default) for downloads, :put for direct browser uploads
    :expires  validity window in seconds (default 900 = 15 min; max 7 days)

  For `:put`, a client `PUT`s the file straight to the returned URL, so large
  uploads never pass through your server. Returns the URL string."
  [{:keys [client bucket]} key & [{:keys [method expires]
                                   :or   {method :get expires 900}}]]
  (when-not (<= 1 expires max-expires-seconds)
    (throw (ex-info "presigned-url :expires must be between 1 and 604800 seconds (7 days)"
                    {:expires expires})))
  (s3/presign client {:method  (-> method name str/upper-case)
                      :path    (object-path bucket key)
                      :expires expires}))
