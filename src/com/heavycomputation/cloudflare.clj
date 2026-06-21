(ns com.heavycomputation.cloudflare
  "Heavy Computation's Cloudflare toolkit for Clojure.

  A thin, batteries-included wrapper over the Cloudflare products we reach for
  in every webapp. Features are added incrementally:

    - R2     object storage  (S3-compatible)   -> com.heavycomputation.cloudflare.r2
    - Images delivery/upload                    -> com.heavycomputation.cloudflare.images

  Nothing is implemented yet — this namespace is the public entry point and
  will re-export the high-level API as features land.")
