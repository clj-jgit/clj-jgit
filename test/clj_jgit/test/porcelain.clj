(ns clj-jgit.test.porcelain
  (:use [clj-jgit.test.helpers]
        [clj-jgit.porcelain]
        [clojure.test])
  (:import
    [java.io File]
    [org.eclipse.jgit.api Git PullResult]
    [org.eclipse.jgit.transport PushResult]
    [org.eclipse.jgit.revwalk RevWalk]
    [org.eclipse.jgit.api.errors NoHeadException]))

(deftest test-git-init
  (let [repo-dir (get-temp-dir)]
    (is (not (nil? (git-init repo-dir))))))

(deftest test-git-init-bare
  (testing "git-init bare option works"
    (let [repo-dir (get-temp-dir)]
      (is (-> (git-init repo-dir true)
              .getRepository
              .isBare)))))

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

(deftest test-remote-functions
  (with-tmp-repo "target/tmp"
    (let [remote-name "origin"
          remote-uri "git@github.com:clj-jgit/clj-jgit.git"]
      (testing "git-remote-list works and fresh repo doesn't have any remotes"
        (is (empty? (git-remote-list repo))))
      (testing "git-remote-add works and name/uri match inputs"
        (git-remote-add repo remote-name remote-uri)
        (let [[added-name uri-vec] (-> repo git-remote-list first)]
          (is (= remote-name added-name))
          (is (= remote-uri (-> uri-vec first .toString)))))
      (testing "git-remote-remove works and remotes list is empty again"
        (git-remote-remove repo remote-name)
        (is (empty? (git-remote-list repo)))))))

(deftest test-push-pull-functions
  (let [repo-a (git-init (get-temp-dir))
        repo-b (git-init (get-temp-dir))
        bare-dir (get-temp-dir)
        repo-bare (git-init bare-dir true)
        commit-msg "this is a test"]
    (testing "repo-a and repo-b have no commits"
      (is (thrown? NoHeadException (git-log repo-a)))
      (is (thrown? NoHeadException (git-log repo-b))))
    (testing "git-push works with test commit for repo-a using repo-bare as remote"
      (git-commit repo-a commit-msg)
      (git-remote-add repo-a "origin" (.getAbsolutePath bare-dir))
      (is (instance? PushResult (-> repo-a git-push first))))
    (testing "git-pull works for repo-b using repo-bare as remote and repo-b has a commit with matching message"
      (git-remote-add repo-b "origin" (.getAbsolutePath bare-dir))
      (is (instance? PullResult (git-pull repo-b)))
      (is (= commit-msg (-> repo-b git-log first .getFullMessage))))))
