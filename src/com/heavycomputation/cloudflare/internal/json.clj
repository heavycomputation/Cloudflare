(ns com.heavycomputation.cloudflare.internal.json
  "Just enough JSON to talk to the Cloudflare REST API v4: read the small
  `{success, errors, messages, result}` envelopes it returns and write the
  compact request bodies (and `x-amz`-free metadata blobs) we send.

  This is deliberately *not* a general JSON library — but it is a correct one
  for the subset it covers: objects, arrays, strings (with `\\uXXXX` and the
  usual escapes), numbers (long or double), `true`/`false`/`null`. Keeping it
  in-house preserves the library's zero-dependency promise, the same way
  `internal.xml` does for S3.

  `read` returns objects as maps with *string* keys (Cloudflare's are camelCase,
  so callers destructure with `:strs`); `write` accepts maps with string or
  keyword keys."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Reader
;; ---------------------------------------------------------------------------
;;
;; A small recursive-descent parser. Each `parse-*` takes the source string and
;; an index and returns `[value next-index]`, threading the cursor by hand — no
;; mutable state, and fast enough for the envelope-sized documents we parse.

(declare parse-value)

(defn- parse-error [msg i]
  (throw (ex-info (str "Invalid JSON: " msg) {:index i})))

(defn- skip-ws ^long [^String s ^long i]
  (let [n (.length s)]
    (loop [i i]
      (if (and (< i n) (Character/isWhitespace (.charAt s i)))
        (recur (inc i))
        i))))

(defn- parse-string
  "Parse a JSON string starting at the opening quote at `i`."
  [^String s ^long i]
  (let [n  (.length s)
        sb (StringBuilder.)]
    (loop [i (inc i)]
      (when (>= i n) (parse-error "unterminated string" i))
      (let [c (.charAt s i)]
        (cond
          (= c \")  [(.toString sb) (inc i)]
          (= c \\)  (let [d (.charAt s (inc i))]
                      (case d
                        \" (do (.append sb \")         (recur (+ i 2)))
                        \\ (do (.append sb \\)         (recur (+ i 2)))
                        \/ (do (.append sb \/)         (recur (+ i 2)))
                        \b (do (.append sb \backspace) (recur (+ i 2)))
                        \f (do (.append sb \formfeed)  (recur (+ i 2)))
                        \n (do (.append sb \newline)   (recur (+ i 2)))
                        \r (do (.append sb \return)    (recur (+ i 2)))
                        \t (do (.append sb \tab)       (recur (+ i 2)))
                        \u (let [cp (Integer/parseInt (subs s (+ i 2) (+ i 6)) 16)]
                             (.append sb (char cp))
                             (recur (+ i 6)))
                        (parse-error (str "bad escape \\" d) i)))
          :else     (do (.append sb c) (recur (inc i))))))))

(defn- number-char? [c]
  (or (Character/isDigit ^char c)
      (contains? #{\+ \- \. \e \E} c)))

(defn- parse-number [^String s ^long i]
  (let [n (.length s)]
    (loop [j i]
      (if (and (< j n) (number-char? (.charAt s j)))
        (recur (inc j))
        (let [tok (subs s i j)]
          [(if (re-find #"[.eE]" tok)
             (Double/parseDouble tok)
             (Long/parseLong tok))
           j])))))

(defn- parse-array [^String s ^long i]
  (let [i (skip-ws s (inc i))]                      ; skip '['
    (if (= \] (.charAt s i))
      [[] (inc i)]
      (loop [i i, acc (transient [])]
        (let [[v i] (parse-value s i)
              i     (skip-ws s i)
              c     (.charAt s i)]
          (case c
            \, (recur (skip-ws s (inc i)) (conj! acc v))
            \] [(persistent! (conj! acc v)) (inc i)]
            (parse-error "expected ',' or ']' in array" i)))))))

(defn- parse-object [^String s ^long i]
  (let [i (skip-ws s (inc i))]                      ; skip '{'
    (if (= \} (.charAt s i))
      [{} (inc i)]
      (loop [i i, acc (transient {})]
        (let [i     (skip-ws s i)
              [k i] (parse-string s i)
              i     (skip-ws s i)
              _     (when-not (= \: (.charAt s i)) (parse-error "expected ':'" i))
              [v i] (parse-value s (inc i))
              i     (skip-ws s i)
              c     (.charAt s i)]
          (case c
            \, (recur (inc i) (assoc! acc k v))
            \} [(persistent! (assoc! acc k v)) (inc i)]
            (parse-error "expected ',' or '}' in object" i)))))))

(defn- lit
  "Match literal `word` (e.g. \"true\") at `i`, yielding `[value after]`."
  [^String s ^long i word value]
  (let [end (+ i (count word))]
    (if (and (<= end (.length s)) (= word (subs s i end)))
      [value end]
      (parse-error (str "expected '" word "'") i))))

(defn- parse-value [^String s ^long i]
  (let [i (skip-ws s i)]
    (when (>= i (.length s)) (parse-error "unexpected end of input" i))
    (let [c (.charAt s i)]
      (case c
        \{ (parse-object s i)
        \[ (parse-array s i)
        \" (parse-string s i)
        \t (lit s i "true" true)
        \f (lit s i "false" false)
        \n (lit s i "null" nil)
        (if (or (Character/isDigit c) (= c \-))
          (parse-number s i)
          (parse-error (str "unexpected character '" c "'") i))))))

(defn read
  "Parse JSON text `s` into Clojure data (objects as string-keyed maps). Returns
  nil for nil/blank input. Throws `ex-info` on malformed JSON."
  [s]
  (when-not (str/blank? s)
    (first (parse-value s 0))))

;; ---------------------------------------------------------------------------
;; Writer
;; ---------------------------------------------------------------------------

(defn- write-string [^StringBuilder sb ^String s]
  (.append sb \")
  (dotimes [idx (.length s)]
    (let [c (.charAt s idx)]
      (case c
        \"         (.append sb "\\\"")
        \\         (.append sb "\\\\")
        \newline   (.append sb "\\n")
        \return    (.append sb "\\r")
        \tab       (.append sb "\\t")
        \backspace (.append sb "\\b")
        \formfeed  (.append sb "\\f")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c)))))
  (.append sb \"))

(defn- write-value [^StringBuilder sb v]
  (cond
    (nil? v)        (.append sb "null")
    (boolean? v)    (.append sb (if v "true" "false"))
    (string? v)     (write-string sb v)
    (keyword? v)    (write-string sb (name v))
    (symbol? v)     (write-string sb (name v))
    (ratio? v)      (.append sb (str (double v)))
    (number? v)     (.append sb (str v))
    (map? v)        (do (.append sb \{)
                        (loop [first? true, kvs (seq v)]
                          (when-let [[k val] (first kvs)]
                            (when-not first? (.append sb \,))
                            (write-string sb (if (keyword? k) (name k) (str k)))
                            (.append sb \:)
                            (write-value sb val)
                            (recur false (next kvs))))
                        (.append sb \}))
    (sequential? v) (do (.append sb \[)
                        (loop [first? true, xs (seq v)]
                          (when (seq xs)
                            (when-not first? (.append sb \,))
                            (write-value sb (first xs))
                            (recur false (next xs))))
                        (.append sb \]))
    :else           (write-string sb (str v))))

(defn write
  "Render Clojure data `v` as a compact JSON string. Maps become objects (string
  or keyword keys), sequential collections become arrays; keywords/symbols
  stringify to their name. Anything unrecognized is written as its `str`."
  ^String [v]
  (let [sb (StringBuilder.)]
    (write-value sb v)
    (.toString sb)))
