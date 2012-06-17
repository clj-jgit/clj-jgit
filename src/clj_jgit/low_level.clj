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
    [org.eclipse.jgit.lib FileMode Repository ObjectIdRef]
    [org.eclipse.jgit.api Git LogCommand]))

(declare raw-repo raw-repo find-object-id
         change-kind create-tree-walk diff-formatter-for-changes
         changed-files-in-first-commit prepare-file-entry
         mark-all-heads-as-start-for!)

(defn universal-repo [path]
  (let [git-repo ^Git (porcelain/load-repo path)
        raw-repo ^Repository (.getRepository git-repo)
        rev-walk ^RevWalk (RevWalk. raw-repo)]
    {:git git-repo
     :raw raw-repo
     :walk rev-walk}))

(defmacro with-repo [repo-path & body]
  `(let [~'repo (universal-repo ~repo-path)]
     ~@body))

(defn bound-commit 
  "Find a RevCommit object in a RevWalk"
  [repo ^RevCommit rev-commit]
  (.parseCommit (:walk repo) rev-commit))

(def cached-bound-commit (memo-lru bound-commit 10000))

(defn find-object-id 
  "Find RevCommit instance not in any RevWalk by commit-ish"
  [repo commit-ish]
  (.resolve (:raw repo) commit-ish))

(defn find-rev-commit
  "Find RevCommit instance in RevWalk by commit-ish"
  [repo commit-ish]
  (->> commit-ish
    (find-object-id repo)
    (cached-bound-commit repo)))

(defn branch-list-with-heads
  "List of branches for a repo with heads of each branch"
  [repo]
  (let [branches (porcelain/git-branch-list (:git repo))]
    (map (fn [^ObjectIdRef branch-ref]
           [branch-ref (.parseCommit (:walk repo) (.getObjectId branch-ref))]) branches)))

(def cached-branch-list-with-heads (memo-lru branch-list-with-heads 100))

(defn branches-for [repo ^RevCommit rev-commit]
  "List of branches in which specific commit is present."
  (->> 
    (for [[^ObjectIdRef branch-ref ^RevCommit rev-branch-tip] (cached-branch-list-with-heads repo)]
      (when (.isMergedInto (:walk repo) (cached-bound-commit repo rev-commit) rev-branch-tip)
        (.getName branch-ref)))
    (remove nil?)))

(defn changed-files [repo ^RevCommit rev-commit]
  (try
    (let [rev-parent ^RevCommit (.getParent rev-commit 0)
          df ^DiffFormatter (diff-formatter-for-changes repo)
          entries (.scan df rev-parent rev-commit)]
      (map prepare-file-entry entries))
    (catch ArrayIndexOutOfBoundsException _
      (changed-files-in-first-commit repo rev-commit))))

(defn changes-for
  "Find changes for commit-ish"
  [repo commit-ish]
  (->> commit-ish
    (find-rev-commit repo)
    (changed-files repo)))

(defn rev-list [repo]
  (let [list (RevCommitList.)]
    (.reset (:walk repo))
    (mark-all-heads-as-start-for! repo)
    (doto list
      (.source (:walk repo))
      (.fillTo Integer/MAX_VALUE))))

(defn commit-info [repo ^RevCommit rev-commit]
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

(defn- mark-all-heads-as-start-for! [repo]
  (doseq [[objId ref] (.getAllRefsByPeeledObjectId (:raw repo))]
    (.markStart (:walk repo) (.lookupCommit (:walk repo) objId))))

(defn- change-kind [^DiffEntry entry]
  (let [change (.. entry getChangeType name)]
    (cond
      (= change "ADD") :add
      (= change "MODIFY") :edit
      (= change "DELETE") :delete
      (= change "COPY") :copy)))

(defn- create-tree-walk [repo ^RevCommit rev-commit]
  (doto
    (TreeWalk. (:raw repo))
    (.addTree (.getTree rev-commit))
    (.setRecursive true)))

(defn- diff-formatter-for-changes [repo]
  (doto 
    (DiffFormatter. DisabledOutputStream/INSTANCE)
    (.setRepository (:raw repo))
    (.setDiffComparator RawTextComparator/DEFAULT)
    (.setDetectRenames false)))

(defn- changed-files-in-first-commit [repo ^RevCommit rev-commit]
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
