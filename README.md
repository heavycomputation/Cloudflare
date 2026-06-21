# cloudflare

[![Clojars Project](https://img.shields.io/clojars/v/com.heavycomputation/cloudflare.svg)](https://clojars.org/com.heavycomputation/cloudflare)

A batteries-included Clojure library by [Heavy Computation](https://heavycomputation.com)
that makes working with Cloudflare products painless — so you stop copy-pasting
the same R2 and Images glue into every new webapp.

## Status

Early and incremental. The scaffolding is in place; features are landing one at
a time:

- [ ] **R2** — object storage (S3-compatible)
- [ ] **Images** — upload, variants, and delivery

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
