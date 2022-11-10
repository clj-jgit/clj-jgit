(ns dev
  (:require
    [clj-jgit.internal :as i]
    [clj-jgit.porcelain :as p]
    [clj-jgit.querying :as q]
    [clj-jgit.util :as u]
    [clojure.tools.namespace.repl :refer [refresh] :as repl]
    [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def test-repo-path
  "Use exported shell env JGIT_TEST_REPO_PATH or a local string as path for a test repo"
  (or (System/getenv "JGIT_TEST_REPO_PATH")
                     "some/path"))

(def repo
  "Preload a test Git repo for REPL sessions"
  (when (-> test-repo-path io/file .exists)
    (p/load-repo test-repo-path)))
