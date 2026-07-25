(ns com.heavycomputation.cloudflare.images
  "Cloudflare Images: store, manage, and serve images.

  Unlike R2 (which speaks S3 and is signed with SigV4), Images has two distinct
  surfaces, and this namespace covers both:

    - the **management API** — Cloudflare's REST API v4, authenticated with a
      Bearer token (see `internal.api`) — for uploading, listing, updating and
      deleting images; and
    - **delivery** — images are *served* from `imagedelivery.net/<hash>/<id>/
      <variant>`, a URL this namespace builds (and, for private images, signs)
      with no network round-trip.

  The public API:

    (client m)                     ; build a client handle
    (upload client body)           ; upload a File/byte[]/InputStream
    (upload-url client url)        ; ingest a remote image by URL
    (direct-upload-url client)     ; a one-time URL the browser uploads to
    (get client id)                ; fetch details; also head-less exists?
    (list client) / (list-seq …)   ; enumerate images (v2 cursor pagination)
    (update client id m)           ; edit metadata / requireSignedURLs
    (delete client id)             ; remove an image (idempotent)
    (get-bytes client id)          ; original bytes; also get-stream
    (stats client)                 ; usage counts
    (signing-keys client)          ; the URL-signing keys
    (delivery-url client id v)     ; build (optionally signed) a delivery URL
    (request client req)           ; escape hatch to the raw v4 API

  `get`/`list`/`update` shadow `clojure.core` names, so this namespace excludes
  them; refer to them qualified (e.g. `images/get`)."
  (:refer-clojure :exclude [get list update])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.heavycomputation.cloudflare.internal.api :as api]
            [com.heavycomputation.cloudflare.internal.json :as json])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.time Instant)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private default-base-url    "https://api.cloudflare.com/client/v4")
(def ^:private delivery-base       "https://imagedelivery.net")
(def ^:private max-attempts        4)
(def ^:private default-sign-expiry (* 60 60)) ; 1 hour

;; ---------------------------------------------------------------------------
;; Content-type inference (cosmetic — Cloudflare re-detects server-side, but a
;; correct Content-Type on the multipart file part is polite and aids debugging)
;; ---------------------------------------------------------------------------

(def ^:private content-types
  {"png"  "image/png"   "jpg"  "image/jpeg"  "jpeg" "image/jpeg"
   "gif"  "image/gif"   "webp" "image/webp"  "avif" "image/avif"
   "svg"  "image/svg+xml" "bmp" "image/bmp"  "tif"  "image/tiff"
   "tiff" "image/tiff"  "ico"  "image/x-icon" "heic" "image/heic"})

(defn content-type-for
  "Infer an image Content-Type from the extension of `name`, defaulting to
  \"application/octet-stream\" when the extension is unknown or absent."
  [name]
  (let [ext (some-> (re-find #"\.([^./\\]+)$" (str name)) second str/lower-case)]
    (or (content-types ext) "application/octet-stream")))

;; ---------------------------------------------------------------------------
;; Errors and retries
;; ---------------------------------------------------------------------------

(defn- api-error
  "Turn a failed API response into an ex-info carrying Cloudflare's first error
  `code`/`message` (from the JSON envelope) and whether it's worth retrying."
  [op {:keys [status] :as _resp} parsed]
  (let [errors (clojure.core/get parsed "errors")
        {:strs [code message]} (first errors)]
    (ex-info (str "Cloudflare Images " (name op) " failed: HTTP " status
                  (when code (str " [" code "]"))
                  (when message (str " — " message)))
             {:op op :status status :code code :message message :errors errors
              :retryable? (or (>= status 500) (= status 429))})))

(defn- envelope
  "Parse a v4 JSON-envelope response; return its `result` on success, else throw
  an `api-error`."
  [op {:keys [status body] :as resp}]
  (let [parsed (json/read body)]
    (if (and (< status 300) (clojure.core/get parsed "success"))
      (clojure.core/get parsed "result")
      (throw (api-error op resp parsed)))))

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
;; Result shaping
;; ---------------------------------------------------------------------------

(defn- parse-instant [s]
  (when s (try (Instant/parse s) (catch Exception _ nil))))

(defn- ->image
  "Normalize a Cloudflare image record (string keys, camelCase) into our shape."
  [m]
  (when m
    (let [{:strs [id filename uploaded requireSignedURLs meta variants draft]} m]
      (cond-> {:id                  id
               :filename            filename
               :uploaded            (parse-instant uploaded)
               :require-signed-urls requireSignedURLs
               :meta                meta
               :variants            variants}
        (some? draft) (assoc :draft draft)))))

;; ---------------------------------------------------------------------------
;; Client + request helpers
;; ---------------------------------------------------------------------------

(defn client
  "Build a Cloudflare Images client handle from a config map:

    {:account-id   \"abc123\"      ; required — drives the management API
     :api-token    \"…\"           ; required — a Cloudflare API token
     :account-hash \"aBcD…\"       ; for building delivery URLs (a *different*
                                   ;   value from :account-id; find it in the
                                   ;   Images dashboard)
     :signing-key  \"…\"}          ; optional — the URL-signing key, needed only
                                   ;   for signed (private-image) delivery URLs

  `:account-hash` and `:signing-key` are only consulted by `delivery-url`; the
  management calls need just `:account-id` and `:api-token`. Returns a handle
  suitable for every function in this namespace."
  [{:keys [account-id api-token account-hash signing-key base-url http-client]}]
  (when (str/blank? account-id)
    (throw (ex-info "Images client requires :account-id" {})))
  (when (str/blank? api-token)
    (throw (ex-info "Images client requires :api-token" {})))
  {:api          (api/client {:api-token   api-token
                              :base-url    (or base-url default-base-url)
                              :http-client http-client})
   :account-id   account-id
   :account-hash account-hash
   :signing-key  signing-key})

(defn- images-path
  "Path under the account's Images root, e.g. (images-path c \"/v1/abc\")."
  [{:keys [account-id]} sub]
  (str "/accounts/" account-id "/images" sub))

(defn- bool-part [parts field v]
  (if (some? v)
    (conj parts {:name field :content (str (boolean v))})
    parts))

;; ---------------------------------------------------------------------------
;; Uploads
;; ---------------------------------------------------------------------------

(defn- to-bytes ^bytes [body]
  (cond
    (bytes? body)                 body
    (instance? java.io.File body) (Files/readAllBytes (.toPath ^java.io.File body))
    (instance? InputStream body)  (let [out (ByteArrayOutputStream.)]
                                    (io/copy body out)
                                    (.toByteArray out))
    (string? body)
    (throw (ex-info "upload takes a File/byte[]/InputStream; for a remote URL use upload-url"
                    {:type (type body)}))
    :else (throw (ex-info "Unsupported upload body" {:type (type body)}))))

(defn- file-name [body]
  (when (instance? java.io.File body) (.getName ^java.io.File body)))

(defn upload
  "Upload `body` — a `java.io.File`, `byte[]`, or `InputStream` — to Cloudflare
  Images. Options (all optional):
    :id                   custom image id (else Cloudflare assigns a UUID)
    :metadata             map of arbitrary metadata (never shown to end users)
    :require-signed-urls  when true, the image can only be served via a signed
                          delivery URL (see `delivery-url`)
    :filename             name recorded for the image (defaults to the File's
                          name, else \"upload\")
    :content-type         Content-Type for the upload part (else inferred from
                          the filename extension)

  Returns `{:id :filename :uploaded :require-signed-urls :meta :variants}`
  (`:variants` are ready-to-use delivery URLs). Throws `ex-info` on failure."
  [client body & [{:keys [id metadata require-signed-urls filename content-type]}]]
  (let [^bytes data (to-bytes body)
        fname (or filename (file-name body) "upload")
        parts (cond-> [{:name "file" :content data :filename fname
                        :content-type (or content-type (content-type-for fname))}]
                metadata (conj {:name "metadata" :content (json/write metadata)})
                :always  (bool-part "requireSignedURLs" require-signed-urls)
                id       (conj {:name "id" :content id}))]
    (->image
     (with-retries
       #(envelope :upload
                  (api/send-request (:api client)
                                    {:method "POST" :path (images-path client "/v1")
                                     :multipart parts}))))))

(defn upload-url
  "Ingest a remote image by `url` — Cloudflare fetches it directly, so the bytes
  never pass through your process. Options are the same as `upload` minus the
  file-specific ones: `:id`, `:metadata`, `:require-signed-urls`. Returns the
  same image map as `upload`."
  [client url & [{:keys [id metadata require-signed-urls]}]]
  (let [parts (cond-> [{:name "url" :content url}]
                metadata (conj {:name "metadata" :content (json/write metadata)})
                :always  (bool-part "requireSignedURLs" require-signed-urls)
                id       (conj {:name "id" :content id}))]
    (->image
     (with-retries
       #(envelope :upload-url
                  (api/send-request (:api client)
                                    {:method "POST" :path (images-path client "/v1")
                                     :multipart parts}))))))

(defn direct-upload-url
  "Create a one-time upload URL the browser can POST an image to directly,
  without your API token ever reaching the client. Your server calls this, hands
  the returned `:upload-url` to the browser, and the browser POSTs the file (form
  field `file`) to it. Options (all optional):
    :id                   custom image id for the eventual upload
    :metadata             map of arbitrary metadata
    :require-signed-urls  gate the resulting image behind signed URLs
    :expiry-seconds       how long the upload URL stays valid (Cloudflare
                          requires 120–21600s; default 1800 = 30 min)

  Returns `{:id <future-image-id> :upload-url <one-time-url>}`."
  [client & [{:keys [id metadata require-signed-urls expiry-seconds]}]]
  (let [parts (cond-> []
                :always        (bool-part "requireSignedURLs" require-signed-urls)
                metadata       (conj {:name "metadata" :content (json/write metadata)})
                expiry-seconds (conj {:name "expiry"
                                      :content (str (.plusSeconds (Instant/now) expiry-seconds))})
                id             (conj {:name "id" :content id}))
        result (envelope :direct-upload
                         (api/send-request (:api client)
                                           {:method "POST"
                                            :path (images-path client "/v2/direct_upload")
                                            :multipart parts}))
        {:strs [id uploadURL]} result]
    {:id id :upload-url uploadURL}))

;; ---------------------------------------------------------------------------
;; Reads / management
;; ---------------------------------------------------------------------------

(defn get
  "Fetch an image's details by `id`, or `nil` if no such image exists. Returns
  the same map shape as `upload`."
  [client id]
  (let [resp (api/send-request (:api client)
                               {:method "GET" :path (images-path client (str "/v1/" id))})]
    (when-not (= 404 (:status resp))
      (->image (envelope :get resp)))))

(defn exists?
  "Whether an image with `id` exists."
  [client id]
  (some? (get client id)))

(defn list
  "List one page of images (newest first). Uses the v2 cursor-paginated
  endpoint. Options:
    :per-page            page size (Cloudflare default 1000, max 10000)
    :continuation-token  fetch the page following a previous result
    :sort-order          :asc or :desc (by upload time)

  Returns `{:images [image-map …] :continuation-token <token-or-nil>}`. When the
  token is non-nil there may be more pages — pass it back (or use `list-seq`)."
  [client & [{:keys [per-page continuation-token sort-order]}]]
  (let [query  (cond-> {}
                 per-page           (assoc "per_page" per-page)
                 continuation-token (assoc "continuation_token" continuation-token)
                 sort-order         (assoc "sort_order" (name sort-order)))
        result (envelope :list
                         (api/send-request (:api client)
                                           {:method "GET" :path (images-path client "/v2")
                                            :query query}))
        {:strs [images continuation_token]} result]
    {:images             (mapv ->image images)
     :continuation-token continuation_token}))

(defn list-seq
  "Lazily enumerate every image matching `opts` (same options as `list`, minus
  `:continuation-token`), transparently following pagination. Each element is an
  image map. Pages are fetched as the seq is consumed, so realizing it performs
  I/O and may throw."
  [client & [opts]]
  (letfn [(pages [token]
            (let [{:keys [images continuation-token]}
                  (list client (assoc opts :continuation-token token))]
              (concat images
                      (when-not (str/blank? continuation-token)
                        (lazy-seq (pages continuation-token))))))]
    (lazy-seq (pages nil))))

(defn update
  "Update an image's mutable properties. `changes` may contain:
    :metadata             replace the image's metadata map
    :require-signed-urls  toggle whether serving needs a signed URL

  Returns the updated image map (same shape as `upload`)."
  [client id changes]
  (let [{:keys [metadata require-signed-urls]} changes
        body (cond-> {}
               (contains? changes :metadata)            (assoc "metadata" metadata)
               (contains? changes :require-signed-urls) (assoc "requireSignedURLs"
                                                               (boolean require-signed-urls)))]
    (->image
     (envelope :update
               (api/send-request (:api client)
                                 {:method "PATCH" :path (images-path client (str "/v1/" id))
                                  :json body})))))

(defn delete
  "Delete the image `id`. Idempotent — succeeds whether or not it existed.
  Returns `true`."
  [client id]
  (let [resp (api/send-request (:api client)
                               {:method "DELETE" :path (images-path client (str "/v1/" id))})]
    (if (or (< (:status resp) 300) (= 404 (:status resp)))
      true
      (throw (api-error :delete resp (json/read (:body resp)))))))

(defn- blob-response
  "GET an image blob via `send`, returning the response on success, nil on 404,
  or throwing (parsing the byte[] body as a JSON error) otherwise."
  [send client id op]
  (let [resp (send (:api client)
                   {:method "GET" :path (images-path client (str "/v1/" id "/blob"))})]
    (cond
      (< (:status resp) 300) resp
      (= 404 (:status resp)) (do (when (instance? InputStream (:body resp))
                                   (.close ^InputStream (:body resp)))
                                 nil)
      :else (let [b (:body resp)
                  s (if (bytes? b) (String. ^bytes b StandardCharsets/UTF_8) (slurp b))]
              (throw (api-error op resp (json/read s)))))))

(defn get-bytes
  "Fetch an image's original bytes as a `byte[]`, or `nil` if it doesn't exist.
  Buffers the whole image in memory — use `get-stream` for large originals."
  [client id]
  (some-> (blob-response api/send-bytes client id :get-bytes) :body))

(defn get-stream
  "Fetch an image's original bytes as an `InputStream`, or `nil` if it doesn't
  exist. The caller must close the stream (e.g. with `with-open`)."
  [client id]
  (some-> (blob-response api/send-streaming client id :get-stream) :body))

(defn stats
  "Current Images usage for the account: `{:current n :allowed n}` (image count
  against the plan's allowance)."
  [client]
  (let [{:strs [count]} (envelope :stats
                                  (api/send-request (:api client)
                                                    {:method "GET"
                                                     :path (images-path client "/v1/stats")}))
        {:strs [current allowed]} count]
    {:current current :allowed allowed}))

(defn signing-keys
  "The account's URL-signing keys, as `[{:name … :value …} …]`. The `:value` of
  a key is what you pass as `:signing-key` when building a signed `delivery-url`
  (or when configuring the client)."
  [client]
  (let [{:strs [keys]} (envelope :signing-keys
                                 (api/send-request (:api client)
                                                   {:method "GET"
                                                    :path (images-path client "/v1/keys")}))]
    (mapv (fn [k] {:name (clojure.core/get k "name")
                   :value (clojure.core/get k "value")})
          keys)))

(defn request
  "Escape hatch to the raw Cloudflare v4 API for anything this namespace doesn't
  wrap. `req` is `{:method :path :query :headers :json :multipart :body}`; the
  `:path` is taken relative to the account's Images root (e.g. `\"/v1/keys\"`).
  Returns the raw response `{:status :headers :body}` (body a string) without
  throwing on 4xx/5xx, so the caller controls error handling."
  [client req]
  (api/send-request (:api client) (clojure.core/update req :path #(images-path client %))))

;; ---------------------------------------------------------------------------
;; Delivery URLs (pure — no network)
;; ---------------------------------------------------------------------------

(defn- variant->str
  "Render the variant segment of a delivery URL. A string/keyword is a named
  variant (e.g. \"public\"); a map is a flexible-variant transform, rendered as
  comma-joined `key=value` pairs (e.g. {:w 400 :fit \"cover\"} -> \"fit=cover,w=400\")."
  [variant]
  (cond
    (string? variant)  variant
    (keyword? variant) (name variant)
    (map? variant)     (->> variant
                            (map (fn [[k v]] (str (name k) "=" v)))
                            sort
                            (str/join ","))
    :else              (str variant)))

(defn- hmac-sha256-hex [key ^String msg]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String key StandardCharsets/UTF_8) "HmacSHA256"))
    (let [raw (.doFinal mac (.getBytes msg StandardCharsets/UTF_8))
          sb  (StringBuilder. (* 2 (alength raw)))]
      (doseq [b raw] (.append sb (format "%02x" (bit-and b 0xff))))
      (.toString sb))))

(defn delivery-url
  "Build a delivery URL that serves image `id` through `variant`. `variant` is a
  named variant (string/keyword like \"public\") or, if flexible variants are
  enabled on your account, a transform map (e.g. `{:w 400 :h 300 :fit \"cover\"}`).

  Needs `:account-hash` on the client. Pure — builds a string, no network call.

  Options:
    :signed       when true, append an HMAC signature so the URL serves a
                  private (requireSignedURLs) image. Needs a signing key.
    :expires      signature lifetime in seconds (default 3600)
    :signing-key  the key to sign with (defaults to the client's :signing-key)

  Returns the URL string, e.g.
  `https://imagedelivery.net/<hash>/<id>/public` or, signed,
  `…/public?exp=<unix-ts>&sig=<hex>`."
  [client id variant & [{:keys [signed expires signing-key]}]]
  (let [hash (:account-hash client)]
    (when (str/blank? hash)
      (throw (ex-info "delivery-url needs :account-hash on the client" {})))
    (let [path (str "/" hash "/" id "/" (variant->str variant))
          base (str delivery-base path)]
      (if-not signed
        base
        (let [key (or signing-key (:signing-key client))]
          (when (str/blank? key)
            (throw (ex-info "signed delivery-url needs a :signing-key (on the client or in opts)" {})))
          (let [exp     (+ (.getEpochSecond (Instant/now)) (or expires default-sign-expiry))
                to-sign (str path "?exp=" exp)]
            (str base "?exp=" exp "&sig=" (hmac-sha256-hex key to-sign))))))))
