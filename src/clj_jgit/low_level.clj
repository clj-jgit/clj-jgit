(ns clj-jgit.low-level
  (:require [clojure.java.io :as io]
            [clj-jgit.util :as util]
            [clj-jgit.porcelain :as porcelain]
            [clojure.string :as string])
  (:use clojure.core.memoize)
  (:import
    [org.eclipse.jgit.diff DiffFormatter DiffEntry]
    [org.eclipse.jgit.util.io DisabledOutputStream]
    [org.eclipse.jgit.diff RawTextComparator]
    [org.eclipse.jgit.revwalk RevWalk RevCommit RevCommitList]
    [org.eclipse.jgit.treewalk TreeWalk]
    [org.eclipse.jgit.lib FileMode Repository ObjectIdRef ObjectId]
    [org.eclipse.jgit.api Git LogCommand]))

(declare resolve-object change-kind create-tree-walk diff-formatter-for-changes
         changed-files-in-first-commit prepare-file-entry
         mark-all-heads-as-start-for! rev-walk)

(defmacro with-repo [path & body]
  `(let [~'repo (porcelain/load-repo ~path)
         ~'rev-walk (new-rev-walk ~'repo)]
     ~@body))

(defn ^RevWalk new-rev-walk [^Git repo]
  (RevWalk. (.getRepository repo)))

(defn bound-commit 
  "Find a RevCommit object in a RevWalk and bound to it."
  [^Git repo 
   ^RevWalk rev-walk 
   ^ObjectId rev-commit]
  (.parseCommit rev-walk rev-commit))

(def cached-bound-commit (memo-lru bound-commit 10000))

(defn resolve-object
  "Find ObjectId instance for any Git name: commit-ish, tree-ish or blob."
  [^Git repo 
   ^String commit-ish]
  (.resolve (.getRepository repo) commit-ish))

(defn find-rev-commit
  "Find RevCommit instance in RevWalk by commit-ish"
  [^Git repo
   ^RevWalk rev-walk
   commit-ish]
  (->> commit-ish
    (resolve-object repo) 
    (cached-bound-commit repo rev-walk)))

(defn branch-list-with-heads
  "List of branches for a repo in pairs of [branch-ref branch-tip-commit]"
  [^Git repo
   ^RevWalk rev-walk]
  (letfn [(zip-commits [^ObjectIdRef branch-ref]
                        [branch-ref (bound-commit repo rev-walk (.getObjectId branch-ref))])] 
          (let [branches (porcelain/git-branch-list repo)]
            (map zip-commits branches))))

(def cached-branch-list-with-heads (memo-lru branch-list-with-heads 100))

(defn commit-in-branch? 
  "Checks if commit is merged into branch"
  [repo rev-walk branch-tip-commit commit]
  (.isMergedInto rev-walk (cached-bound-commit repo rev-walk commit) branch-tip-commit))

(defn branches-for [^Git repo 
                    ^ObjectId rev-commit]
  "List of branches in which specific commit is present"
  (let [rev-walk (new-rev-walk repo)] 
    (->> 
      (for [[^ObjectIdRef branch-ref ^RevCommit branch-tip-commit] (cached-branch-list-with-heads repo rev-walk)]
        (if (commit-in-branch? repo rev-walk branch-tip-commit rev-commit)
          (.getName branch-ref)))
      (remove nil?))))

(defn changed-files [^Git repo 
                     ^RevCommit rev-commit]
  (if-let [parent (first (.getParents rev-commit))]
    (let [rev-parent ^RevCommit parent
          df ^DiffFormatter (diff-formatter-for-changes repo)
          entries (.scan df rev-parent rev-commit)]
      (map prepare-file-entry entries))
    (changed-files-in-first-commit repo rev-commit)))

(defn changes-for
  "Find changes for commit-ish" 
  [^Git repo
   commit-ish]
  (->> commit-ish
    (find-rev-commit repo (new-rev-walk repo))
    (changed-files repo)))

(defn rev-list [^Git repo 
                ^RevWalk rev-walk]
  (.reset rev-walk)
  (mark-all-heads-as-start-for! repo rev-walk)
  (doto (RevCommitList.)
    (.source rev-walk)
    (.fillTo Integer/MAX_VALUE)))

(defn commit-info [^Git repo 
                   ^RevCommit rev-commit]
  (let [ident (.getAuthorIdent rev-commit)
        time (-> (.getCommitTime rev-commit) (* 1000) java.util.Date.)
        message (-> (.getFullMessage rev-commit) str string/trim)]
    {:id (.getName rev-commit)
     :repo repo
     :author (.getName ident)
     :email (.getEmailAddress ident)
     :time time
     :message message
     :changed_files (changed-files repo rev-commit)
     :merge (> (.getParentCount rev-commit) 1)
     :branches (branches-for repo rev-commit)
     :raw rev-commit}))

(defn- mark-all-heads-as-start-for! [^Git repo
                                     ^RevWalk rev-walk]
  (doseq [[objId ref] (.getAllRefsByPeeledObjectId (.getRepository repo))]
    (.markStart rev-walk (.lookupCommit rev-walk objId))))

(defn- change-kind [^DiffEntry entry]
  (let [change (.. entry getChangeType name)]
    (cond
      (= change "ADD") :add
      (= change "MODIFY") :edit
      (= change "DELETE") :delete
      (= change "COPY") :copy)))

(defn- create-tree-walk [^Git repo 
                         ^RevCommit rev-commit]
  (doto
    (TreeWalk. (.getRepository repo))
    (.addTree (.getTree rev-commit))
    (.setRecursive true)))

(defn- diff-formatter-for-changes [^Git repo]
  (doto 
    (DiffFormatter. DisabledOutputStream/INSTANCE)
    (.setRepository (.getRepository repo))
    (.setDiffComparator RawTextComparator/DEFAULT)
    (.setDetectRenames false)))

(defn- changed-files-in-first-commit [^Git repo 
                                      ^RevCommit rev-commit]
  (let [tree-walk (create-tree-walk repo rev-commit)
        changes (transient [])]
    (while (.next tree-walk)
      (conj! changes [(util/normalize-path (.getPathString tree-walk)) :add]))
    (persistent! changes)))

(defn- prepare-file-entry [^DiffEntry entry]
  (let [old-path (util/normalize-path (.getOldPath entry))
        new-path (util/normalize-path (.getNewPath entry))
        change-kind (change-kind entry)]
    (cond
      (= old-path new-path)   [new-path change-kind]
      (= old-path "dev/null") [new-path change-kind]
      (= new-path "dev/null") [old-path change-kind]
      :else [old-path change-kind new-path])))
