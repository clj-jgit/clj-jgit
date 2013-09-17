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

(deftest porcelain-tests
  (testing "with-repo macro"
    (read-only-repo
      (is (instance? Git repo))
      (is (instance? RevWalk rev-walk)))))

(deftest test-current-branch-functions
  (is (= [true
          "master"
          40
          false]
         (with-tmp-repo "target/tmp"
           (let [tmp-file "target/tmp/tmp.txt"]
             (spit tmp-file "1")
             (git-add repo tmp-file)
             (git-commit repo "first commit")
             (let [sha (->> repo git-log first str
                            (re-matches #"^commit ([^\s]+) .*") second)]
               [(git-branch-attached? repo)
                (git-branch-current repo)
                (do (git-checkout repo sha),
                    (count (git-branch-current repo))) ; char count suggests sha
                (git-branch-attached? repo)]))))))