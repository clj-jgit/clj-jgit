(ns clj-jgit.test.helpers
  (:require [fs.core :as fs])
  (:use clj-jgit.porcelain))

; Using clj-jgit repo for read-only tests
(def read-only-repo-path (System/getProperty "user.dir"))

(defmacro read-only-repo [& body]
  `(with-repo ~read-only-repo-path
     ~@body))

(defmacro with-tmp-repo
  "execute operations within a created and immediately deleted repo,
   producing only the results of expressions in body."
  [repo-path & body]
  `(do
     (fs/delete-dir ~repo-path)
     (fs/mkdir ~repo-path)
     (git-init ~repo-path)
     (let [outcome# (with-repo ~repo-path ~@body)]
       (fs/delete-dir ~repo-path)
       outcome#)))