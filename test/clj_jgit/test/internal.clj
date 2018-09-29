(ns clj-jgit.test.internal
  (:use
    [clj-jgit.test.helpers]
    [clj-jgit.porcelain]
    [clj-jgit.querying]
    [clj-jgit.internal]
    [clojure.test])
  (:import
    [org.eclipse.jgit.lib ObjectId]
    [org.eclipse.jgit.revwalk RevWalk RevCommit]
    [org.eclipse.jgit.treewalk TreeWalk]))

(deftest internal-tests
  (testing "resolve-object"
    (read-only-repo
      (are
        [object-name] (instance? ObjectId (resolve-object object-name repo))
        "master" ; commit-ish
        "38dd57264cf5c05fb77211c8347d1f16e4474623" ; initial commit
        "cefa1a770d57f7f89a59d1a376ef5ffc480649ae" ; tree
        "1656b6ddae437f8cbdaabaa27e399cb431eec94e" ; blob
        )))

  (testing "bound-commit"
    (read-only-repo
      (are
        [commit-ish] (instance? RevCommit
                                (bound-commit repo
                                              (new-rev-walk repo)
                                              (resolve-object commit-ish repo)))
        "38dd57264cf5c05fb77211c8347d1f16e4474623" ; initial commit
        "master" ; branch name
        "master^" ; commit before master's head
        )))

  (testing "new-tree-walk"
    (read-only-repo
      (is (instance? TreeWalk (new-tree-walk repo (find-rev-commit repo (new-rev-walk repo) "master"))))))

  (testing "get-head-commit"
    (read-only-repo
      (is (instance? RevCommit (get-head-commit repo))))))
