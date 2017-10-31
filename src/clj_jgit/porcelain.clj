(ns clj-jgit.porcelain
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-jgit.util :as util]
            [clj-jgit.internal :refer :all])
  (:import [java.io FileNotFoundException File]
           [org.eclipse.jgit.lib RepositoryBuilder AnyObjectId]
           [org.eclipse.jgit.api Git InitCommand StatusCommand AddCommand
            ListBranchCommand PullCommand MergeCommand LogCommand
            LsRemoteCommand Status ResetCommand$ResetType
            FetchCommand]
           [org.eclipse.jgit.submodule SubmoduleWalk]
           [com.jcraft.jsch Session JSch]
           [org.eclipse.jgit.transport FetchResult JschConfigSessionFactory
            OpenSshConfig$Host SshSessionFactory]
           [org.eclipse.jgit.util FS]
           [org.eclipse.jgit.merge MergeStrategy]
           [clojure.lang Keyword]
           [java.util List]
           [org.eclipse.jgit.api.errors JGitInternalException]
           [org.eclipse.jgit.transport UsernamePasswordCredentialsProvider URIish]
           [org.eclipse.jgit.treewalk TreeWalk]
           [java.nio.charset StandardCharsets]
           [org.eclipse.jgit.revwalk RevWalk RevCommit]))

(declare log-builder)

(defmulti discover-repo "Discover a Git repository in a path." type)

(defmethod discover-repo File
  [^File file]
  (discover-repo (.getPath file)))

(defmethod discover-repo String
  [^String path]
  (let [with-git (io/as-file (str path "/.git"))
        bare (io/as-file (str path "/refs"))]
    (cond
     (.endsWith path ".git") (io/as-file path)
     (.exists with-git) with-git
     (.exists bare) (io/as-file path))))

(def ^:dynamic *credentials* nil)
(def ^:dynamic *ssh-identity-name* "")
(def ^:dynamic *ssh-prvkey* nil)
(def ^:dynamic *ssh-pubkey* nil)
(def ^:dynamic *ssh-passphrase* "")
(def ^:dynamic *ssh-identities* [])
(def ^:dynamic *ssh-exclusive-identity* false)
(def ^:dynamic *ssh-session-config* {"StrictHostKeyChecking" "no"
                                     "UserKnownHostsFile" "/dev/null"})

(defmacro with-credentials
  [login password & body]
  `(binding [*credentials* (UsernamePasswordCredentialsProvider. ~login ~password)]
     ~@body))

(defn load-repo
  "Given a path (either to the parent folder or to the `.git` folder itself), load the Git repository"
  ^org.eclipse.jgit.api.Git [path]
  (if-let [git-dir (discover-repo path)]
    (-> (RepositoryBuilder.)
        (.setGitDir git-dir)
        (.readEnvironment)
        (.findGitDir)
        (.build)
        (Git.))
    (throw
     (FileNotFoundException. (str "The Git repository at '" path "' could not be located.")))))

(defmacro with-repo
  "Load Git repository at `path` and bind it to `repo`, then evaluate `body`.
  Also provides a fresh `rev-walk` instance for `repo` which is closed on form exit."
  [path & body]
  `(let [~'repo (load-repo ~path)
         ~'rev-walk (new-rev-walk ~'repo)]
     (try ~@body
      (finally (close-rev-walk ~'rev-walk)))))

(defn git-add
  "The `file-pattern` is either a single file name (exact, not a pattern) or the name of a directory. If a directory is supplied, all files within that directory will be added. If `only-update?` is set to `true`, only files which are already part of the index will have their changes staged (i.e. no previously untracked files will be added to the index)."
  ([^Git repo file-pattern]
     (git-add repo file-pattern false nil))
  ([^Git repo file-pattern only-update?]
     (git-add repo file-pattern only-update? nil))
  ([^Git repo file-pattern only-update? working-tree-iterator]
     (-> repo
         (.add)
         (.addFilepattern file-pattern)
         (.setUpdate only-update?)
         (.setWorkingTreeIterator working-tree-iterator)
         (.call))))

(defn git-branch-list
  "Get a list of branches in the Git repo. Return the default objects generated by the JGit API."
  ([^Git repo]
     (git-branch-list repo :local))
  ([^Git repo opt]
     (let [opt-val {:all org.eclipse.jgit.api.ListBranchCommand$ListMode/ALL
                    :remote org.eclipse.jgit.api.ListBranchCommand$ListMode/REMOTE}
           branches (if (= opt :local)
                      (-> repo
                          (.branchList)
                          (.call))
                      (-> repo
                          (.branchList)
                          (.setListMode (opt opt-val))
                          (.call)))]
       (seq branches))))

(defn git-branch-current*
  [^Git repo]
  (.getFullBranch (.getRepository repo)))

(defn git-branch-current
  "The current branch of the git repo"
  [^Git repo]
  (str/replace (git-branch-current* repo) #"^refs/heads/" ""))

(defn git-branch-attached?
  "Is the current repo on a branch (true) or in a detached HEAD state?"
  [^Git repo]
  (not (nil? (re-find #"^refs/heads/" (git-branch-current* repo)))))

(defn git-branch-create
  "Create a new branch in the Git repository."
  ([^Git repo branch-name]
     (git-branch-create repo branch-name false nil))
  ([^Git repo branch-name force?]
     (git-branch-create repo branch-name force? nil))
  ([^Git repo branch-name force? ^String start-point]
     (if (nil? start-point)
       (-> repo
           (.branchCreate)
           (.setName branch-name)
           (.setForce force?)
           (.call))
       (-> repo
           (.branchCreate)
           (.setName branch-name)
           (.setForce force?)
           (.setStartPoint start-point)
           (.call)))))

(defn git-branch-delete
  ([^Git repo branch-names]
     (git-branch-delete repo branch-names false))
  ([^Git repo branch-names force?]
     (-> repo
         (.branchDelete)
         (.setBranchNames (into-array String branch-names))
         (.setForce force?)
         (.call))))

(defn git-checkout
  ([^Git repo branch-name]
     (git-checkout repo branch-name false false nil))
  ([^Git repo branch-name create-branch?]
     (git-checkout repo branch-name create-branch? false nil))
  ([^Git repo branch-name create-branch? force?]
     (git-checkout repo branch-name create-branch? force? nil))
  ([^Git repo branch-name create-branch? force? ^String start-point]
     (if (nil? start-point)
       (-> repo
           (.checkout)
           (.setName branch-name)
           (.setCreateBranch create-branch?)
           (.setForce force?)
           (.call))
       (-> repo
           (.checkout)
           (.setName branch-name)
           (.setCreateBranch create-branch?)
           (.setForce force?)
           (.setStartPoint start-point)
           (.call)))))

(declare git-cherry-pick)

(defn clone-cmd [uri]
  (-> (Git/cloneRepository)
      (.setCredentialsProvider *credentials*)
      (.setURI uri)))

(defn git-clone
  ([uri]
     (git-clone uri (util/name-from-uri uri) "origin" "master" false))
  ([uri local-dir]
     (git-clone uri local-dir "origin" "master" false))
  ([uri local-dir remote-name]
     (git-clone uri local-dir remote-name "master" false))
  ([uri local-dir remote-name local-branch]
     (git-clone uri local-dir remote-name local-branch false))
  ([uri local-dir remote-name local-branch bare?]
     (-> (clone-cmd uri)
         (.setDirectory (io/as-file local-dir))
         (.setRemote remote-name)
         (.setBranch local-branch)
         (.setBare bare?)
         (.call))))

(defn git-clone2
  [uri {:as options
        :keys [path remote-name branch-name bare clone-all-branches]
        :or {path (util/name-from-uri uri)
             remote-name "origin"
             branch-name "master"
             bare false
             clone-all-branches true}}]
  (doto (clone-cmd uri)
    (.setDirectory (io/as-file path))
    (.setRemote remote-name)
    (.setBranch branch-name)
    (.setBare bare)
    (.setCloneAllBranches clone-all-branches)
    (.call)))

(declare git-fetch git-merge)

(defn git-clone-full
  "Clone, fetch the master branch and merge its latest commit"
  ([uri]
     (git-clone-full uri (util/name-from-uri uri) "origin" "master" false))
  ([uri local-dir]
     (git-clone-full uri local-dir "origin" "master" false))
  ([uri local-dir remote-name]
     (git-clone-full uri local-dir remote-name "master" false))
  ([uri local-dir remote-name local-branch]
     (git-clone-full uri local-dir remote-name local-branch false))
  ([uri local-dir remote-name local-branch bare?]
     (let [new-repo (-> (clone-cmd uri)
                        (.setDirectory (io/as-file local-dir))
                        (.setRemote remote-name)
                        (.setBranch local-branch)
                        (.setBare bare?)
                        (.call))
           fetch-result ^FetchResult (git-fetch new-repo)
           first-ref (first (.getAdvertisedRefs fetch-result))
           merge-result (when first-ref
                          (git-merge new-repo first-ref))]
       {:repo new-repo,
        :fetch-result fetch-result,
        :merge-result  merge-result})))

(defn git-commit
  "Commit staged changes."
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.call)))
  ([^Git repo message {:keys [author-name author-email]} {:keys [committer-name committer-email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor author-name author-email)
         (.setCommitter committer-name committer-email)
         (.call))))

(defn git-commit-amend
  "Amend previous commit with staged changes."
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAmend true)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setAmend true)
         (.call)))
  ([^Git repo message {:keys [name email]} {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.setAmend true)
         (.call))))


(defn git-add-and-commit
  "This is the `git commit -a...` command"
  ([^Git repo message]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAll true)
         (.call)))
  ([^Git repo message {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setAll true)
         (.call)))
  ([^Git repo message {:keys [name email]} {:keys [name email]}]
     (-> repo
         (.commit)
         (.setMessage message)
         (.setAuthor name email)
         (.setCommitter name email)
         (.setAll true)
         (.call))))

(defn fetch-cmd [^Git repo]
  (-> repo
      (.fetch)
      (.setCredentialsProvider *credentials*)))

(defn git-fetch
  "Fetch changes from upstream repository."
  (^org.eclipse.jgit.transport.FetchResult [^Git repo]
                                           (-> (fetch-cmd repo)
                                               (.call)))
  (^org.eclipse.jgit.transport.FetchResult [^Git repo remote]
                                           (-> (fetch-cmd repo)
                                               (.setRemote remote)
                                               (.call)))
  (^org.eclipse.jgit.transport.FetchResult [^Git repo remote & refspecs]
                                           (let [^FetchCommand cmd (fetch-cmd repo)]
                                             (.setRefSpecs cmd ^List (map ref-spec refspecs))
                                             (.setRemote cmd remote)
                                             (.call cmd))))

(defn git-fetch-all
  "Fetch all refs from upstream repository"
  ([^Git repo]
     (git-fetch-all repo "origin"))
  ([^Git repo remote]
     (git-fetch repo remote "+refs/tags/*:refs/tags/*" "+refs/heads/*:refs/heads/*")))

(defn git-init
  "Initialize and return a new Git repository, if no options are passed a non-bare repo is created at user.dir"
  ([] (git-init "."))
  ([^String target-dir] (git-init target-dir false))
  ([^String target-dir ^Boolean bare]
    (-> (InitCommand.)
        (.setDirectory (io/as-file target-dir))
        (.setBare bare)
        (.call))))

(defn git-remote-add
  "Add a new remote to given repo and return the JGit RemoteAddCommand instance"
  [^Git repo name ^String uri]
  (doto (.remoteAdd repo)
        (.setName name)
        (.setUri (URIish. uri))
        (.call)))

(defn git-remote-remove
  "Remove a remote with given name from repo and return the JGit RemoteRemoveCommand instance"
  [^Git repo name]
  (doto (.remoteRemove repo)
        (.setName name)
        (.call)))

(defn git-remote-list
  "Return a seq of vectors with format [name [^URIish ..]] representing all configured remotes for given repo"
  [^Git repo]
  (->> repo
       .remoteList
       .call
       (map (fn [r]
              [(.getName r) (.getURIs r)]))))

(defn git-log
  "Return a seq of all commit objects"
  ([^Git repo]
     (seq (-> repo
              (.log)
              (.call))))
  ([^Git repo hash]
     (seq (-> repo
              (.log)
              (.add (resolve-object hash repo))
              (.call))))
  ([^Git repo hash-a hash-b]
     (seq (-> repo
              ^LogCommand (log-builder hash-a hash-b)
              (.call)))))

(defn- log-builder
  "Builds a log command object for a range of commit-ish names"
  ^org.eclipse.jgit.api.LogCommand [^Git repo hash-a hash-b]
  (let [log (.log repo)]
    (if (= hash-a "0000000000000000000000000000000000000000")
      (.add log (resolve-object hash-b repo))
      (.addRange log (resolve-object hash-a repo) (resolve-object hash-b repo)))))

(def merge-strategies
  {:ours MergeStrategy/OURS
   :resolve MergeStrategy/RESOLVE
   :simple-two-way MergeStrategy/SIMPLE_TWO_WAY_IN_CORE
   :theirs MergeStrategy/THEIRS})

(defn git-merge
  "Merge ref in current branch."
  ([^Git repo commit-ref]
     (let [commit-obj (resolve-object commit-ref repo)]
       (-> repo
           (.merge)
           ^MergeCommand (.include commit-obj)
           (.call))))
  ([^Git repo commit-ref ^Keyword strategy]
     (let [commit-obj (resolve-object commit-ref repo)
           strategy-obj ^MergeStrategy (merge-strategies strategy)]
       (-> repo
           (.merge)
           ^MergeCommand (.include commit-obj)
           ^MergeCommand (.setStrategy strategy-obj)
           (.call)))))

(defn git-pull
  "Pull from a remote.

   Options:
     :remote    The remote to use. (default: \"origin\")

  Example usage:

  (gitp/with-identity {:name \"~/.ssh/id_rsa\" :exclusive true}
    (gitp/git-pull repo :remote \"my-remote\"))
  "
  ([^Git repo & {:keys [remote]}]
    (as-> repo x
      (.pull x)
      (.setRemote x (or remote "origin"))
      (.call x))))

(defn git-push
  "Push current branch to a remote.

   Options:
     :remote    The remote to use. (default: \"origin\").
     :tags      Also push tags to the remote. (default: false)

   Example usage:
     (gitp/with-identity {:name \"~/.ssh/id_rsa\" :exclusive true}
       (gitp/git-push repo :remote \"daveyarwood\" :tags true))
  "
  ([^Git repo & {:keys [remote tags]}]
    (as-> repo x
      (.push x)
      (.setRemote x (or remote "origin"))
      (if tags (.setPushTags x) x)
      (.call x))))

(defn git-rebase [])
(defn git-revert [])
(defn git-rm
  [^Git repo file-pattern]
  (-> repo
      (.rm)
      (.addFilepattern file-pattern)
      (.call)))

(defn git-status
  "Return the status of the Git repository. Opts will return individual aspects of the status, and can be specified as `:added`, `:changed`, `:missing`, `:modified`, `:removed`, or `:untracked`."
  [^Git repo & fields]
  (let [status (.. repo status call)
        status-fns {:added     #(.getAdded ^Status %)
                    :changed   #(.getChanged ^Status %)
                    :missing   #(.getMissing ^Status %)
                    :modified  #(.getModified ^Status %)
                    :removed   #(.getRemoved ^Status %)
                    :untracked #(.getUntracked ^Status %)}]
    (if-not (seq fields)
      (apply merge (for [[k f] status-fns]
                     {k (into #{} (f status))}))
      (apply merge (for [field fields]
                     {field (into #{} ((field status-fns) status))})))))

(defn git-tag-create
  "Creates an annotated tag with the provided name and (optional) message."
  [^Git repo tag-name & [tag-message]]
  (as-> repo x
    (.tag x)
    (.setAnnotated x true)
    (.setName x tag-name)
    (if tag-message (.setMessage x tag-message) x)
    (.call x)))

(defn git-tag-delete
  "Deletes a tag with the provided name(s)."
  [^Git repo & tag-names]
  (-> repo
      (.tagDelete)
      (.setTags (into-array tag-names))
      (.call)))

(defn git-tag-list
  "Lists the tags in a repo, returning them as a seq of strings."
  [^Git repo]
  (->> repo
       (.tagList)
       (.call)
       (map #(->> % .getName (re-matches #"refs/tags/(.*)") second))))

(defn ls-remote-cmd [^Git repo]
  (-> repo
      (.lsRemote)
      (.setCredentialsProvider *credentials*)))

(defn git-ls-remote
  ([^Git repo]
     (-> (ls-remote-cmd repo)
         (.call)))
  ([^Git repo remote]
     (-> (ls-remote-cmd repo)
         (.setRemote remote)
         (.call)))
  ([^Git repo remote opts]
     (-> (ls-remote-cmd repo)
         (.setRemote remote)
         (.setHeads (:heads opts false))
         (.setTags (:tags opts false))
         (.call))))

(def reset-modes
  {:hard ResetCommand$ResetType/HARD
   :keep ResetCommand$ResetType/KEEP
   :merge ResetCommand$ResetType/MERGE
   :mixed ResetCommand$ResetType/MIXED
   :soft ResetCommand$ResetType/SOFT})

(defn git-reset
  ([^Git repo ref]
     (git-reset repo ref :mixed))
  ([^Git repo ref mode-sym]
     (-> repo .reset
         (.setRef ref)
         (.setMode ^ResetCommand$ResetType (reset-modes mode-sym))
         (.call))))

(def jsch-factory
  (proxy [JschConfigSessionFactory] []
    (configure [hc session]
      (let [jsch (.getJSch this hc FS/DETECTED)]
        (doseq [[key val] *ssh-session-config*]
          (.setConfig session key val))
        (when *ssh-exclusive-identity*
          (.removeAllIdentity jsch))
        (when (and *ssh-prvkey* *ssh-pubkey* *ssh-passphrase*)
          (.addIdentity jsch *ssh-identity-name*
                        (.getBytes *ssh-prvkey* )
                        (.getBytes *ssh-pubkey*)
                        (.getBytes *ssh-passphrase*)))
        (when (and *ssh-identity-name* (not (and *ssh-prvkey* *ssh-pubkey*)))
          (if (empty? *ssh-passphrase*)
            (.addIdentity jsch *ssh-identity-name*)
            (.addIdentity jsch *ssh-identity-name* (.getBytes *ssh-passphrase*))))
        (doseq [{:keys [name private-key public-key passphrase]
                 :or {passphrase ""}} *ssh-identities*]
          (.addIdentity jsch
                        (or name (str "key-" (.hashCode private-key)))
                        (.getBytes private-key)
                        (.getBytes public-key)
                        (.getBytes passphrase)))))))

(SshSessionFactory/setInstance jsch-factory)

(defmacro with-identity
  "Creates an identity to use for SSH authentication."
  [config & body]
  `(let [name# (get ~config :name "jgit-identity")
         private# (get ~config :private)
         public# (get ~config :public)
         passphrase# (get ~config :passphrase "")
         options# (get ~config :options *ssh-session-config*)
         exclusive# (get ~config :exclusive false)
         identities# (get ~config :identities)]
     (binding [*ssh-identity-name* name#
               *ssh-prvkey* private#
               *ssh-pubkey* public#
               *ssh-identities* identities#
               *ssh-passphrase* passphrase#
               *ssh-session-config* options#
               *ssh-exclusive-identity* exclusive#]
       ~@body)))

(defn submodule-walk
  ([repo]
     (->> (submodule-walk (.getRepository repo) 0)
          (flatten)
          (filter identity)
          (map #(Git/wrap %))))
  ([repo level]
     (when (< level 3)
       (let [gen (SubmoduleWalk/forIndex repo)
             repos (transient [])]
         (while (.next gen)
           (when-let [subm (.getRepository gen)]
             (conj! repos subm)
             (conj! repos (submodule-walk subm (inc level)))))
         (->> (persistent! repos)
              (flatten))))))

(defn git-submodule-fetch
  [repo]
  (doseq [subm (submodule-walk repo)]
    (git-fetch-all subm)))

(defn submodule-update-cmd [^Git repo]
  (-> repo
      (.submoduleUpdate)
      (.setCredentialsProvider *credentials*)))

(defn git-submodule-update
  "Fetch each submodule repo and update them."
  ([repo]
   (git-submodule-fetch repo)
   (-> (submodule-update-cmd repo)
       (.call))
   (doseq [subm (submodule-walk repo)]
     (-> (submodule-update-cmd subm)
         (.call))))
  ([repo path]
   (git-submodule-fetch repo)
   (-> (submodule-update-cmd repo)
       (.call))
   (doseq [subm (submodule-walk repo)]
     (-> (submodule-update-cmd subm)
         (.addPath path)
         (.call)))))

(defn git-submodule-sync
  ([repo]
     (.. repo submoduleSync call)
     (doseq [subm (submodule-walk repo)]
       (.. subm submoduleSync call)))
  ([repo path]
     (.. repo submoduleSync call)
     (doseq [subm (submodule-walk repo)]
       (-> subm
           (.submoduleSync)
           (.addPath path)
           (.call)))))

(defn git-submodule-init
  ([repo]
     (.. repo submoduleInit call)
     (doseq [subm (submodule-walk repo)]
       (.. subm submoduleInit call)))
  ([repo path]
     (.. repo submoduleInit call)
     (doseq [subm (submodule-walk repo)]
       (-> subm
           (.submoduleInit)
           (.addPath path)
           (.call)))))

(defn git-submodule-add
  [repo uri path]
  (-> repo
      (.submoduleAdd)
      (.setURI uri)
      (.setPath path)
      (.call)))

;;
;; Git Stash Commands
;;

(defn git-create-stash
  [^Git repo]
  (-> repo
      .stashCreate
      .call))

(defn git-apply-stash
  ([^Git repo]
     (git-apply-stash repo nil))
  ([^Git repo ^String ref-id]
     (-> repo
         .stashApply
         (.setStashRef ref-id)
         .call)))

(defn git-list-stash
  [^Git repo]
  (-> repo
      .stashList
      .call))

(defn git-drop-stash
  ([^Git repo]
     (-> repo
         .stashDrop
         .call))
  ([^Git repo ^String ref-id]
     (let [stashes (git-list-stash repo)
           target (first (filter #(= ref-id (second %))
                                 (map-indexed #(vector %1 (.getName %2)) stashes)))]
       (when-not (nil? target)
         (-> repo
             .stashDrop
             (.setStashRef (first target))
             .call)))))

(defn git-pop-stash
  ([^Git repo]
     (git-apply-stash repo)
     (git-drop-stash repo))
  ([^Git repo ^String ref-id]
     (git-apply-stash repo ref-id)
     (git-drop-stash repo ref-id)))

(defn git-clean
  "Remove untracked files from the working tree.

  clean-dirs? - true/false - remove untracked directories
  force-dirs? - true/false - force removal of non-empty untracked directories
  paths - set of paths to cleanup
  ignore? - true/false - ignore paths from .gitignore"
  [^Git repo & {:keys [clean-dirs? ignore? paths force-dirs?]
                :or {clean-dirs? false
                     force-dirs? false
                     ignore? true
                     paths #{}}}]
  (letfn [(clean-loop [retries]
            (try
              (-> repo
                  (.clean)
                  (.setCleanDirectories clean-dirs?)
                  (.setIgnore ignore?)
                  (.setPaths paths)
                  (.call))
              (catch JGitInternalException e
                (if-not force-dirs?
                  (throw e)
                  (when-let [dir-path (->> (.getMessage e)
                                           (re-seq #"^Could not delete file (.*)$")
                                           (first)
                                           (last))]
                    (if (retries dir-path)
                      (throw e)
                      (util/recursive-delete-file dir-path true))
                    #(clean-loop (conj retries dir-path)))))))]
    (trampoline clean-loop #{})))

(defn blame-result
  [blame]
  (.computeAll blame)
  (letfn [(blame-line [num]
            (when-let [commit (try
                                (.getSourceCommit blame num)
                                (catch ArrayIndexOutOfBoundsException _ nil))]
              {:author (util/person-ident (.getSourceAuthor blame num))
               :commit commit
               :committer (util/person-ident (.getSourceCommitter blame num))
               :line (.getSourceLine blame num)
               :line-contents (-> blame .getResultContents (.getString num))
               :source-path (.getSourcePath blame num)}))
          (blame-seq [num]
            (when-let [cur (blame-line num)]
              (cons cur
                    (lazy-seq (blame-seq (inc num))))))]
    (blame-seq 0)))

(defn git-blame
  ([^Git repo ^String path]
     (git-blame repo path false))
  ([^Git repo ^String path ^Boolean follow-renames?]
     (-> repo
         .blame
         (.setFilePath path)
         (.setFollowFileRenames follow-renames?)
         .call
         blame-result))
  ([^Git repo ^String path ^Boolean follow-renames? ^AnyObjectId start-commit]
     (-> repo
         .blame
         (.setFilePath path)
         (.setFollowFileRenames follow-renames?)
         (.setStartCommit start-commit)
         .call
         blame-result)))

(defn get-blob-id
  [repo commit path]
  (let [tree-walk (TreeWalk/forPath (.getRepository repo) path (.getTree commit))]
    (when tree-walk
      (.getObjectId tree-walk 0))))

(defn get-blob
  [repo commit path]
  (when-let [blob-id (get-blob-id repo commit path)]
    (.getName blob-id)))

(defn git-notes
  "Return the list of notes object for a given ref (defaults to 'commits')."
  ([^Git repo ^String ref]
    (-> repo
        .notesList
        (.setNotesRef (str "refs/notes/" ref))
        .call))
  ([^Git repo]
    (git-notes repo "commits")))

(defn git-notes-show
  "Return notes strings for the given ref (defaults to 'commits')."
  ([^Git repo ^String ref]
    (let [repository (-> repo .getRepository)]
      (->> (git-notes repo ref)
           (map #(String. (.getBytes (.open repository (.getData %))) (StandardCharsets/UTF_8)))
           (map #(str/split % #"\n"))
           (first))))
  ([^Git repo]
    (git-notes-show repo "commits")))

(defn get-head-commit-object "Return HEAD RevCommit instance" [^Git repo ]
  (let [repository (-> repo .getRepository)
        head-id (-> repository (.resolve "HEAD"))]
    (-> (RevWalk. repository) (.parseCommit head-id))))

(defn git-notes-add
  "Add note for a given commit (defaults to HEAD) with the given ref (defaults to 'commits')
  It overwrites existing note for the commit"
  ([^Git repo ^String message ^String ref ^RevCommit commit]
    (-> repo
      .notesAdd
      (.setMessage message)
      (.setNotesRef (str "refs/notes/" ref))
      (.setObjectId commit)
      .call))
  ([^Git repo ^String message ^String ref]
    (->> repo
         get-head-commit-object
         (git-notes-add repo message ref)))
  ([^Git repo ^String message]
    (git-notes-add repo message "commits")))

(defn git-notes-append
  "Append note for a given commit (defaults to HEAD) with the given ref (defaults to 'commits')
  It concatenates notes with \n char"
  ([^Git repo ^String message ^String ref ^RevCommit commit]
    (as-> (git-notes-show repo ref) $
          (conj $ message)
          (str/join "\n" $)
          (git-notes-add repo $ ref commit)))
  ([^Git repo ^String message ^String ref]
    (->> repo
         get-head-commit-object
         (git-notes-append repo message ref)))
  ([^Git repo ^String message]
    (git-notes-append repo message "commits")))