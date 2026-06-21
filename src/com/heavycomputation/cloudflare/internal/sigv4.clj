(ns com.heavycomputation.cloudflare.internal.sigv4
  "AWS Signature Version 4 signing, pared down to what R2 needs.

  Header-based authorization (not presigned query strings), SHA-256 payload
  hashing, path-style URLs. No chunked/streaming signature. The crypto is all
  JDK-native (`java.security`, `javax.crypto`), so this carries no dependency.

  `sign` is pure — timestamps are passed in rather than read from the clock —
  which lets it be checked against AWS's published test vectors."
  (:require [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private algorithm "AWS4-HMAC-SHA256")

;; Presigned URLs don't sign the payload — the body isn't known when the URL is
;; minted — so the canonical request uses this sentinel as the payload hash.
(def ^:private unsigned-payload "UNSIGNED-PAYLOAD")

(defn- utf8 ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- hex ^String [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (dotimes [i (alength bs)]
      (let [b (bit-and (aget bs i) 0xff)]
        (when (< b 0x10) (.append sb \0))
        (.append sb (Integer/toHexString b))))
    (.toString sb)))

(defn- sha256 ^bytes [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn- hmac-sha256 ^bytes [^bytes k ^bytes data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. k "HmacSHA256"))
    (.doFinal mac data)))

(defn sha256-hex
  "Lowercase hex SHA-256 of `data` (a byte[]) — i.e. an `x-amz-content-sha256`
  value."
  ^String [^bytes data]
  (hex (sha256 data)))

(defn uri-encode
  "Percent-encode `s` per the AWS canonicalization rules: every byte outside the
  unreserved set is `%`-escaped with uppercase hex. With `encode-slash?` false,
  `/` is left intact (used for the request path); query components encode it."
  ^String [^String s encode-slash?]
  (let [sb (StringBuilder.)]
    (doseq [byte (utf8 s)]
      (let [b (bit-and (int byte) 0xff)]
        (cond
          (or (<= 0x41 b 0x5A) (<= 0x61 b 0x7A) (<= 0x30 b 0x39)
              (== b 0x2D) (== b 0x5F) (== b 0x2E) (== b 0x7E))
          (.append sb (char b))
          (and (== b 0x2F) (not encode-slash?))
          (.append sb \/)
          :else
          (.append sb (format "%%%02X" b)))))
    (.toString sb)))

(defn canonical-query-string
  "Canonical (sorted, encoded) query string for `query` (a map). Empty for an
  empty map. Matches the order used when building the real request URL."
  ^String [query]
  (->> query
       (map (fn [[k v]] [(uri-encode (name k) true) (uri-encode (str v) true)]))
       (sort-by first)
       (map (fn [[k v]] (str k "=" v)))
       (str/join "&")))

(defn- canonical-path ^String [path]
  (->> (str/split path #"/" -1)
       (map #(uri-encode % true))
       (str/join "/")))

(defn- signing-key
  "Derive the SigV4 signing key: HMAC chained over date, region, service, and
  the \"aws4_request\" terminator, starting from \"AWS4\"+secret."
  ^bytes [secret-access-key date region service]
  (-> (utf8 (str "AWS4" secret-access-key))
      (hmac-sha256 (utf8 date))
      (hmac-sha256 (utf8 region))
      (hmac-sha256 (utf8 service))
      (hmac-sha256 (utf8 "aws4_request"))))

(defn- credential-scope [date region service]
  (str/join "/" [date region service "aws4_request"]))

(defn sign
  "Compute SigV4 and return the headers to send. The result is `headers` plus
  `x-amz-date`, `x-amz-content-sha256`, and the `Authorization` header.

  Required keys:
    :method                HTTP method, e.g. \"PUT\"
    :host                  request host (signed; the http client sends it itself)
    :path                  path-style path, unencoded, e.g. \"/bucket/key\"
    :query                 map of query params (may be empty)
    :headers               extra request headers to sign (string->string)
    :payload-hash          hex SHA-256 of the body (see `sha256-hex`)
    :region :service       SigV4 scope (service is \"s3\" for R2)
    :access-key-id :secret-access-key
    :amz-date :date        timestamps (\"yyyyMMdd'T'HHmmss'Z'\" and \"yyyyMMdd\")

  `host`, `x-amz-date`, and `x-amz-content-sha256` are always signed; every
  header in `:headers` is signed too, so callers needn't worry about which
  `x-amz-*` headers S3 requires in the signature."
  [{:keys [method host path query headers payload-hash region service
           access-key-id secret-access-key amz-date date]}]
  (let [canon (->> (assoc headers
                          "host" host
                          "x-amz-date" amz-date
                          "x-amz-content-sha256" payload-hash)
                   (map (fn [[k v]] [(str/lower-case k) (str/trim (str v))]))
                   (sort-by first))
        signed-headers    (str/join ";" (map first canon))
        canonical-headers (str/join (map (fn [[k v]] (str k ":" v "\n")) canon))
        canonical-request (str/join "\n" [method
                                          (canonical-path path)
                                          (canonical-query-string query)
                                          canonical-headers
                                          signed-headers
                                          payload-hash])
        scope          (credential-scope date region service)
        string-to-sign (str/join "\n" [algorithm amz-date scope
                                       (sha256-hex (utf8 canonical-request))])
        signature      (hex (hmac-sha256 (signing-key secret-access-key date region service)
                                         (utf8 string-to-sign)))]
    (assoc headers
           "x-amz-date" amz-date
           "x-amz-content-sha256" payload-hash
           "Authorization" (str algorithm
                                " Credential=" access-key-id "/" scope
                                ", SignedHeaders=" signed-headers
                                ", Signature=" signature))))

(defn presigned-url
  "Build a presigned URL using SigV4 query-string authentication. The auth
  parameters travel in the query string and only the `host` header is signed,
  so the resulting URL works with a bare GET (download) or PUT (upload) and no
  extra headers. `:expires` is the validity window in seconds.

  Required keys: :method :endpoint :host :path :query :expires :region :service
  :access-key-id :secret-access-key :amz-date :date (see `sign`). Returns the
  full URL string."
  [{:keys [method endpoint host path query expires region service
           access-key-id secret-access-key amz-date date]}]
  (let [scope (credential-scope date region service)
        q     (assoc query
                     "X-Amz-Algorithm"     algorithm
                     "X-Amz-Credential"    (str access-key-id "/" scope)
                     "X-Amz-Date"          amz-date
                     "X-Amz-Expires"       (str expires)
                     "X-Amz-SignedHeaders" "host")
        canonical-query   (canonical-query-string q)
        canonical-request (str/join "\n" [method
                                          (canonical-path path)
                                          canonical-query
                                          (str "host:" host "\n")
                                          "host"
                                          unsigned-payload])
        string-to-sign    (str/join "\n" [algorithm amz-date scope
                                          (sha256-hex (utf8 canonical-request))])
        signature         (hex (hmac-sha256 (signing-key secret-access-key date region service)
                                            (utf8 string-to-sign)))]
    (str endpoint (canonical-path path) "?" canonical-query
         "&X-Amz-Signature=" signature)))
