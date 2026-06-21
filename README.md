# cloudflare

[![Clojars Project](https://img.shields.io/clojars/v/com.heavycomputation/cloudflare.svg)](https://clojars.org/com.heavycomputation/cloudflare)

A batteries-included Clojure library by [Heavy Computation](https://heavycomputation.com)
that makes working with Cloudflare products painless — so you stop copy-pasting
the same R2 and Images glue into every new webapp.

**Zero dependencies** beyond Clojure itself: R2 is reached by signing requests
with AWS Signature V4 and sending them over the JDK's built-in HTTP client, so
adding this library pulls in a ~13 KB jar rather than a transitive AWS SDK.

## Status

Early and incremental. Features land one at a time:

- [x] **R2** — object storage (S3-compatible). Upload, download, delete, list,
  and presigned URLs are all in. See below.
- [ ] **Images** — upload, variants, and delivery

## Usage

```clojure
(require '[com.heavycomputation.cloudflare.r2 :as r2]
         '[clojure.java.io :as io])

(def bucket
  (r2/bucket {:account-id        "abc123"
              :access-key-id     "..."
              :secret-access-key "..."
              :bucket            "uploads"}))

;; Upload anything — small objects go up in a single PUT, large ones are split
;; into concurrent, individually-retried multipart chunks automatically.
(r2/put bucket "archives/backup.zip" (io/file "backup.zip"))
;; => {:key "archives/backup.zip" :bucket "uploads" :etag "..." :bytes 4831293021}

;; Download: bytes for small objects, a stream for large ones, or just metadata.
(r2/get-bytes bucket "notes.txt")           ; => byte[] (or nil if absent)
(with-open [in (:body (r2/get-stream bucket "archives/backup.zip"))]
  (io/copy in (io/file "restored.zip")))
(r2/head bucket "notes.txt")                ; => {:content-type :bytes :etag ...} or nil
(r2/exists? bucket "notes.txt")             ; => true/false

;; List by prefix (one page), or lazily across all pages.
(r2/list bucket {:prefix "archives/" :delimiter "/"})
;; => {:objects [{:key :size :etag :last-modified} ...] :common-prefixes [...]
;;     :truncated? false :continuation-token nil}
(count (r2/list-seq bucket {:prefix "archives/"}))

;; Delete one, or many (batched, >1000 keys split automatically).
(r2/delete bucket "notes.txt")
(r2/delete-many bucket (map :key (r2/list-seq bucket {:prefix "tmp/"})))

;; Presigned URLs — hand these to a browser so bytes never touch your server.
(r2/presigned-url bucket "archives/backup.zip")                    ; download, 15 min
(r2/presigned-url bucket "uploads/new.zip" {:method :put           ; direct upload
                                            :expires 3600})

;; Anything not covered? Drop to the raw signed-S3 surface — the bucket is
;; prefixed for you; you get back {:status :headers :body}.
(r2/request bucket {:method "GET" :key "notes.txt"
                    :headers {"Range" "bytes=0-1023"}})
```

## Installation

deps.edn:

```clojure
com.heavycomputation/cloudflare {:mvn/version "RELEASE"}
```

## Development

```bash
clojure -M:dev        # REPL
clojure -X:test       # run tests
clojure -T:build jar  # build a jar
```

## License

Copyright © 2026 Heavy Computation. Distributed under the MIT License.
