(ns clj-jgit.test.util
  (:require [clojure.java.io :as io]
            [clj-jgit.test.helpers :refer :all]
            [clj-jgit.util :as util]
            [clojure.test :refer :all])
  (:import [java.io File IOException]))

(deftest recursive-delete-file
  (testing "Deleting a file"
    (let [temp-file (File/createTempFile "test" "clj-jgit")]
      (.deleteOnExit temp-file)
      (spit temp-file "test")
      (is (= "test" (slurp temp-file)))
      (is (true? (util/recursive-delete-file (.getAbsolutePath temp-file))))
      (is (not (.exists temp-file)) "The file does not exist after deletion.")))
  
  (testing "Deleting a directory"
    (let [temp-dir (get-temp-dir)
          temp-file (io/file (str (.getAbsolutePath temp-dir) "/test.txt"))]
      (spit temp-file "test")
      (is (.exists temp-dir) "The directory exists before deletion.")
      (is (= "test" (slurp temp-file)))
      (is (true? (util/recursive-delete-file (.getAbsolutePath temp-dir))))
      (is (not (.exists temp-dir)) "The directory does not exist after deletion.")
      (is (not (.exists temp-file)) "The file does not exist after deletion.")))

  (testing "Failing to delete files"
    (let [non-existent-file-path "does not exist"]
      (is (thrown? IOException (util/recursive-delete-file non-existent-file-path))
          "When silently is false, failing to delete a file raises an exception.")
      (is (true? (util/recursive-delete-file non-existent-file-path true ))
          "When silently is true, failing to delete a file does not raise an exception."))))

