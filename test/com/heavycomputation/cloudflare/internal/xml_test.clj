(ns com.heavycomputation.cloudflare.internal.xml-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.heavycomputation.cloudflare.internal.xml :as xml]))

(deftest tag-text-extraction
  (let [doc (str "<InitiateMultipartUploadResult>"
                 "<Bucket>uploads</Bucket><Key>a/b.zip</Key>"
                 "<UploadId>abc123==</UploadId>"
                 "</InitiateMultipartUploadResult>")]
    (is (= "abc123==" (xml/tag-text doc "UploadId")))
    (is (= "a/b.zip"  (xml/tag-text doc "Key")))
    (is (nil? (xml/tag-text doc "Missing"))))
  (testing "entities are unescaped"
    (is (= "\"etag\"" (xml/tag-text "<ETag>&quot;etag&quot;</ETag>" "ETag")))
    (is (= "a&b"      (xml/tag-text "<Code>a&amp;b</Code>" "Code"))))
  (testing "tags with attributes still match"
    (is (= "x" (xml/tag-text "<ETag xmlns=\"y\">x</ETag>" "ETag")))))

(deftest complete-multipart-body
  (is (= (str "<CompleteMultipartUpload>"
              "<Part><PartNumber>1</PartNumber><ETag>\"a\"</ETag></Part>"
              "<Part><PartNumber>2</PartNumber><ETag>\"b\"</ETag></Part>"
              "</CompleteMultipartUpload>")
         (xml/complete-multipart-upload [{:PartNumber 1 :ETag "\"a\""}
                                         {:PartNumber 2 :ETag "\"b\""}]))))
