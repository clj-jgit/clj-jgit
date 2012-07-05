(ns clj-jgit.internal
  (:import
    [org.eclipse.jgit.revwalk RevWalk RevCommit RevCommitList]
    [org.eclipse.jgit.treewalk TreeWalk]
    [org.eclipse.jgit.lib ObjectId]
    [org.eclipse.jgit.api Git]))

(defn ^RevWalk new-rev-walk [^Git repo]
  (RevWalk. (.getRepository repo)))

(defn ^TreeWalk new-tree-walk
  "Create new recursive TreeWalk instance"
  [^Git repo 
   ^RevCommit rev-commit]
  (doto
    (TreeWalk. (.getRepository repo))
    (.addTree (.getTree rev-commit))
    (.setRecursive true)))

(defn ^RevCommit bound-commit 
  "Find a RevCommit object in a RevWalk and bound to it."
  [^Git repo 
   ^RevWalk rev-walk 
   ^ObjectId rev-commit]
  (.parseCommit rev-walk rev-commit))

(defn ^ObjectId resolve-object
  "Find ObjectId instance for any Git name: commit-ish, tree-ish or blob."
  [^Git repo 
   ^String commit-ish]
  (.resolve (.getRepository repo) commit-ish))