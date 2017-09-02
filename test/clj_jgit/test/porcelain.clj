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

(deftest test-tag-functions
  (with-tmp-repo "target/tmp"
    ;; setup: the repo must have at least one commit (i.e. have a HEAD) in order
    ;; to create a tag
    (git-commit repo "initial commit")
    (testing "there are initially no tags"
      (is (= () (git-tag-list repo))))
    (testing "after creating a tag, the tag is in the list"
      (git-tag-create repo "foo")
      (is (= ["foo"] (git-tag-list repo))))
    (testing "after creating another tag, both tags are in the list"
      (git-tag-create repo "bar" "bar tag message goes here")
      (is (= #{"foo" "bar"} (set (git-tag-list repo)))))
    (testing "after deleting one tag, the other tag is still in the list"
      (git-tag-delete repo "foo")
      (is (= ["bar"] (git-tag-list repo))))
    (testing "after deleting the other tag, there are no tags in the list"
      (git-tag-delete repo "bar")
      (is (= () (git-tag-list repo))))))
