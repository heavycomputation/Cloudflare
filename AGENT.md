# AGENT.md

## Project

`com.heavycomputation/cloudflare` is a Clojure library by Heavy Computation that
makes common Cloudflare products painless to use, so the same R2/Images glue
doesn't get copy-pasted into every webapp.

It's a `deps.edn` library (tools.build for packaging, cognitect `test-runner`
for tests). **Zero runtime dependencies beyond Clojure.** Two products, two
transports, both hand-rolled over the JDK's `java.net.http` client:

- **R2** is S3-compatible, so we sign requests with AWS Signature V4 against
  R2's custom endpoint (`https://<account-id>.r2.cloudflarestorage.com`). No
  AWS SDK.
- **Images** uses Cloudflare's own REST API v4 (Bearer-token auth, JSON
  envelopes, multipart uploads), and serves images from `imagedelivery.net`.
  No official Cloudflare SDK.

**Design principle:** layer a small convenience API on top of a thin signed-HTTP
S3 transport. Make easy things easy (`put`/`get`/`delete`/`presigned-url`), but
always leave an escape hatch (`request` — raw signed HTTP) for full S3 surface
area (multipart, range requests, conditional ops, metadata, tagging, pagination).

Source lives under `src/com/heavycomputation/cloudflare/`. Namespaces:
- `com.heavycomputation.cloudflare`          — public entry point (re-exports R2)
- `com.heavycomputation.cloudflare.r2`       — R2 object storage
- `com.heavycomputation.cloudflare.images`   — Cloudflare Images
- `com.heavycomputation.cloudflare.internal.sigv4` — AWS SigV4 signing (R2)
- `com.heavycomputation.cloudflare.internal.s3`    — signed-HTTP S3 transport (R2)
- `com.heavycomputation.cloudflare.internal.xml`   — minimal S3 XML read/write (R2)
- `com.heavycomputation.cloudflare.internal.api`   — Cloudflare REST v4 transport (Images)
- `com.heavycomputation.cloudflare.internal.json`  — minimal JSON read/write (Images)

Verify changes with `clojure -X:test`.

---

## Roadmap

- [x] **R2: `put` (with automatic multipart)**
- [x] R2: client/bucket construction (`bucket`, raw `request`)
- [x] Zero-dependency S3 transport (SigV4 + `java.net.http`)
- [x] R2: `get` / `get-bytes` / `get-stream`
- [x] R2: `delete` / `delete-many`, `list` / `list-seq`, `head` / `exists?`
- [x] R2: `presigned-url` (download + upload)
- [x] R2: browser-direct multipart (`create-multipart-upload`,
  `presigned-upload-part`, `complete-multipart-upload`, `abort-multipart-upload`)
- [x] **R2 feature-complete — published** to Clojars as
  `com.heavycomputation/cloudflare` (latest `0.1.2`)
- [x] **Images: upload/manage/serve** — `upload` (File/byte[]/InputStream),
  `upload-url` (remote ingest), `direct-upload-url` (browser-direct), `get`,
  `list`/`list-seq`, `update`, `delete`, `exists?`, `get-bytes`/`get-stream`,
  `stats`, `signing-keys`, and `delivery-url` (named + flexible variants, with
  optional HMAC signing for private images). Zero-dependency REST v4 transport
  (`internal.api`) + minimal JSON (`internal.json`). *Unreleased — on `master`
  for the `0.1.3` cycle.*

---

## Feature 1 — R2 `put` with automatic multipart

> **Shipped (zero-dependency).** This was the original design spec; the notes
> below have been reconciled to the implementation. There is no AWS SDK: every
> request is signed with SigV4 (`internal.sigv4`) and sent over the JDK's
> `java.net.http` client (`internal.s3`). The only runtime dependency is Clojure.

### Goal

A single `put` that reliably uploads any object to an R2 bucket, including a
large file (primary use case: a ~5GB `.zip` archive). The caller should not have
to think about size — `put` picks the right strategy.

```clojure
(require '[com.heavycomputation.cloudflare.r2 :as r2]
         '[clojure.java.io :as io])

(def bucket
  (r2/bucket {:account-id        "abc123"
              :access-key-id     "..."
              :secret-access-key "..."
              :bucket            "uploads"}))

(r2/put bucket "archives/backup.zip" (io/file "backup.zip"))
;; => {:key "archives/backup.zip" :bucket "uploads" :etag "..." :bytes 4831293021}
```

### Why multipart matters

R2/S3 caps a single `PutObject` at **5 GiB**, and a one-shot PUT of a multi-GB
file is fragile — one network blip restarts the whole transfer. Above a
threshold, `put` must split the file into parts and upload them, so a failed
part retries on its own instead of the whole file.

### Requirements

1. **Client/bucket construction** (needed first):
   - `bucket` takes `{:account-id :access-key-id :secret-access-key :bucket
     :region?}` and returns a handle holding a signed-HTTP S3 client
     (`internal.s3`) + bucket name.
   - R2 endpoint: `https://<account-id>.r2.cloudflarestorage.com`. Region is
     `auto` for R2. The client signs each request with SigV4 against this
     endpoint using the static credentials — no SDK, no endpoint resolver.

2. **`(put bucket key body & [opts])`**:
   - `body` accepts at least: `java.io.File`, `InputStream`, and `byte[]`.
   - Infer `Content-Type` from the key's extension (e.g. `.zip` →
     `application/zip`), overridable via `opts`.
   - **Small objects** (below `:multipart-threshold`, default ~100 MB):
     a single `PutObject` (signed `PUT`).
   - **Large objects** (at/above threshold, or unknown-length streams):
     multipart — `CreateMultipartUpload` → upload parts (default part size
     ~100 MB) → `CompleteMultipartUpload`. On any failure, call
     `AbortMultipartUpload` so no orphaned parts are billed.
   - Upload parts **concurrently** with bounded parallelism
     (`:concurrency`, default ~4) and **retry** individual parts on transient
     failure.
   - Return a small result map: `{:key :bucket :etag :bytes}`.

3. **Options** (all optional, sensible defaults):
   `:content-type`, `:multipart-threshold`, `:part-size`, `:concurrency`,
   `:metadata` (custom `x-amz-meta-*`), `:cache-control`.

4. **Errors:** the signed-HTTP transport returns every completed exchange as
   data — including 4xx/5xx — rather than throwing. `put` inspects the status and
   on a non-2xx raises an `ex-info` carrying the parsed S3 `Code`/`Message`
   (transient 5xx/429 and network errors are retried with backoff first). Any
   in-flight multipart upload is aborted before the error propagates.

### Notes / gotchas

- A `File` has a known length (cheap multipart decision); a generic
  `InputStream` does not — buffer per-part and stream until exhausted.
- The multipart lifecycle maps to the S3 operations `CreateMultipartUpload`,
  `UploadPart`, `CompleteMultipartUpload`, and `AbortMultipartUpload`, each
  issued as a SigV4-signed HTTP request (no SDK op layer).
- Each `UploadPart` response's `ETag` must be collected with its part number and
  passed (sorted) to `CompleteMultipartUpload`.
- Part numbers are 1-based; minimum part size is 5 MiB (last part exempt).

### Done when

- `put` uploads a small file via single PUT and a large file via multipart.
- A simulated mid-upload failure aborts the multipart upload (no orphan parts).
- `clojure -X:test` passes, with tests covering content-type inference and the
  single-vs-multipart threshold decision (mock/stub the S3 client — no live R2
  needed in CI).

---

## Feature 2 — Images upload / manage / serve

> **Shipped (zero-dependency).** Reconciled to the implementation.

### Goal

Make Cloudflare Images painless: upload an image (from a File/byte[]/stream, a
remote URL, or a browser-direct one-time URL), manage it (get/list/update/
delete), and build the URL that serves it — including a signed URL for private
images. No official Cloudflare SDK; the REST v4 transport and JSON are
hand-rolled, keeping the zero-dependency promise.

```clojure
(require '[com.heavycomputation.cloudflare.images :as images]
         '[clojure.java.io :as io])

(def c (images/client {:account-id   "abc123"
                       :api-token    "..."
                       :account-hash "aBcDeF"      ; delivery URLs
                       :signing-key  "..."}))       ; optional, private images

(images/upload c (io/file "cat.jpg") {:metadata {:owner "u42"}})
;; => {:id "..." :filename "cat.jpg" :uploaded #inst"..."
;;     :require-signed-urls false :meta {...} :variants ["https://imagedelivery.net/.../public"]}

(images/delivery-url c "cats/fluffy" "public")            ; named variant
(images/delivery-url c "cats/fluffy" {:w 400 :fit "cover"}) ; flexible variant
(images/delivery-url c "cats/fluffy" "public" {:signed true :expires 3600})
```

### How it differs from R2 (the key mental model)

- **Not S3, not SigV4.** Images speaks Cloudflare's REST API v4 at
  `https://api.cloudflare.com/client/v4/accounts/<account-id>/images/…`, with
  `Authorization: Bearer <api-token>`. Responses are the standard
  `{success, errors, messages, result}` JSON envelope. Uploads are
  `multipart/form-data` (fields `file`|`url`, `metadata`, `requireSignedURLs`,
  `id`). All of this lives in `internal.api` + `internal.json`.
- **Two IDs.** The **account hash** (for delivery URLs) is a *different* value
  from the **account id** (for the API). Both are needed; `delivery-url` uses
  the hash, everything else uses the id.
- **Serving is a separate domain.** Images are served from
  `https://imagedelivery.net/<account-hash>/<image-id>/<variant>`. `delivery-url`
  builds this as a pure string (no network).

### Notes / gotchas

- **Signed delivery URLs** are *not* SigV4. Compute
  `HMAC-SHA256(key, "<pathname>?exp=<unix-ts>")` where pathname is
  `/<hash>/<id>/<variant>`, hex-encode it, and append `&sig=<hex>`. The key is
  an Images URL-signing key (`GET /v1/keys`, exposed as `signing-keys`).
- **Variants** are either named (configured in the dashboard, e.g. `public`) or
  *flexible* (enabled account-wide), which put transform params inline where the
  variant name goes — `delivery-url` renders a map like `{:w 400 :fit "cover"}`
  into `fit=cover,w=400` (sorted for stable output).
- **Listing uses v2** (`GET /v2`), which is cursor-paginated
  (`continuation_token`), not the page/per_page v1 endpoint. `list-seq` follows
  the cursor until it's blank.
- **Direct-upload expiry** is sent as an absolute RFC-3339 timestamp
  (`now + :expiry-seconds`), and Cloudflare requires it be 2 min–6 h out.
- **Errors:** the transport returns every exchange as data; the `images` layer
  raises `ex-info` (carrying the envelope's `code`/`message`) on any non-2xx or
  `success:false`. Transient 5xx/429 are retried with backoff first. `get`/
  `get-bytes` return `nil` on 404; `delete` treats 404 as success (idempotent).

### Done when

- `upload` / `upload-url` / `direct-upload-url` cover the three upload paths;
  `get`/`list`/`update`/`delete` round-trip an image's lifecycle.
- `delivery-url` builds named, flexible, and signed URLs (signing verified
  against a known HMAC vector).
- `clojure -X:test` passes, stubbing the `internal.api` seam — no live
  Cloudflare needed in CI.
