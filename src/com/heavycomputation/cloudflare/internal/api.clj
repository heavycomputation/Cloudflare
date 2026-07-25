(ns com.heavycomputation.cloudflare.internal.api
  "A tiny, zero-dependency client for the Cloudflare REST API v4: attaches a
  Bearer token and sends requests over the JDK's `java.net.http` client. It is
  the transport the Images product needs — the counterpart to `internal.s3`,
  but for Cloudflare's own JSON API rather than S3.

  It handles two request-body shapes, both hand-rolled so nothing is pulled in:
  a JSON body (`:json <clj-data>`) and a `multipart/form-data` upload
  (`:multipart [part …]`). It is stateless and carries no per-product knowledge;
  callers build paths and interpret responses."
  (:require [clojure.string :as str]
            [com.heavycomputation.cloudflare.internal.json :as json])
  (:import (java.io ByteArrayOutputStream)
           (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util UUID)))

(def default-base-url "https://api.cloudflare.com/client/v4")

(defn default-http-client ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn client
  "Build a Cloudflare API client handle. `:api-token` is the bearer token;
  `:base-url` defaults to the public v4 endpoint. An `:http-client` may be
  supplied to share a JDK client; otherwise one is created."
  [{:keys [api-token base-url http-client]
    :or   {base-url default-base-url}}]
  {:base-url  base-url
   :api-token api-token
   :http      (or http-client (default-http-client))})

;; ---------------------------------------------------------------------------
;; Request bodies
;; ---------------------------------------------------------------------------

(defn- multipart-body
  "Encode `parts` as a `multipart/form-data` body. Each part is a map:
    {:name \"file\" :content <byte[]|String> :filename \"x.jpg\" :content-type \"…\"}
  `:filename` and `:content-type` are optional (used for the file part). Returns
  `{:content-type <header value incl. boundary> :body <byte[]>}`."
  [parts]
  (let [boundary (str "----hc" (str/replace (str (UUID/randomUUID)) "-" ""))
        out      (ByteArrayOutputStream.)
        crlf     "\r\n"
        emit     (fn [^String s] (.write out (.getBytes s StandardCharsets/UTF_8)))]
    (doseq [{:keys [name content filename content-type]} parts]
      (emit (str "--" boundary crlf))
      (emit (str "Content-Disposition: form-data; name=\"" name "\""
                 (when filename (str "; filename=\"" filename "\"")) crlf))
      (when content-type (emit (str "Content-Type: " content-type crlf)))
      (emit crlf)
      (if (bytes? content)
        (.write out ^bytes content)
        (emit (str content)))
      (emit crlf))
    (emit (str "--" boundary "--" crlf))
    {:content-type (str "multipart/form-data; boundary=" boundary)
     :body         (.toByteArray out)}))

(defn- prepare
  "Resolve the high-level `:json`/`:multipart` body sugar into a concrete
  `:body` (byte[]) plus the matching Content-Type header."
  [{:keys [json multipart headers] :as req}]
  (cond
    multipart (let [{:keys [content-type body]} (multipart-body multipart)]
                (-> req
                    (dissoc :multipart)
                    (assoc :body body)
                    (assoc :headers (assoc (or headers {}) "Content-Type" content-type))))
    (contains? req :json)
    (-> req
        (dissoc :json)
        (assoc :body (.getBytes (json/write json) StandardCharsets/UTF_8))
        (assoc :headers (assoc (or headers {}) "Content-Type" "application/json")))
    :else req))

;; ---------------------------------------------------------------------------
;; Transport
;; ---------------------------------------------------------------------------

(defn- query-suffix [query]
  (if (seq query)
    (->> query
         (map (fn [[k v]]
                (str (URLEncoder/encode (str k) StandardCharsets/UTF_8)
                     "="
                     (URLEncoder/encode (str v) StandardCharsets/UTF_8))))
         (str/join "&")
         (str "?"))
    ""))

(defn- response-headers [^HttpResponse resp]
  (reduce (fn [acc [k v]] (assoc acc (str/lower-case k) (vec v)))
          {}
          (.map (.headers resp))))

(defn header
  "First value of response header `name` (case-insensitive), or nil."
  [resp name]
  (first (get (:headers resp) (str/lower-case name))))

(defn- build-request
  ^HttpRequest
  [{:keys [base-url api-token]} {:keys [method path query headers body]}]
  (let [url            (str base-url path (query-suffix query))
        ^bytes payload (or body (byte-array 0))
        pub            (if (pos? (alength payload))
                         (HttpRequest$BodyPublishers/ofByteArray payload)
                         (HttpRequest$BodyPublishers/noBody))
        builder        (-> (HttpRequest/newBuilder (URI/create url))
                           (.method method pub)
                           (.header "Authorization" (str "Bearer " api-token)))]
    (doseq [[k v] headers] (.header builder k v))
    (.build builder)))

(defn- exchange [{:keys [^HttpClient http] :as client} req body-handler]
  (let [resp (.send http (build-request client (prepare req)) body-handler)]
    {:status  (.statusCode resp)
     :headers (response-headers resp)
     :body    (.body resp)}))

(defn send-request
  "Sign-free send of one request. `req`:
    :method     \"GET\" | \"POST\" | \"PATCH\" | \"DELETE\"
    :path       path after the base URL, e.g. \"/accounts/ID/images/v1\"
    :query      map of query params (optional; URL-encoded)
    :headers    map of extra headers (optional)
    :json       Clojure data to send as a JSON body (optional)
    :multipart  vector of form parts to send as multipart/form-data (optional)
    :body       raw byte[] body (optional; ignored if :json/:multipart given)

  Returns `{:status int :headers {lower-name [vals]} :body <string>}` for any
  completed exchange (including 4xx/5xx) — the body is decoded as a UTF-8 string
  (Cloudflare answers JSON even on errors). Network failures throw IOException."
  [client req]
  (exchange client req (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))

(defn send-bytes
  "Like `send-request`, but `:body` in the result is the raw `byte[]` — for
  binary responses such as an image blob."
  [client req]
  (exchange client req (HttpResponse$BodyHandlers/ofByteArray)))

(defn send-streaming
  "Like `send-request`, but `:body` in the result is an InputStream — for
  downloading a large blob without buffering it. The caller must close (or fully
  consume) `:body`."
  [client req]
  (exchange client req (HttpResponse$BodyHandlers/ofInputStream)))
