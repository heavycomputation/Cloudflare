# AGENT.md

## Project

`com.heavycomputation/cloudflare` is a Clojure library by Heavy Computation that
makes common Cloudflare products painless to use, so the same R2/Images glue
doesn't get copy-pasted into every webapp.

It's a `deps.edn` library (tools.build for packaging, cognitect `test-runner`
for tests). **Zero runtime dependencies beyond Clojure:** R2 is S3-compatible,
so we sign requests with AWS Signature V4 and send them over the JDK's
`java.net.http` client, against R2's custom endpoint
(`https://<account-id>.r2.cloudflarestorage.com`). No AWS SDK.

**Design principle:** layer a small convenience API on top of a thin signed-HTTP
S3 transport. Make easy things easy (`put`/`get`/`delete`/`presigned-url`), but
always leave an escape hatch (`request` — raw signed HTTP) for full S3 surface
area (multipart, range requests, conditional ops, metadata, tagging, pagination).

Source lives under `src/com/heavycomputation/cloudflare/`. Namespaces:
- `com.heavycomputation.cloudflare`          — public entry point (re-exports)
- `com.heavycomputation.cloudflare.r2`       — R2 object storage (this work)
- `com.heavycomputation.cloudflare.images`   — Cloudflare Images (later)
- `com.heavycomputation.cloudflare.internal.sigv4` — AWS SigV4 signing
- `com.heavycomputation.cloudflare.internal.s3`    — signed-HTTP S3 transport
- `com.heavycomputation.cloudflare.internal.xml`   — minimal S3 XML read/write

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
- [ ] Images: upload, variants, delivery URLs

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
