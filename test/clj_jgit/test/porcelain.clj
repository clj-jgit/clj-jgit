(ns clj-jgit.test.porcelain
  (:use [clj-jgit.test.helpers]
        [clj-jgit.porcelain]
        [clojure.test])
  (:import 
    [java.io File]
    [org.eclipse.jgit.api Git]
    [org.eclipse.jgit.revwalk RevWalk]))

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

(testing "with-repo macro"
  (read-only-repo
    (is (instance? Git repo))
    (is (instance? RevWalk rev-walk))))