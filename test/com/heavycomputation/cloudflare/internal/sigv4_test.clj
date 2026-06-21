(ns com.heavycomputation.cloudflare.internal.sigv4-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.heavycomputation.cloudflare.internal.sigv4 :as sigv4]))

(def ^:private empty-hash
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(deftest sha256-hex-of-empty
  (is (= empty-hash (sigv4/sha256-hex (byte-array 0)))))

(deftest uri-encoding
  (testing "unreserved characters pass through"
    (is (= "abcXYZ09-_.~" (sigv4/uri-encode "abcXYZ09-_.~" true))))
  (testing "space and reserved characters are percent-encoded uppercase"
    (is (= "a%20b" (sigv4/uri-encode "a b" true)))
    (is (= "%3D%26" (sigv4/uri-encode "=&" true))))
  (testing "slash handling depends on the flag"
    (is (= "a%2Fb" (sigv4/uri-encode "a/b" true)))
    (is (= "a/b"   (sigv4/uri-encode "a/b" false)))))

(deftest canonical-query-string-sorts-and-encodes
  (is (= "" (sigv4/canonical-query-string {})))
  (is (= "partNumber=2&uploadId=a%2Fb"
         (sigv4/canonical-query-string {"uploadId" "a/b" "partNumber" "2"})))
  (is (= "uploads=" (sigv4/canonical-query-string {"uploads" ""}))))

;; AWS's own SigV4 documentation worked example ("GET Object" with a Range
;; header). If our canonicalization, string-to-sign, or key derivation drifts,
;; this signature won't match.
;; https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-header-based-auth.html
(deftest aws-published-vector
  (let [signed (sigv4/sign
                {:method            "GET"
                 :host              "examplebucket.s3.amazonaws.com"
                 :path              "/test.txt"
                 :query             {}
                 :headers           {"Range" "bytes=0-9"}
                 :payload-hash      empty-hash
                 :region            "us-east-1"
                 :service           "s3"
                 :access-key-id     "AKIAIOSFODNN7EXAMPLE"
                 :secret-access-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                 :amz-date          "20130524T000000Z"
                 :date              "20130524"})
        auth   (get signed "Authorization")]
    (testing "date headers are echoed back for sending"
      (is (= "20130524T000000Z" (get signed "x-amz-date")))
      (is (= empty-hash (get signed "x-amz-content-sha256"))))
    (testing "credential scope, signed headers, and signature match AWS"
      (is (str/includes?
           auth "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request"))
      (is (str/includes?
           auth "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"))
      (is (= "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
             (second (re-find #"Signature=([0-9a-f]+)" auth)))))))

;; AWS's published presigned-URL ("query parameters") worked example — same
;; credentials, a 24h expiry. Guards the query-string signing path.
;; https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
(deftest aws-published-presign-vector
  (let [url (sigv4/presigned-url
             {:method            "GET"
              :endpoint          "https://examplebucket.s3.amazonaws.com"
              :host              "examplebucket.s3.amazonaws.com"
              :path              "/test.txt"
              :query             {}
              :expires           86400
              :region            "us-east-1"
              :service           "s3"
              :access-key-id     "AKIAIOSFODNN7EXAMPLE"
              :secret-access-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
              :amz-date          "20130524T000000Z"
              :date              "20130524"})]
    (testing "auth parameters are present and slashes in the credential encoded"
      (is (str/starts-with? url "https://examplebucket.s3.amazonaws.com/test.txt?"))
      (is (str/includes? url "X-Amz-Expires=86400"))
      (is (str/includes?
           url "X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request")))
    (testing "the signature matches AWS"
      (is (= "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
             (second (re-find #"X-Amz-Signature=([0-9a-f]+)" url)))))))
