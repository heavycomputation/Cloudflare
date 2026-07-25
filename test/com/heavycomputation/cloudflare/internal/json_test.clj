(ns com.heavycomputation.cloudflare.internal.json-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.heavycomputation.cloudflare.internal.json :as json]))

(deftest read-scalars
  (is (nil?    (json/read "null")))
  (is (true?   (json/read "true")))
  (is (false?  (json/read "false")))
  (is (= 42    (json/read "42")))
  (is (= -7    (json/read "-7")))
  (is (= 3.5   (json/read "3.5")))
  (is (= 1.0e3 (json/read "1e3")))
  (is (= "hi"  (json/read "\"hi\"")))
  (testing "blank / nil input reads as nil"
    (is (nil? (json/read nil)))
    (is (nil? (json/read "")))
    (is (nil? (json/read "   ")))))

(deftest read-string-escapes
  (is (= "a\"b"   (json/read "\"a\\\"b\"")))
  (is (= "a\\b"   (json/read "\"a\\\\b\"")))
  (is (= "tab\there" (json/read "\"tab\\there\"")))
  (is (= "line\nbreak" (json/read "\"line\\nbreak\"")))
  (is (= "/slash" (json/read "\"\\/slash\"")))
  (testing "\\uXXXX escapes"
    (is (= "é" (json/read "\"\\u00e9\"")))
    (is (= "A"      (json/read "\"\\u0041\"")))))

(deftest read-collections
  (is (= [] (json/read "[]")))
  (is (= {} (json/read "{}")))
  (is (= [1 2 3] (json/read "[1, 2, 3]")))
  (is (= ["a" true nil] (json/read "[\"a\", true, null]")))
  (is (= {"a" 1 "b" 2} (json/read "{\"a\":1,\"b\":2}")))
  (testing "keys stay as strings; nesting works"
    (is (= {"result" {"id" "x" "variants" ["u1" "u2"]}
            "success" true}
           (json/read "{\"result\":{\"id\":\"x\",\"variants\":[\"u1\",\"u2\"]},\"success\":true}"))))
  (testing "whitespace between tokens is ignored"
    (is (= {"a" [1 2]} (json/read "  {  \"a\" : [ 1 , 2 ]  }  ")))))

(deftest read-realistic-envelope
  (let [body (str "{\"success\":true,\"errors\":[],\"messages\":[],"
                  "\"result\":{\"id\":\"abc\",\"filename\":\"cat.jpg\","
                  "\"requireSignedURLs\":false,\"meta\":{\"owner\":\"u42\"},"
                  "\"uploaded\":\"2024-01-02T03:04:05.000Z\","
                  "\"variants\":[\"https://imagedelivery.net/h/abc/public\"]}}")
        parsed (json/read body)]
    (is (true? (parsed "success")))
    (is (= "abc" (get-in parsed ["result" "id"])))
    (is (= {"owner" "u42"} (get-in parsed ["result" "meta"])))
    (is (= false (get-in parsed ["result" "requireSignedURLs"])))))

(deftest read-malformed-throws
  (is (thrown? clojure.lang.ExceptionInfo (json/read "{\"a\":}")))
  (is (thrown? clojure.lang.ExceptionInfo (json/read "[1,")))
  (is (thrown? clojure.lang.ExceptionInfo (json/read "\"unterminated"))))

(deftest write-scalars
  (is (= "null"    (json/write nil)))
  (is (= "true"    (json/write true)))
  (is (= "false"   (json/write false)))
  (is (= "42"      (json/write 42)))
  (is (= "3.5"     (json/write 3.5)))
  (is (= "\"hi\""  (json/write "hi")))
  (testing "keywords/symbols stringify to their name"
    (is (= "\"cover\"" (json/write :cover)))))

(deftest write-escapes
  (is (= "\"a\\\"b\"" (json/write "a\"b")))
  (is (= "\"a\\\\b\"" (json/write "a\\b")))
  (is (= "\"x\\ny\""  (json/write "x\ny")))
  (testing "control characters use \\uXXXX"
    (is (= "\"\\u0000\"" (json/write (str (char 0)))))))

(deftest write-collections
  (is (= "[1,2,3]" (json/write [1 2 3])))
  (is (= "[]"      (json/write [])))
  (is (= "{}"      (json/write {})))
  (testing "keyword and string keys both work"
    (is (= "{\"owner\":\"u42\"}" (json/write {:owner "u42"})))
    (is (= "{\"owner\":\"u42\"}" (json/write {"owner" "u42"}))))
  (testing "nested"
    (is (= "{\"a\":[1,{\"b\":true}]}" (json/write {:a [1 {:b true}]})))))

(deftest round-trip
  (doseq [v [{"id" "abc" "n" 5 "ok" true "tags" ["a" "b"] "nested" {"x" 1}}
             [1 2 3]
             "hello world"
             {"meta" {"k" "v"} "requireSignedURLs" false}]]
    (is (= v (json/read (json/write v))) (pr-str v))))
