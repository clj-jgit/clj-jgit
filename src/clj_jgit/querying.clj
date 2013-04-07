(ns clj-jgit.querying
  (:require [clojure.java.io :as io]
            [clj-jgit.util :as util]
            [clj-jgit.porcelain :as porcelain]
            [clojure.string :as string])
  (:use
    clj-jgit.internal
    clojure.core.memoize)
  (:import
    [org.eclipse.jgit.diff DiffFormatter DiffEntry]
    [org.eclipse.jgit.util.io DisabledOutputStream]
    [org.eclipse.jgit.diff RawTextComparator]
    [org.eclipse.jgit.revwalk RevWalk RevCommit RevCommitList]
    [org.eclipse.jgit.lib FileMode Repository ObjectIdRef ObjectId AnyObjectId]
    [org.eclipse.jgit.api Git LogCommand]
    [org.eclipse.jgit.storage.file RefDirectory$LooseNonTag]))

(declare change-kind create-tree-walk diff-formatter-for-changes
         changed-files-in-first-commit parse-diff-entry
         mark-all-heads-as-start-for!)

(defn find-rev-commit
  "Find RevCommit instance in RevWalk by commit-ish"
  [^Git repo
   ^RevWalk rev-walk
   commit-ish]
  (->> commit-ish
    (resolve-object repo) 
    (bound-commit repo rev-walk)))

(defn branch-list-with-heads
  "List of branches for a repo in pairs of [branch-ref branch-tip-commit]"
  ([^Git repo] (branch-list-with-heads repo (new-rev-walk repo)))
  ([^Git repo
    ^RevWalk rev-walk]
    (letfn [(zip-commits [^ObjectIdRef branch-ref]
                         [branch-ref (bound-commit repo rev-walk (.getObjectId branch-ref))])] 
           (let [branches (porcelain/git-branch-list repo)]
             (doall (map zip-commits branches))))))

(defn commit-in-branch? 
  "Checks if commit is merged into branch"
  [^Git repo
   ^RevWalk rev-walk 
   ^RevCommit branch-tip-commit 
   ^ObjectId bound-commit]
  (.isMergedInto rev-walk bound-commit branch-tip-commit))

(defn branches-for
  "List of branches in which specific commit is present"
  ([^Git repo
    ^ObjectId rev-commit]
    (let [rev-walk (new-rev-walk repo)
          bound-commit (bound-commit repo rev-walk rev-commit)
          branch-list (branch-list-with-heads repo rev-walk)]
      (->> 
        (for [[^ObjectIdRef branch-ref ^RevCommit branch-tip-commit] branch-list
              :when branch-tip-commit]
          (do
            (when (commit-in-branch? repo rev-walk branch-tip-commit bound-commit)
              (.getName branch-ref))))
        (remove nil?)
        doall))))

(defn changed-files [^Git repo 
                     ^RevCommit rev-commit]
  (if-let [parent (first (.getParents rev-commit))]
    (let [rev-parent ^RevCommit parent
          df ^DiffFormatter (diff-formatter-for-changes repo)
          entries (.scan df rev-parent rev-commit)]
      (map parse-diff-entry entries))
    (changed-files-in-first-commit repo rev-commit)))

(defn changes-for
  "Find changes for commit-ish" 
  [^Git repo
   commit-ish]
  (->> commit-ish
    (find-rev-commit repo (new-rev-walk repo))
    (changed-files repo)))

(defn rev-list 
  ([^Git repo] (rev-list repo (new-rev-walk repo)))
  ([^Git repo 
    ^RevWalk rev-walk] 
    (.reset rev-walk)
    (mark-all-heads-as-start-for! repo rev-walk)
    (doto (RevCommitList.)
      (.source rev-walk)
      (.fillTo Integer/MAX_VALUE))))

(defn commit-info-without-branch
  [^Git repo
   ^RevWalk rev-walk
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
     :raw rev-commit ; can't retain commit because then RevCommit can't be garbage collected
     }))

(defn commit-info 
  ([^Git repo, ^RevCommit rev-commit] 
    (commit-info repo (new-rev-walk repo) rev-commit))
  ([^Git repo, ^RevWalk rev-walk, ^RevCommit rev-commit]
    (merge (commit-info-without-branch repo rev-walk rev-commit)
      {:branches (branches-for repo rev-commit)}))
  ([^Git repo
    ^RevWalk rev-walk
    commit-map
    ^RevCommit rev-commit]
    (merge (commit-info-without-branch repo rev-walk rev-commit)
      {:branches (map #(.getName %) (or (.get commit-map rev-commit) []))})))

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

(defn- diff-formatter-for-changes [^Git repo]
  (doto 
    (DiffFormatter. DisabledOutputStream/INSTANCE)
    (.setRepository (.getRepository repo))
    (.setDiffComparator RawTextComparator/DEFAULT)
    (.setDetectRenames false)))

(defn- changed-files-in-first-commit [^Git repo 
                                      ^RevCommit rev-commit]
  (let [tree-walk (new-tree-walk repo rev-commit)
        changes (transient [])]
    (while (.next tree-walk)
      (conj! changes [(util/normalize-path (.getPathString tree-walk)) :add]))
    (persistent! changes)))

(defn- parse-diff-entry [^DiffEntry entry]
  (let [old-path (util/normalize-path (.getOldPath entry))
        new-path (util/normalize-path (.getNewPath entry))
        change-kind (change-kind entry)]
    (cond
      (= old-path new-path)   [new-path change-kind]
      (= old-path "dev/null") [new-path change-kind]
      (= new-path "dev/null") [old-path change-kind]
      :else [old-path change-kind new-path])))

(defn rev-list-for
  ([^Git repo 
    ^RevWalk rev-walk
    ^RefDirectory$LooseNonTag object] 
    (.reset rev-walk)
    (.markStart rev-walk (.lookupCommit ^RevWalk rev-walk ^AnyObjectId (.getObjectId object)))
    (.toArray 
      (doto (RevCommitList.)
        (.source rev-walk)
        (.fillTo Integer/MAX_VALUE)))))

(defn- add-branch-to-map
  [repo rev-walk branch ^java.util.HashMap m]
  (let [^"[Ljava.lang.Object;" revs (rev-list-for repo rev-walk branch)]
    (dotimes [i (alength revs)]
      (let [c (aget revs i)]
        (.put m c (conj (or (.get m c) []) branch))))))

(defn build-commit-map
  ([repo] (build-commit-map repo (new-rev-walk repo)))
  ([repo rev-walk]
    (let [^java.util.HashMap m (java.util.HashMap.)]
      (loop [[branch & branches] (vals (.getRefs (.getRefDatabase (.getRepository repo)) "refs/heads/"))]
        (add-branch repo rev-walk branch m)
        (if branches (recur branches) m)))))
