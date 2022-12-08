(ns clj-jgit.test.diff
  (:use [clj-jgit.diff]
        [clojure.test])
  (:import
    (java.io ByteArrayOutputStream)
    (org.eclipse.jgit.diff DiffFormatter Edit Edit$Type EditList RawText)
    (org.eclipse.jgit.util.io DisabledOutputStream)))

(deftest test-get-raw-text
  (let [test-string "Foo"
        raw-text-instance (get-raw-text test-string)]
  (testing "get-raw-text returns as RawText instance"
      (is (instance? RawText raw-text-instance)))
  (testing "RawText content matches test-string"
      (is (= (-> raw-text-instance .getRawContent String.) test-string)))))

(deftest test-diff-string
  (let [diff-result-matching (diff-string "Foo" "Foo")
        diff-result-replace (diff-string "Foo" "Bar")]
    (testing "diff-string with matching strings returns empty EditList"
      (is (instance? EditList diff-result-matching)
          (empty? diff-result-matching)))
    (testing "diff-string with non-matching strings returns a EditList with one Edit entry of type REPLACE"
      (is (instance? EditList diff-result-replace))
      (is (= (count diff-result-replace) 1))
      (is (= (-> diff-result-replace ^Edit first .getType) Edit$Type/REPLACE)))))

(deftest test-get-diff-formatter
    (testing "get-diff-formatter returns a DiffFormatter instance"
      (let [formatter (get-diff-formatter :output-stream (DisabledOutputStream/INSTANCE))]
        (is (instance? DiffFormatter formatter)))))

(deftest test-diff-string-formatted
  (let [diff-out (diff-string-formatted "Foo" "Bar")
        expected-patch "@@ -1 +1 @@\n-Foo\n\\ No newline at end of file\n+Bar\n\\ No newline at end of file\n"]
    (testing "diff-string-formatted returns ByteArrayOutputStream instance"
      (is (instance? ByteArrayOutputStream diff-out)))
    (testing "diff-string-formatted patch matches expected patch"
      (is (= (.toString diff-out) expected-patch)))))
