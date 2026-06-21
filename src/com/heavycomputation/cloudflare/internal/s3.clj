(ns com.heavycomputation.cloudflare.internal.s3
  "A tiny, zero-dependency S3 client: signs each request with SigV4 and sends it
  over the JDK's `java.net.http` client. It is path-style and stateless — just
  the transport R2 needs, with no per-operation knowledge baked in."
  (:require [clojure.string :as str]
            [com.heavycomputation.cloudflare.internal.sigv4 :as sigv4])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
                          HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(defn default-http-client ^HttpClient []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- authority
  "Host (plus port if non-default) for the `Host` header, matching what
  java.net.http sends automatically and what SigV4 must sign."
  [^URI uri]
  (let [host (.getHost uri)
        port (.getPort uri)]
    (if (pos? port) (str host ":" port) host)))

(defn client
  "Build an S3 client handle. `:endpoint` is the scheme+host base URL (no
  trailing slash); `:region`/`:access-key-id`/`:secret-access-key` configure
  SigV4. An `:http-client` may be supplied to share a JDK client; otherwise one
  is created."
  [{:keys [endpoint region access-key-id secret-access-key http-client]}]
  (let [uri (URI/create endpoint)]
    {:endpoint          endpoint
     :host              (authority uri)
     :region            region
     :service           "s3"
     :access-key-id     access-key-id
     :secret-access-key secret-access-key
     :http              (or http-client (default-http-client))}))

(def ^:private amz-date-fmt (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'"))
(def ^:private date-fmt     (DateTimeFormatter/ofPattern "yyyyMMdd"))

(defn- now []
  (let [t (ZonedDateTime/now ZoneOffset/UTC)]
    {:amz-date (.format t amz-date-fmt)
     :date     (.format t date-fmt)}))

(defn- encode-path [path]
  (->> (str/split path #"/" -1)
       (map #(sigv4/uri-encode % true))
       (str/join "/")))

(defn- query-suffix [query]
  (let [q (sigv4/canonical-query-string query)]
    (if (str/blank? q) "" (str "?" q))))

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
  [{:keys [endpoint host region service access-key-id secret-access-key]}
   {:keys [method path query headers body]}]
  (let [^bytes payload (or body (byte-array 0))
        signed   (sigv4/sign (merge (now)
                                    {:method            method
                                     :host              host
                                     :path              path
                                     :query             (or query {})
                                     :headers           (or headers {})
                                     :payload-hash      (sigv4/sha256-hex payload)
                                     :region            region
                                     :service           service
                                     :access-key-id     access-key-id
                                     :secret-access-key secret-access-key}))
        url      (str endpoint (encode-path path) (query-suffix query))
        body-pub (if (pos? (alength payload))
                   (HttpRequest$BodyPublishers/ofByteArray payload)
                   (HttpRequest$BodyPublishers/noBody))
        builder  (-> (HttpRequest/newBuilder (URI/create url))
                     (.method method body-pub))]
    (doseq [[k v] signed] (.header builder k v))
    (.build builder)))

(defn- exchange [{:keys [^HttpClient http] :as client} req body-handler]
  (let [resp (.send http (build-request client req) body-handler)]
    {:status  (.statusCode resp)
     :headers (response-headers resp)
     :body    (.body resp)}))

(defn send-request
  "Sign and send one request. `req`:
    :method  \"GET\" | \"PUT\" | \"POST\" | \"DELETE\" | \"HEAD\"
    :path    path-style path, e.g. \"/bucket/key\"
    :query   map of query params (optional)
    :headers map of extra headers to sign+send (optional)
    :body    byte[] payload (optional; defaults to empty)

  Returns `{:status int :headers {lower-name [vals]} :body byte[]}` for any
  completed exchange (including 4xx/5xx). Network failures throw IOException."
  [client req]
  (exchange client req (HttpResponse$BodyHandlers/ofByteArray)))

(defn send-streaming
  "Like `send-request`, but `:body` in the result is an InputStream rather than
  a buffered byte[] — for downloading large objects without holding them in
  memory. The caller must close (or fully consume) `:body`."
  [client req]
  (exchange client req (HttpResponse$BodyHandlers/ofInputStream)))

(defn presign
  "Build a presigned URL (SigV4 query-string auth) for `req`
  (`{:method :path :query :expires}`). `:method` is the HTTP verb the URL will
  be used with; `:expires` is the validity window in seconds."
  [{:keys [endpoint host region service access-key-id secret-access-key]}
   {:keys [method path query expires]}]
  (sigv4/presigned-url (merge (now)
                              {:method            method
                               :endpoint          endpoint
                               :host              host
                               :path              path
                               :query             (or query {})
                               :expires           expires
                               :region            region
                               :service           service
                               :access-key-id     access-key-id
                               :secret-access-key secret-access-key})))
