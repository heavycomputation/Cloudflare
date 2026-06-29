(ns build
  "Build tasks for the heavycomputation/cloudflare library.

  Usage:
    clojure -T:build jar      ; build a jar into target/
    clojure -T:build install  ; install into the local Maven repo
    clojure -T:build deploy   ; deploy to Clojars (needs CLOJARS_* env vars)
    clojure -T:build clean"
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.heavycomputation/cloudflare)
(def version "0.1.3-SNAPSHOT")
(def class-dir "target/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :scm       {:url "https://github.com/heavycomputation/cloudflare"
                            :tag (str "v" version)}
                ;; Clojars requires the pom to declare a license.
                :pom-data  [[:licenses
                             [:license
                              [:name "MIT License"]
                              [:url "https://opensource.org/licenses/MIT"]]]]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Built" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "Installed" lib version "to local Maven repo"))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  (b/resolve-path jar-file)
              :pom-file  (b/pom-path {:lib lib :class-dir class-dir})})
  (println "Deployed" lib version "to Clojars"))
