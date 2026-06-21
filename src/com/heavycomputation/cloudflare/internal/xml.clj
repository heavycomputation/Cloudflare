(ns com.heavycomputation.cloudflare.internal.xml
  "Just enough XML to talk to S3: pull a tag's text out of a small response
  document and build the CompleteMultipartUpload request body.

  This is deliberately *not* a general XML library. The documents S3 returns
  for the handful of operations we use are small, flat, and machine-generated,
  so a tag scan is enough and avoids a parser dependency."
  (:require [clojure.string :as str]))

(defn- unescape [s]
  (-> s
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace "&lt;"   "<")
      (str/replace "&gt;"   ">")
      (str/replace "&amp;"  "&")))

(defn- escape [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn tag-text
  "Text content of the first `<tag>…</tag>` in `xml`, unescaped, or nil if the
  tag is absent."
  [xml tag]
  (some-> (re-find (re-pattern (str "(?s)<" tag "[^>]*>(.*?)</" tag ">")) xml)
          second
          str/trim
          unescape))

(defn tag-blocks
  "Raw inner XML of every `<tag>…</tag>` occurrence, in document order. Used to
  scope a nested parse — e.g. pull `<Key>` out of each `<Contents>` block in a
  listing — when the same child tag appears under several parents."
  [xml tag]
  (map second (re-seq (re-pattern (str "(?s)<" tag "[^>]*>(.*?)</" tag ">")) xml)))

(defn complete-multipart-upload
  "Render a CompleteMultipartUpload request body from `parts`, a sequence of
  `{:PartNumber n :ETag \"…\"}` already sorted by part number. ETags are passed
  through verbatim (S3 expects the quoted value UploadPart returned)."
  [parts]
  (str "<CompleteMultipartUpload>"
       (str/join (for [{:keys [PartNumber ETag]} parts]
                   (str "<Part>"
                        "<PartNumber>" PartNumber "</PartNumber>"
                        "<ETag>" (escape ETag) "</ETag>"
                        "</Part>")))
       "</CompleteMultipartUpload>"))

(defn delete-objects
  "Render a DeleteObjects request body listing object `keys`."
  [keys]
  (str "<Delete>"
       (str/join (for [k keys]
                   (str "<Object><Key>" (escape k) "</Key></Object>")))
       "</Delete>"))
