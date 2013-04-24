(ns clj-jgit.internal
  (:import
    [org.eclipse.jgit.revwalk RevWalk RevCommit RevCommitList]
    [org.eclipse.jgit.treewalk TreeWalk]
    [org.eclipse.jgit.lib ObjectId]
    [org.eclipse.jgit.api Git]))

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

(defn resolve-object
  "Find ObjectId instance for any Git name: commit-ish, tree-ish or blob."
  ^org.eclipse.jgit.lib.ObjectId [^Git repo ^String commit-ish]
  (.resolve (.getRepository repo) commit-ish))