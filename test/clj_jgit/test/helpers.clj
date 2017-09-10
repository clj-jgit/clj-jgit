(ns clj-jgit.test.helpers
  (:require [clj-jgit.util :as util]
            [clojure.java.io :as io])
  (:use clj-jgit.porcelain)
  (:import [java.io File]))

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
     (util/recursive-delete-file ~repo-path true)
     (.mkdir (io/file ~repo-path))
     (git-init ~repo-path)
     (let [outcome# (with-repo ~repo-path ~@body)]
       (util/recursive-delete-file ~repo-path true)
       outcome#)))

(defn get-temp-dir
  "Returns a temporary directory"
  []
  (let [temp (File/createTempFile "test" "clj-jgit")]
    (if (.exists temp)
      (do
        (.delete temp)
        (.mkdir temp)
        (.deleteOnExit temp)))
    temp))
