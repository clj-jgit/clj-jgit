(ns clj-jgit.internal
  (:import
    [org.eclipse.jgit.revwalk RevWalk RevCommit RevCommitList]
    [org.eclipse.jgit.treewalk TreeWalk]
    [org.eclipse.jgit.lib ObjectId]
    [org.eclipse.jgit.api Git]
    [org.eclipse.jgit.transport RefSpec]))

(defn ref-spec
  ^org.eclipse.jgit.transport.RefSpec [str]
  (RefSpec. str))

(defn new-rev-walk
  "Creates a new RevWalk instance (mutable)"
  ^org.eclipse.jgit.revwalk.RevWalk [^Git repo]
  (RevWalk. (.getRepository repo)))

(defn new-tree-walk
  "Create new recursive TreeWalk instance (mutable)"
  ^org.eclipse.jgit.treewalk.TreeWalk [^Git repo ^RevCommit rev-commit]
  (doto
    (TreeWalk. (.getRepository repo))
    (.addTree (.getTree rev-commit))
    (.setRecursive true)))

(defn bound-commit
  "Find a RevCommit object in a RevWalk and bound to it."
  ^org.eclipse.jgit.revwalk.RevCommit [^Git repo ^RevWalk rev-walk ^ObjectId rev-commit]
  (.parseCommit rev-walk rev-commit))

(defprotocol Resolvable
  (resolve-object [commit-ish repo]
    "Find ObjectId instance for any Git name: commit-ish, tree-ish or blob. Accepts ObjectId instances and just passes them through."))

(extend-type String
  Resolvable
  (resolve-object
    ^org.eclipse.jgit.lib.ObjectId [^String commit-ish ^Git repo]
    (.resolve (.getRepository repo) commit-ish)))

(extend-type ObjectId
  Resolvable
  (resolve-object
    ^org.eclipse.jgit.lib.ObjectId [commit-ish ^Git repo]
    commit-ish))

(extend-type Git
  Resolvable
  (resolve-object
    ^org.eclipse.jgit.lib.ObjectId [^Git repo commit-ish]
    "For compatibility with previous implementation of resolve-object, which would take repo as a first argument."
    (resolve-object commit-ish repo)))
