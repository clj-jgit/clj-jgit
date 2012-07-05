(ns clj-jgit.test.helpers
  (:use clj-jgit.porcelain))

; Using clj-jgit repo for read-only tests
(def read-only-repo-path (System/getProperty "user.dir"))

(defmacro read-only-repo [& body]
  `(with-repo ~read-only-repo-path
     ~@body))
