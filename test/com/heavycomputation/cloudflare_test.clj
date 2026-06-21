(ns com.heavycomputation.cloudflare-test
  (:require [clojure.test :refer [deftest is]]
            [com.heavycomputation.cloudflare]))

(deftest namespace-loads
  (is (some? (find-ns 'com.heavycomputation.cloudflare))))
