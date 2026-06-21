(ns com.heavycomputation.cloudflare
  "Heavy Computation's Cloudflare toolkit for Clojure.

  A thin, batteries-included wrapper over the Cloudflare products we reach for
  in every webapp. Features are added incrementally:

    - R2     object storage  (S3-compatible)   -> com.heavycomputation.cloudflare.r2
    - Images delivery/upload                    -> com.heavycomputation.cloudflare.images

  This namespace is the public entry point and re-exports the high-level API as
  features land. For the full surface (and the raw signed-HTTP escape hatch),
  use the per-product namespaces directly.

  `get`/`list` shadow `clojure.core` names and are excluded here; refer to them
  qualified (e.g. `cloudflare/get`)."
  (:refer-clojure :exclude [get list])
  (:require [com.heavycomputation.cloudflare.r2 :as r2]))

(defn- import-var
  "Alias `target`'s value, docstring, and arglists under `sym` in this ns."
  [sym target]
  (let [v (intern *ns* sym @target)]
    (alter-meta! v merge (select-keys (meta target) [:doc :arglists]))
    v))

;; R2 object storage
(import-var 'bucket        #'r2/bucket)
(import-var 'put           #'r2/put)
(import-var 'get           #'r2/get)
(import-var 'get-bytes     #'r2/get-bytes)
(import-var 'get-stream    #'r2/get-stream)
(import-var 'head          #'r2/head)
(import-var 'exists?       #'r2/exists?)
(import-var 'delete        #'r2/delete)
(import-var 'delete-many   #'r2/delete-many)
(import-var 'list          #'r2/list)
(import-var 'list-seq      #'r2/list-seq)
(import-var 'presigned-url #'r2/presigned-url)
(import-var 'request       #'r2/request)
