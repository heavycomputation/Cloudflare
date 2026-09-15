# cloudflare

[![Clojars Project](https://img.shields.io/clojars/v/com.heavycomputation/cloudflare.svg)](https://clojars.org/com.heavycomputation/cloudflare)

A batteries-included Clojure library by [Heavy Computation](https://heavycomputation.com)
that makes working with Cloudflare products painless — so you stop copy-pasting
the same R2 and Images glue into every new webapp.

**Zero dependencies** beyond Clojure itself: R2 requests are signed with AWS
Signature V4, while Images uses Cloudflare's bearer-authenticated REST API.
Both transports use the JDK's built-in HTTP client, so adding this library does
not pull in an AWS or Cloudflare SDK.

## Status

Early and incremental. Features land one at a time:

- [x] **R2** — object storage (S3-compatible). Upload, download, delete, list,
  and presigned URLs are all in. See below.
- [x] **Images** — upload, remote ingestion, browser-direct uploads, management,
  downloads, and delivery URLs (including signed/private delivery)

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

### Images

```clojure
(require '[com.heavycomputation.cloudflare.images :as images]
         '[clojure.java.io :as io])

(def images-client
  (images/client {:account-id   "abc123"
                  :api-token    "..."
                  :account-hash "..."})) ; needed only for delivery-url

;; Upload a File, byte[], or InputStream. Persist :id in your application.
(def image (images/upload images-client (io/file "avatar.jpg")
                          {:metadata {:user-id 42}}))
(:id image) ; => "2cdc28f0-017a-49c4-9ed7-87056c83901"

;; Cloudflare can also fetch a remote image without proxying it through you.
(images/upload-url images-client "https://example.com/avatar.jpg")

;; Or let a browser upload directly without exposing your API token.
(images/direct-upload-url images-client)
;; => {:id "future-image-id" :upload-url "https://upload.imagedelivery.net/..."}

(images/get images-client (:id image))
(images/delivery-url images-client (:id image) "public")
(images/delete images-client (:id image))
```

The Images namespace also provides `list`, `list-seq`, `update`, `exists?`,
`get-bytes`, `get-stream`, `stats`, `signing-keys`, and a raw `request` escape
hatch. Uploads are limited by Cloudflare Images' current hosted-image limits.

## Installation

deps.edn:

```clojure
com.heavycomputation/cloudflare {:mvn/version "0.1.2"}
```

## Development

```bash
clojure -M:dev        # REPL
clojure -X:test       # run tests
clojure -X:integration # live R2 + Images tests (requires credentials)
clojure -T:build jar  # build a jar
```

## License

Copyright © 2026 Heavy Computation. Distributed under the MIT License.
