(ns com.heavycomputation.cloudflare.images-integration-test
  "Live Cloudflare Images lifecycle test.

  Run with:

    clojure -M:integration -m cognitect.test-runner -d test-integration -n com.heavycomputation.cloudflare.images-integration-test

  Needs CLOUDFLARE_ACCOUNT_ID and CLOUDFLARE_API_TOKEN. For the managed agent
  environment, CLOUDFLARE_AGENT_API_TOKEN is accepted as a fallback. The image
  is deleted in a finally block even when an assertion fails."
  (:require [clojure.test :refer [deftest is testing]]
            [com.heavycomputation.cloudflare.images :as images])
  (:import (java.util Base64)))

(def config
  {:account-id (System/getenv "CLOUDFLARE_ACCOUNT_ID")
   :api-token  (or (System/getenv "CLOUDFLARE_API_TOKEN")
                   (System/getenv "CLOUDFLARE_AGENT_API_TOKEN"))})

(defn- configured? []
  (every? (comp seq config) [:account-id :api-token]))

(def ^:private one-pixel-png
  (.decode (Base64/getDecoder)
           "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="))

(deftest ^:integration live-images-roundtrip
  (if-not (configured?)
    (println (str "\n[skipped] live Images test — set CLOUDFLARE_ACCOUNT_ID "
                  "and CLOUDFLARE_API_TOKEN\n"))
    (let [client (images/client config)
          image-id (atom nil)]
      (println "\nRunning live Cloudflare Images round-trip")
      (try
        (testing "upload returns a durable image id"
          (let [uploaded (images/upload client one-pixel-png
                                        {:filename "hc-cloudflare-smoke.png"
                                         :metadata {:source "integration-test"}})]
            (reset! image-id (:id uploaded))
            (is (seq @image-id))
            (is (= "hc-cloudflare-smoke.png" (:filename uploaded)))
            (is (= {"source" "integration-test"} (:meta uploaded)))))

        (testing "the returned id addresses the uploaded image"
          (let [stored (images/get client @image-id)]
            (is (= @image-id (:id stored)))
            (is (true? (images/exists? client @image-id)))))

        (finally
          (when @image-id
            (images/delete client @image-id)
            (is (nil? (images/get client @image-id)))
            (println "Live Images round-trip complete; test image deleted.")))))))
