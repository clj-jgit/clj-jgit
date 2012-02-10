(ns clj-jgit.test.porcelain
  (:use [clj-jgit.porcelain]
        [clojure.test])
  (:import [java.io File]))

(defn- get-temp-dir
  "Returns a temporary directory"
  []
  (let [temp (File/createTempFile "test" "repo")]
    (if (.exists temp)
      (do
        (.delete temp)
        (.mkdir temp)
        (.deleteOnExit temp)))
    temp))

(deftest test-git-init
  (let [repo-dir (get-temp-dir)]
     (is #(nil? %) (git-init repo-dir))))
