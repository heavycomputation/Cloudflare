# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Added
- Cloudflare Images client with local uploads (`File`, `byte[]`, or
  `InputStream`), remote URL ingestion, and browser-direct one-time upload URLs.
- Image management through `get`, `list`/`list-seq`, `update`, `delete`, and
  `exists?`, plus original-image downloads, account statistics, signing keys,
  and a raw REST API escape hatch.
- Named and flexible delivery URLs, including HMAC-signed URLs for private
  images.
- Zero-dependency Cloudflare REST v4 transport and JSON codec.
- Live Images upload/get/delete integration coverage.

## [0.1.2] - 2026-06-29

### Added
- R2: browser-direct multipart uploads — `create-multipart-upload`,
  `presigned-upload-part`, `complete-multipart-upload`, and
  `abort-multipart-upload` are now public. A server can broker the multipart
  lifecycle and hand the browser one presigned PUT URL per part, so the bytes
  of a large object never pass through it.

## [0.1.1] - 2026-06-21

First public release. (`0.1.0` was burned by a partial Clojars deploy whose pom
omitted a license; it is not a usable release — use `0.1.1`.)

### Added
- Initial project scaffolding (deps.edn, tools.build, test runner).
- R2: `bucket` handle construction against R2's S3-compatible endpoint.
- R2: `put` with automatic single-PUT vs. multipart selection — large objects
  (and unknown-length streams) upload in concurrent, individually-retried
  parts, aborting the upload on failure to avoid orphaned parts.
- R2: downloads — `get-bytes`, `get` (bytes + metadata), and `get-stream`
  (streaming, for large objects); all return `nil` for a missing key.
- R2: `head` (object metadata) and `exists?`.
- R2: `delete`, and `delete-many` (batched, automatically chunked above the
  1000-key S3 limit, reporting per-key errors).
- R2: `list` (one page, with `:prefix`/`:delimiter`/`:max-keys` and
  continuation tokens) and `list-seq` (lazy enumeration across all pages).
- R2: `presigned-url` for time-limited download (`:get`) and direct-upload
  (`:put`) URLs, via SigV4 query-string signing.
- R2: `request` escape hatch to the raw signed-S3 surface.
- Internal zero-dependency S3 transport: an AWS Signature V4 signer
  (`internal.sigv4`, header and presigned signing, both checked against AWS's
  published test vectors) over the JDK `java.net.http` client (`internal.s3`),
  with minimal S3 XML handling (`internal.xml`).

### Changed
- Dropped the Cognitect `aws-api` dependency in favour of the zero-dependency
  transport above. The library now adds only a ~13 KB jar (plus Clojure)
  instead of the ~250 KB of transitive AWS SDK artifacts. The R2 escape hatch
  changed from aws-api op maps (`invoke`) to raw signed HTTP (`request`).
