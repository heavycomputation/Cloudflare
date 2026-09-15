(ns com.heavycomputation.cloudflare.internal.api-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.heavycomputation.cloudflare.internal.api :as api])
  (:import (java.nio.charset StandardCharsets)))

(deftest multipart-body-is-wire-ready
  (let [{:keys [headers body]}
        (#'api/prepare
         {:method "POST"
          :multipart [{:name "file"
                       :content (.getBytes "PNG" StandardCharsets/UTF_8)
                       :filename "avatar.png"
                       :content-type "image/png"}
                      {:name "metadata" :content "{\"user-id\":42}"}
                      {:name "requireSignedURLs" :content "false"}]})
        content-type (get headers "Content-Type")
        payload (String. ^bytes body StandardCharsets/ISO_8859_1)
        boundary (second (re-find #"boundary=(.+)$" content-type))]
    (is (str/starts-with? content-type "multipart/form-data; boundary="))
    (is (not (str/blank? boundary)))
    (testing "file headers and binary content are encoded as a form part"
      (is (str/includes? payload
                         "Content-Disposition: form-data; name=\"file\"; filename=\"avatar.png\"\r\n"))
      (is (str/includes? payload "Content-Type: image/png\r\n\r\nPNG\r\n")))
    (testing "text fields are encoded as separate form parts"
      (is (str/includes? payload
                         "name=\"metadata\"\r\n\r\n{\"user-id\":42}\r\n"))
      (is (str/includes? payload
                         "name=\"requireSignedURLs\"\r\n\r\nfalse\r\n")))
    (is (str/ends-with? payload (str "--" boundary "--\r\n")))))

(deftest json-body-is-wire-ready
  (let [{:keys [headers body]}
        (#'api/prepare {:method "PATCH" :json {:requireSignedURLs true}})]
    (is (= "application/json" (get headers "Content-Type")))
    (is (= "{\"requireSignedURLs\":true}"
           (String. ^bytes body StandardCharsets/UTF_8)))))
