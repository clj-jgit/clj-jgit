(ns clj-jgit.test.low-level
  (:use 
    [clj-jgit.low-level]
    [clojure.test])
  (:import 
    [java.io File]
    [org.eclipse.jgit.api Git]
    [org.eclipse.jgit.lib Repository AnyObjectId ObjectId]
    [org.eclipse.jgit.revwalk RevWalk RevCommit]
    [org.eclipse.jgit.storage.file RefDirectory$LooseUnpeeled]))

; Using clj-jgit repo for read-only tests
(def read-only-repo-path (System/getProperty "user.dir"))

(defmacro read-only-repo [& body]
  `(with-repo ~read-only-repo-path
     ~@body))

(testing "with-repo macro"
  (read-only-repo
    (is (instance? Git repo))
    (is (instance? RevWalk rev-walk))))

(testing "find-rev-commit"
  (read-only-repo
    (is
      #(instance? RevCommit %)
      (resolve-object repo "38dd57264cf5c05fb77211c8347d1f16e4474623"))
    (is
      #(instance? RevCommit %)
      (resolve-object repo "master"))))

(testing "bound-commit"
  (read-only-repo
    (let [first-commit (resolve-object repo "38dd57264cf5c05fb77211c8347d1f16e4474623")
          master (resolve-object repo "master")
          rev-walk (new-rev-walk repo)]
      (is
        (instance? RevCommit (bound-commit repo rev-walk first-commit)))
      (is
        (instance? RevCommit (bound-commit repo rev-walk master))))))

(testing "branch-list-with-heads"
  (read-only-repo
    (let [branches (branch-list-with-heads repo (new-rev-walk repo))
          [branch-ref head-rev] (first branches)]
      (is (seq? branches))
      (is (instance? RefDirectory$LooseUnpeeled branch-ref))
      (is (instance? RevCommit head-rev)))))

(testing "branches-for"
  (read-only-repo
    (let [first-commit (resolve-object repo "38dd57264cf5c05fb77211c8347d1f16e4474623")]
      (is (= ["refs/heads/master"] (branches-for repo first-commit))))))

(testing "changed-files" 
  (read-only-repo
    (are 
      [commit-ish changes] (= changes (changes-for repo commit-ish))
      "38dd57264cf5c05fb77211c8347d1f16e4474623" [[".gitignore" :add] 
                                                  ["README.md" :add] 
                                                  ["project.clj" :add] 
                                                  ["src/clj_jgit/core.clj" :add] 
                                                  ["src/clj_jgit/util/print.clj" :add] 
                                                  ["test/clj_jgit/test/core.clj" :add]]
      "edb462cd4ea2f351c4c5f20ec0952e70e113c489" [["src/clj_jgit/porcelain.clj" :edit] 
                                                  ["src/clj_jgit/util.clj" :add] 
                                                  ["src/clj_jgit/util/core.clj" :delete] 
                                                  ["src/clj_jgit/util/print.clj" :delete] 
                                                  ["test/clj_jgit/test/core.clj" :edit]]
      "0d3d1c2e7b6c47f901fcae9ef661a22948c64573" [[".gitignore" :edit] 
                                                  ["src/clj_jgit/porcelain.clj" :edit] 
                                                  ["src/clj_jgit/util.clj" :add] 
                                                  ["src/clj_jgit/util/core.clj" :delete] 
                                                  ["src/clj_jgit/util/print.clj" :delete] 
                                                  ["test/clj_jgit/test/core.clj" :edit] 
                                                  ["test/clj_jgit/test/porcelain.clj" :add]])))

(testing "rev-list"
  (read-only-repo
    (is (>= (count (rev-list repo (new-rev-walk repo))) 24))))

(testing "commit-info"
  (read-only-repo
    (are 
      [commit-ish info] (= info (-> commit-ish
                                  ((partial find-rev-commit repo (new-rev-walk repo))) 
                                  ((partial commit-info repo))
                                  (dissoc :repo :raw :time)))
      "38dd57264cf5c05fb77211c8347d1f16e4474623" {:changed_files 
                                                  [[".gitignore" :add] 
                                                   ["README.md" :add] 
                                                   ["project.clj" :add] 
                                                   ["src/clj_jgit/core.clj" :add] 
                                                   ["src/clj_jgit/util/print.clj" :add] 
                                                   ["test/clj_jgit/test/core.clj" :add]], 
                                                  :author "Daniel Gregoire", 
                                                  :email "daniel.l.gregoire@gmail.com", 
                                                  :message "Initial commit", 
                                                  :branches ["refs/heads/master"], 
                                                  :merge false, 
                                                  :id "38dd57264cf5c05fb77211c8347d1f16e4474623"}
      "edb462cd4ea2f351c4c5f20ec0952e70e113c489" {:changed_files 
                                                  [["src/clj_jgit/porcelain.clj" :edit] 
                                                   ["src/clj_jgit/util.clj" :add] 
                                                   ["src/clj_jgit/util/core.clj" :delete] 
                                                   ["src/clj_jgit/util/print.clj" :delete] 
                                                   ["test/clj_jgit/test/core.clj" :edit]], 
                                                  :author "vijaykiran", 
                                                  :email "mail@vijaykiran.com", 
                                                  :message "Utils - move into a single file.\n- Test for utils method.", 
                                                  :branches ["refs/heads/master"], 
                                                  :merge false, 
                                                  :id "edb462cd4ea2f351c4c5f20ec0952e70e113c489"}
      "0d3d1c2e7b6c47f901fcae9ef661a22948c64573" {:changed_files 
                                                  [[".gitignore" :edit] 
                                                   ["src/clj_jgit/porcelain.clj" :edit] 
                                                   ["src/clj_jgit/util.clj" :add] 
                                                   ["src/clj_jgit/util/core.clj" :delete] 
                                                   ["src/clj_jgit/util/print.clj" :delete] 
                                                   ["test/clj_jgit/test/core.clj" :edit] 
                                                   ["test/clj_jgit/test/porcelain.clj" :add]], 
                                                  :author "Daniel Gregoire", 
                                                  :email "daniel.l.gregoire@gmail.com", 
                                                  :message "Merge pull request #2 from vijaykiran/master\n\nInit Tests", 
                                                  :branches ["refs/heads/master"], 
                                                  :merge true, 
                                                  :id "0d3d1c2e7b6c47f901fcae9ef661a22948c64573"})))

(testing "resolve-object"
  (read-only-repo
    (are
      [object-name] (instance? ObjectId (resolve-object repo object-name))
      "master" ; commit-ish
      "38dd57264cf5c05fb77211c8347d1f16e4474623" ; initial commit
      "cefa1a770d57f7f89a59d1a376ef5ffc480649ae" ; tree
      "1656b6ddae437f8cbdaabaa27e399cb431eec94e" ; blob
      )))
