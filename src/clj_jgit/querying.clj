(ns clj-jgit.querying
  (:require [clojure.string :as string]
            [clj-jgit.internal :refer :all]
            [clj-jgit.porcelain :as porcelain]
            [clj-jgit.util :as util])
  (:import (java.io ByteArrayOutputStream)
           (java.util HashMap Date)
           (org.eclipse.jgit.api Git)
           (org.eclipse.jgit.diff DiffFormatter DiffEntry RawTextComparator)
           (org.eclipse.jgit.internal.storage.file RefDirectory$LooseRef)
           (org.eclipse.jgit.lib ObjectIdRef ObjectId AnyObjectId Ref)
           (org.eclipse.jgit.revwalk RevWalk RevCommit RevCommitList)
           (org.eclipse.jgit.util.io DisabledOutputStream)))

(declare change-kind create-tree-walk diff-formatter-for-changes
         byte-array-diff-formatter-for-changes changed-files-in-first-commit
         parse-diff-entry mark-all-heads-as-start-for!)

(defn find-rev-commit
  "Find RevCommit instance in RevWalk by commit-ish. Returns nil if the commit is not found."
  [^Git repo ^RevWalk rev-walk commit-ish]
  (let [object (resolve-object commit-ish repo)]
    (if (nil? object)
      nil
      (bound-commit repo rev-walk object))))

(defn branch-list-with-heads
  "List of branches for a repo in pairs of [branch-ref branch-tip-commit]"
  ([^Git repo]
    (branch-list-with-heads repo (new-rev-walk repo)))
  ([^Git repo ^RevWalk rev-walk]
    (letfn [(zip-commits [^ObjectIdRef branch-ref]
                         [branch-ref (bound-commit repo rev-walk (.getObjectId branch-ref))])]
           (let [branches (porcelain/git-branch-list repo :jgit? true)]
             (doall (map zip-commits branches))))))

(defn commit-in-branch?
  "Checks if commit is merged into branch"
  [^Git repo ^RevWalk rev-walk ^RevCommit branch-tip-commit ^ObjectId bound-commit]
  (.isMergedInto rev-walk bound-commit branch-tip-commit))

(defn branches-for
  "List of branches in which specific commit is present"
  [^Git repo ^ObjectId rev-commit]
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
      doall)))

(defn changed-files-between-commits
  "List of files changed between two RevCommit objects"
  [^Git repo ^RevCommit old-rev-commit ^RevCommit new-rev-commit]
    (let [df ^DiffFormatter (diff-formatter-for-changes repo)
          entries (.scan df old-rev-commit new-rev-commit)]
      (map parse-diff-entry entries)))

(defn changed-files
  "List of files changed in RevCommit object"
  [^Git repo ^RevCommit rev-commit]
  (if-let [parent (first (.getParents rev-commit))]
    (changed-files-between-commits repo parent rev-commit)
    (changed-files-in-first-commit repo rev-commit)))

(defn changed-files-with-patch
  "Patch with diff of all changes in RevCommit object"
  [^Git repo ^RevCommit rev-commit]
  (if-let [parent (first (.getParents rev-commit))]
    (let [rev-parent ^RevCommit parent
          out ^ByteArrayOutputStream (new ByteArrayOutputStream)
          df ^DiffFormatter (byte-array-diff-formatter-for-changes repo out)]
      (.format df rev-parent rev-commit)
      (.toString out))))

(defn changes-for
  "Find changes for commit-ish. Returns nil if the commit is not found."
  [^Git repo commit-ish]
  (let [rev-commit (->> commit-ish
                        (find-rev-commit repo (new-rev-walk repo)))]
    (if (nil? rev-commit)
      nil
      (changed-files repo rev-commit))))

(defn rev-list
  "List of all revision in repo"
  ([^Git repo]
    (rev-list repo (new-rev-walk repo)))
  ([^Git repo ^RevWalk rev-walk]
    (.reset rev-walk)
    (mark-all-heads-as-start-for! repo rev-walk)
    (doto (RevCommitList.)
      (.source rev-walk)
      (.fillTo Integer/MAX_VALUE))))

(defn commit-info-without-branches
  [^Git repo ^RevWalk rev-walk ^RevCommit rev-commit]
  (let [ident (.getAuthorIdent rev-commit)
        time (-> (.getCommitTime rev-commit) (* 1000) Date.)
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
    (merge (commit-info-without-branches repo rev-walk rev-commit)
      {:branches (branches-for repo rev-commit)}))
  ([^Git repo ^RevWalk rev-walk ^HashMap commit-map ^RevCommit rev-commit]
    (merge (commit-info-without-branches repo rev-walk rev-commit)
      {:branches (map #(.getName ^Ref %) (or (.get commit-map rev-commit) []))})))

(defn- mark-all-heads-as-start-for!
  [^Git repo ^RevWalk rev-walk]
  (doseq [[objId ref] (.getAllRefsByPeeledObjectId (.getRepository repo))]
    (.markStart rev-walk (.lookupCommit rev-walk objId))))

(defn- change-kind
  [^DiffEntry entry]
  (let [change (.. entry getChangeType name)]
    (cond
      (= change "ADD") :add
      (= change "MODIFY") :edit
      (= change "DELETE") :delete
      (= change "COPY") :copy)))

(defn- diff-formatter-for-changes
  [^Git repo]
  (doto
    (DiffFormatter. DisabledOutputStream/INSTANCE)
    (.setRepository (.getRepository repo))
    (.setDiffComparator RawTextComparator/DEFAULT)
    (.setDetectRenames false)))

(defn- byte-array-diff-formatter-for-changes
  [^Git repo ^ByteArrayOutputStream out]
  (doto
      (new DiffFormatter out)
    (.setRepository (.getRepository repo))
    (.setDiffComparator RawTextComparator/DEFAULT)))

(defn- changed-files-in-first-commit
  [^Git repo ^RevCommit rev-commit]
  (let [tree-walk (new-tree-walk repo rev-commit)
        changes (transient [])]
    (while (.next tree-walk)
      (conj! changes [(util/normalize-path (.getPathString tree-walk)) :add]))
    (persistent! changes)))

(defn- parse-diff-entry
  [^DiffEntry entry]
  (let [old-path (util/normalize-path (.getOldPath entry))
        new-path (util/normalize-path (.getNewPath entry))
        change-kind (change-kind entry)]
    (cond
      (= old-path new-path)   [new-path change-kind]
      (= old-path "dev/null") [new-path change-kind]
      (= new-path "dev/null") [old-path change-kind]
      :else [old-path change-kind new-path])))

(defn rev-list-for
  ([^Git repo ^RevWalk rev-walk ^RefDirectory$LooseRef object]
    (.reset rev-walk)
    (.markStart rev-walk (.lookupCommit ^RevWalk rev-walk ^AnyObjectId (.getObjectId object)))
    (.toArray
      (doto (RevCommitList.)
        (.source rev-walk)
        (.fillTo Integer/MAX_VALUE)))))

(defn- add-branch-to-map
  [^Git repo ^RevWalk rev-walk branch ^HashMap m]
  (let [^"[Ljava.lang.Object;" revs (rev-list-for repo rev-walk branch)]
    (dotimes [i (alength revs)]
      (let [c (aget revs i)]
        (.put m c (conj (or (.get m c) []) branch))))))

(defn build-commit-map
  "Build commit map, which is a map of commit IDs to the list of branches they are in."
  ([repo]
    (build-commit-map repo (new-rev-walk repo)))
  ([^Git repo ^RevWalk rev-walk]
    (let [^HashMap m (HashMap.)]
      (loop [[branch & branches] (vals (get-refs repo "refs/heads/"))]
        (add-branch-to-map repo rev-walk branch m)
        (if branches
          (recur branches)
          m)))))
