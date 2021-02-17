(ns clj-jgit.porcelain
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-jgit.internal :refer :all]
            [clj-jgit.util :as util :refer [seq?! doseq-cmd-fn!]])
  (:import (java.io File FileNotFoundException IOException)
           (java.nio.charset StandardCharsets)
           (java.security GeneralSecurityException)
           (java.util List)
           (org.eclipse.jgit.api Git InitCommand StatusCommand AddCommand PullCommand MergeCommand LogCommand
                                 LsRemoteCommand Status ResetCommand$ResetType FetchCommand PushCommand CloneCommand
                                 RmCommand ResetCommand SubmoduleUpdateCommand SubmoduleSyncCommand SubmoduleInitCommand
                                 StashCreateCommand StashApplyCommand BlameCommand ListBranchCommand$ListMode
                                 CreateBranchCommand$SetupUpstreamMode CheckoutCommand$Stage
                                 CommitCommand MergeCommand$FastForwardMode RevertCommand CreateBranchCommand
                                 CheckoutCommand TransportConfigCallback TransportCommand ListBranchCommand TagCommand
                                 CleanCommand DeleteTagCommand)
           (org.eclipse.jgit.blame BlameResult)
           (org.eclipse.jgit.diff DiffAlgorithm$SupportedAlgorithm)
           (org.eclipse.jgit.lib RepositoryBuilder AnyObjectId PersonIdent BranchConfig$BranchRebaseMode ObjectId
                                 SubmoduleConfig$FetchRecurseSubmodulesMode Ref Repository StoredConfig GpgConfig)
           (org.eclipse.jgit.merge MergeStrategy)
           (org.eclipse.jgit.notes Note)
           (org.eclipse.jgit.revwalk RevCommit)
           (org.eclipse.jgit.submodule SubmoduleWalk)
           (org.eclipse.jgit.transport.sshd SshdSessionFactory DefaultProxyDataFactory JGitKeyCache KeyPasswordProvider)
           (org.eclipse.jgit.transport FetchResult UsernamePasswordCredentialsProvider URIish RefSpec RefLeaseSpec TagOpt
                                       RemoteConfig CredentialsProvider CredentialItem$CharArrayType
                                       CredentialItem$YesNoType SshTransport)
           (org.eclipse.jgit.treewalk TreeWalk)))

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

(def ^:dynamic *cred-provider* nil)
(def ^:dynamic *ssh-key-dir* (str (System/getProperty "user.home") (File/separator) ".ssh"))
(def ^:dynamic *ssh-key-name* ["id_rsa", "id_dsa", "id_ecdsa", "id_ed25519"])
(def ^:dynamic *ssh-key-passphrase* "")
(def ^:dynamic *known-hosts-file* ["known_hosts" "known_hosts2"])

(def ^CredentialsProvider trust-any-provider
  "Basic `CredentialsProvider` instance that accepts and adds any unknown server keys to `known_hosts` file."
  (proxy [CredentialsProvider] []
    (supports [items]
      (if (some? (->> items
                      (filter #(when (instance? CredentialItem$YesNoType %) true))
                      first))
        true
        false))
    (get [uri items]
      (let [^CredentialItem$YesNoType yesno-item (->> items
                                                      (filter #(when (instance? CredentialItem$YesNoType %) true))
                                                      first)]
        (if (some? yesno-item)
          (do (.setValue yesno-item true) true)
          false)))
    (isInteractive []
      false)))

(defn user-pass-provider
  "Create a new `UsernamePasswordCredentialsProvider` instance for given `login` and `password`.

    Options:

    :trust-all?  Accept and add any server key not present in known_hosts file.
                 (default: false)
  "
  ^UsernamePasswordCredentialsProvider [login password & {:keys [trust-all?] :or {trust-all? false}}]
  (if trust-all?
    (proxy [UsernamePasswordCredentialsProvider] [^String login ^String password]
      (supports [items]
        (if (some? (->> items
                        (filter #(when (instance? CredentialItem$YesNoType %) true))
                        first))
          true
          false))
      (get [uri items]
        (let [^CredentialItem$YesNoType yesno-item (->> items
                                                        (filter #(when (instance? CredentialItem$YesNoType %) true))
                                                        first)]
          (if (some? yesno-item)
            (do (.setValue yesno-item true) true)
            false)))
      (isInteractive []
        false))
    (UsernamePasswordCredentialsProvider. ^String login ^String password)))

(defn key-pass-provider
  "Create a new `KeyPasswordProvider` instance for given `key-pw`."
  ^KeyPasswordProvider [key-pw]
  (reify KeyPasswordProvider
    (getPassphrase [_ uri attempt]
      (char-array key-pw))
    (setAttempts [_ attempts]
      true)
    (getAttempts [_]
      1)
    (keyLoaded [_ uri attempt ex]
      (when ex (throw (GeneralSecurityException. "Key passphrase mismatch")))
      ; never retry on error
      false)))

(def ^SshdSessionFactory sshd-factory
  "Handle SSH transport configuration."
  (proxy [SshdSessionFactory] [(JGitKeyCache.) (DefaultProxyDataFactory.)]
    (getSshDirectory []
      (let [ssh-dir (io/as-file *ssh-key-dir*)]
        (if (.isAbsolute ssh-dir)
          ssh-dir
          (.getAbsoluteFile ssh-dir))))
    (createKeyPasswordProvider [^CredentialsProvider provider]
      (key-pass-provider *ssh-key-passphrase*))
    (getDefaultKnownHostsFiles [^File ssh-dir]
      (let [ssh-dir (.toPath ssh-dir)
            kh-files (->> (seq?! *known-hosts-file*)
                          (map #(.resolve ssh-dir ^String %)))]
        (if (seq kh-files)
          kh-files
          (throw (IOException. (str "Couldn't find any known hosts file(s), tried: " *known-hosts-file*
                                    " in " *ssh-key-dir*))))))
    (getDefaultIdentities [^File ssh-dir]
      (let [key-files (->> (seq?! *ssh-key-name*)
                           (map #(io/file ssh-dir %))
                           (filter #(.exists ^File %))
                           (map #(.toPath ^File %)))]
        (if (seq key-files)
          key-files
          (throw (IOException. (str "Couldn't find any key file(s), tried: " *ssh-key-name*
                                    " in " *ssh-key-dir*))))))))

(-> (Runtime/getRuntime) (.addShutdownHook (Thread. #(.close sshd-factory))))

(def ^TransportConfigCallback transport-callback
  "Default `TransportConfigCallback`."
  (reify ^TransportConfigCallback TransportConfigCallback
    (configure [_ transport]
      (when (instance? SshTransport transport)
        (.setSshSessionFactory ^SshTransport transport sshd-factory)))))

(def ^:dynamic *transport-callback* transport-callback)

(defmacro with-credentials
  "Use given `config` map for all commands in body that require credentials based authentication.

    Options:

    :login          User name.
    :pw             User password.
    :trust-all?     If true any unknown server key is accepted and added to configured
                    `known_hosts` file. (default: false)
    :cred-provider  Use custom credentials provider. (default: nil = use default)
  "
  [config & body]
  `(let [user-name#      (get ~config :login "")
         user-pw#        (get ~config :pw "")
         key-dir#        (get ~config :key-dir *ssh-key-dir*)
         trust-all?#     (get ~config :trust-all? false)
         cred-provider#  (get ~config :credentials *cred-provider*)]
    (binding [*ssh-key-dir*    key-dir#
              *transport-callback* nil
              *cred-provider*  (if (some? cred-provider#)
                                 cred-provider#
                                 (user-pass-provider user-name# user-pw# :trust-all? trust-all?#))]
       ~@body)))

(defmacro with-identity
  "Use given `config` map for all commands in body that require key based authentication.

    Options:

    :name               A string or seq of strings with private key name(s). The public
                        key must have the same name with an additional \".pub\" postfix.
                        (default: [\"id_rsa\", \"id_dsa\", \"id_ecdsa\", \"id_ed25519\"])
    :pw                 Optional password for encrypted keys. (default: nil)
    :key-dir            Path to ssl keys and known_hosts file. (default: ~/.ssh)
    :trust-any-host?    If true any unknown server key is accepted and added to
                        known_hosts file. (default: false)
    :cred-provider      Use custom credentials provider. (default: nil = use default)
    :transport-callback Use custom TransportConfigCallback
  "
  [config & body]
  `(let [key-name#      (get ~config :name *ssh-key-name*)
         key-pw#        (get ~config :pw *ssh-key-passphrase*)
         key-dir#       (get ~config :key-dir *ssh-key-dir*)
         trust-all?#    (get ~config :trust-all? false)
         cred-provider# (get ~config :cred-provider *cred-provider*)
         transport-cb#  (get ~config :transport-callback *transport-callback*)]
     (binding [*ssh-key-name* key-name#
               *ssh-key-dir* key-dir#
               *ssh-key-passphrase* key-pw#
               *cred-provider* (if (and trust-all?# (nil? cred-provider#))
                                 trust-any-provider
                                 cred-provider#)
               *transport-callback* transport-cb#]
       ~@body)))

(defn load-repo
  "Given a `path` (either to the parent folder or to the `.git` folder itself), load the Git repository"
  ^Git [path]
  (if-let [git-dir (discover-repo path)]
    (-> (RepositoryBuilder.)
        (.setGitDir git-dir)
        (.readEnvironment)
        (.findGitDir)
        (.build)
        (Git.))
    (throw
      (FileNotFoundException. (str "The Git repository at '" path "' could not be located.")))))

(defn find-repo
  "Given a `path` located somewhere within a Git repository, return a path to the `.git` directory."
  ^Git [path & {:keys [ceiling-dirs]}]
  (let [builder (-> (RepositoryBuilder.)
                    (.addCeilingDirectories ceiling-dirs)
                    (.readEnvironment)
                    (.findGitDir path))]
    (if (.getGitDir builder)
      (-> builder
          (.build)
          (Git.))
      (throw
       (FileNotFoundException. (str "Could not load a git repository at '" path "'"
                                    " with ceiling dirs: " ceiling-dirs))))))

(defmacro with-repo
  "Load Git repository at `path` and bind it to `repo`, then evaluate `body`.
  Also provides a fresh `rev-walk` instance for `repo`."
  [path & body]
  `(with-open [~'repo (load-repo ~path)
               ~'rev-walk (new-rev-walk ~'repo)]
     ~@body))

(defn git-shutdown
  "Release all resources held by JGit process. Not mandatory, but prevents leaks when, for example, running in
  a webapp."
  [] (Git/shutdown))

(defn git-add
  "Add file contents to the index. `file-patterns` is either a String with a repository-relative path of the
  file/directory to be added or coll of Strings with paths. If a directory name is given all files in the directory are
  added recursively. Fileglobs (e.g. *.txt) are not yet supported.

    Options:

    :update?                If set to true, the command only matches file-patterns
                            against already tracked files in the index rather than
                            the working tree. That means that it will never stage new
                            files, but that it will stage modified new contents of
                            tracked files and that it will remove files from the index
                            if the corresponding files in the working tree have been
                            removed. (default: false)
    :working-tree-iterator  Set the WorkingTreeIterator to be used. If nil a standard
                            FileTreeIterator is used. (default: nil)
  "
  [^Git repo file-patterns & {:keys [update? working-tree-iterator]
                              :or   {update?               false
                                     working-tree-iterator nil}}]
  (as-> (.add repo) cmd
        ^AddCommand (doseq-cmd-fn! cmd #(.addFilepattern ^AddCommand %1 %2) file-patterns)
        (.setUpdate cmd update?)
        (if (some? working-tree-iterator)
          (.setWorkingTreeIterator cmd working-tree-iterator) cmd)
        (.call cmd)))

(defonce branch-list-modes
         {:all    ListBranchCommand$ListMode/ALL
          :remote ListBranchCommand$ListMode/REMOTE})

(defn strip-refs-head [s]
  (str/replace s #"^refs/heads/" ""))

(defn git-branch-list
  "Get a list of branch names for given `repo`. Returns a seq of strings.

    Options:

    :jgit?      If true returns a seq with JGit objects instead. (default: false)
    :list-mode  :all, :local or :remote (default: :local)
  "
  [^Git repo & {:keys [jgit? list-mode]
                :or   {jgit?     false
                       list-mode :local}}]
  (as-> (.branchList repo) ^ListBranchCommand cmd
        (if-not (= list-mode :local)
          (.setListMode cmd (list-mode branch-list-modes)) cmd)
        (.call cmd)
        (if jgit?
          cmd
          (map #(-> (.getName ^Ref %) strip-refs-head) cmd))))

(defn git-branch-current
  "The current branch name of given `repo`.

    Options:

    :jgit?      If true returns the unmodified output by JGit. (default: false)
  "
  [^Git repo & {:keys [jgit?] :or {jgit? false}}]
  (as-> (.getRepository repo) ^Repository r
        (.getFullBranch r)
        (if jgit?
          r
          (strip-refs-head r))))

(defn git-branch-attached?
  "Is the given `repo` on a branch (true) or in a detached HEAD state?"
  [^Git repo]
  (some? (re-find #"^refs/heads/" (git-branch-current repo :jgit? true))))

(defonce branch-upstream-modes
         {:no-track     CreateBranchCommand$SetupUpstreamMode/NOTRACK
          :set-upstream CreateBranchCommand$SetupUpstreamMode/SET_UPSTREAM
          :track        CreateBranchCommand$SetupUpstreamMode/TRACK})

(defn git-branch-create
  "Create a new local branch for given `repo`.

    Options:

    :force?         If true and the branch with the given name already exists, the
                    start-point of an existing branch will be set to a new :start-point;
                    if false, the existing branch will not be changed.
                    (default: false)
    :start-point    String that corresponds to the start-point option; if null, the
                    current HEAD will be used. (default: nil)
    :upstream-mode  Optional keyword that configures branch tracking:
                      :no-track
                      :set-upstream
                      :track
                    (default: nil)
  "
  [^Git repo branch-name & {:keys [force? ^String start-point upstream-mode]
                            :or   {force?        false
                                   start-point   nil
                                   upstream-mode nil}}]
  (as-> (.branchCreate repo) ^CreateBranchCommand cmd
        (.setName cmd branch-name)
        (.setForce cmd force?)
        (if (some? start-point)
          (.setStartPoint cmd start-point) cmd)
        (if (some? upstream-mode)
          (.setUpstreamMode cmd (upstream-mode branch-upstream-modes)) cmd)
        (.call cmd)))

(defn git-branch-delete
  "Delete one or several branches. `branch-names` may be either a string or a coll of strings. The result is a list with
  the (full) names of the deleted branches. Note that we don't have a option corresponding to the -r option; remote
  tracking branches are simply deleted just like local branches.

    Options:

    :force? true corresponds to the -D option, false to the -d option. If false a check
            will be performed whether the branch to be deleted is already merged into
            the current branch and deletion will be refused in this case.
            (default: false)
  "
  [^Git repo branch-names & {:keys [force?]
                             :or   {force? false}}]
  (-> (.branchDelete repo)
      (.setBranchNames (into-array String (seq?! branch-names)))
      (.setForce force?)
      (.call)))

(defonce checkout-stage-modes
         {:base   CheckoutCommand$Stage/BASE
          :ours   CheckoutCommand$Stage/OURS
          :theirs CheckoutCommand$Stage/THEIRS})

(defn git-checkout
  "Checkout a branch to the working tree.

    Options:

    :all-paths?     Do a path checkout on the entire repository. If this option is set,
                    neither the :create-branch? nor :name option is considered. In
                    other words, these options are exclusive. (default: false)
    :create-branch? If true a branch will be created as part of the checkout and set to
                    the specified :start-point. (default: false)
    :force?         If true and the branch with the given name already exists, the
                    start point of an existing branch will be set to a new start-point;
                    if false, the existing branch will not be changed. (default: false)
    :monitor        Set a progress monitor. See JGit ProgressMonitor interface.
                    (default: nil)
    :name           The name of the branch or commit to check out, or the new branch name.
                    When only checking out paths and not switching branches, use
                    :start-point to specify from which branch or commit to check out
                    files. When :create-branch? is set to true, use this option to set
                    the name of the new branch and :start-point to specify the
                    start-point of the branch. (default: nil)
    :orphan?        Whether to create a new orphan branch. If true , the name of the new
                    orphan branch must be set using :name. The commit at which to start
                    the new orphan branch can be set using :start-point; if not
                    specified, \"HEAD\" is used. (default: false)
    :paths          String or coll of strings with path(s) to check out. If this option is
                    set, neither the :create-branch? nor :name option is considered.
                    In other words, these options are exclusive. (default: nil)
    :stage          When checking out the index, check out the specified stage for
                    unmerged paths. This can not be used when checking out a branch, only
                    when checking out the index. Keywords:
                      :base     Base stage (#1)
                      :ours     Ours stage (#2)
                      :theirs   Theirs stage (#3)
                    (default: nil)
    :start-point    String that corresponds to the --start-point option. When checking
                    out :paths and this is not specified or null, the index is used.
                    When creating a new branch, this will be used as the start point. If
                    null, the current HEAD will be used. (default: nil)
    :upstream-mode  Optional keyword that configures branch tracking when creating a new
                    branch with :create-branch?.
                    Modes are:
                      :no-track
                      :set-upstream
                      :track
                    (default: nil)

    Usage examples:

    Check out an existing branch:

      (git-checkout repo :name \"feature\");

    Check out paths from the index:

      (git-checkout repo :paths [\"file1.txt\" \"file2.txt\"]);

    Check out a path from a commit:

      (git-checkout repo :start-point \"HEAD\" :paths \"file1.txt\");

    Create a new branch and make it the current branch:

      (git-checkout repo :create-branch? true :name \"newbranch\");

    Create a new tracking branch for a remote branch and make it the current branch:

      (git-checkout repo :create-branch? true :name \"stable\"
          :upstream-mode :set-upstream :start-point \"origin/stable\");
  "
  [^Git repo & {:keys [all-paths? create-branch? force? monitor name orphan? paths stage ^String start-point upstream-mode]
                :or   {all-paths?     false
                       create-branch? false
                       force?         false
                       monitor        nil
                       name           nil
                       orphan?        false
                       paths          nil
                       stage          nil
                       start-point    nil
                       upstream-mode  nil}}]
  (as-> (.checkout repo) ^CheckoutCommand cmd
        (if (some? name)
          (.setName cmd name) cmd)
        (if (some? paths)
          (.addPaths cmd (seq?! paths)) cmd)
        (.setAllPaths cmd all-paths?)
        (.setCreateBranch cmd create-branch?)
        (.setForceRefUpdate cmd force?)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (.setOrphan cmd orphan?)
        (if (some? stage)
          (.setStage cmd (stage checkout-stage-modes)) cmd)
        (if (some? start-point)
          (.setStartPoint cmd start-point) cmd)
        (if (some? upstream-mode)
          (.setUpstreamMode cmd (upstream-mode branch-upstream-modes)) cmd)
        (.call cmd)))

(declare git-cherry-pick)

(defonce tag-opt
         {:auto-follow  TagOpt/AUTO_FOLLOW
          :fetch-tags   TagOpt/FETCH_TAGS
          :no-tags      TagOpt/NO_TAGS})

(defn clone-cmd ^CloneCommand [uri]
  (-> (Git/cloneRepository)
      (.setURI uri)
      ^TransportCommand (.setCredentialsProvider *cred-provider*)
      (.setTransportConfigCallback *transport-callback*)))

(defn git-clone
  "Clone a repository into a new working directory from given `uri`.

    Options:

    :bare?          Whether the cloned repository shall be bare. (default: false)
    :branch         The initial branch to check out when cloning the repository. Can be
                    specified as ref name (\"refs/heads/master\"), branch name
                    (\"master\") or tag name (\"v1.2.3\"). If set to nil \"HEAD\"
                    is used. (default: \"master\")
    :callback       Register a progress callback. See JGit CloneCommand.Callback
                    interface. (default: nil)
    :clone-all?     Whether all branches have to be fetched. (default: true)
    :clone-branches String or coll of strings of branch(es) to clone. Ignored when
                    :clone-all? is true. Branches must be specified as full ref
                    names (e.g. \"refs/heads/master\"). (default: nil)
    :clone-subs?    If true; initialize and update submodules. Ignored when :bare?
                    is true. (default: false)
    :dir            The optional directory associated with the clone operation. If
                    the directory isn't set, a name associated with the source uri
                    will be used. (default: nil)
    :git-dir        The repository meta directory (.git). (default: nil = automatic)
    :no-checkout?   If set to true no branch will be checked out after the clone.
                    This enhances performance of the clone command when there is no
                    need for a checked out branch. (default: false)
    :mirror?        Set up a mirror of the source repository. This implies that a
                    bare repository will be created. Compared to :bare?, :mirror?
                    not only maps local branches of the source to local branches of
                    the target, it maps all refs (including remote-tracking branches,
                    notes etc.) and sets up a refspec configuration such that all
                    these refs are overwritten by a git remote update in the target
                    repository. (default: false)
    :monitor        Set a progress monitor. See JGit ProgressMonitor interface.
                    (default: nil)
    :remote         The remote name used to keep track of the upstream repository for the
                    clone operation. If no remote name is set, \"origin\" is used.
                    (default: nil)
    :tags           Set the tag option used for the remote configuration explicitly.
                    Options:
                      :auto-follow  - Automatically follow tags if we fetch the thing they point at.
                      :fetch-tags   - Always fetch tags, even if we do not have the thing it points at.
                      :no-tags      - Never fetch tags, even if we have the thing it points at.
                    (default: :auto-follow)
  "
  [uri & {:keys [bare? branch callback clone-all? clone-branches clone-subs? dir git-dir no-checkout? mirror? monitor
                 remote tags]
          :or   {bare?          false
                 branch         "master"
                 clone-all?     true
                 clone-branches nil
                 clone-subs?    false
                 callback       nil
                 dir            nil
                 git-dir        nil
                 no-checkout?   false
                 mirror?        false
                 monitor        nil
                 remote         nil
                 tags           :auto-follow}}]
  (as-> (clone-cmd uri) cmd
        (.setBare cmd bare?)
        (.setBranch cmd branch)
        (.setCloneAllBranches cmd clone-all?)
        (if (some? clone-branches)
          (.setBranchesToClone cmd (seq?! clone-branches)) cmd)
        (.setCloneSubmodules cmd clone-subs?)
        (if (some? callback)
          (.setCallback cmd callback) cmd)
        (if (some? dir)
          (.setDirectory cmd (io/as-file dir)) cmd)
        (if (some? git-dir)
          (.setGitDir cmd (io/as-file git-dir)) cmd)
        (.setNoCheckout cmd no-checkout?)
        (.setMirror cmd mirror?)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (if (some? remote)
          (.setRemote cmd remote) cmd)
        (.setTagOption cmd (tags tag-opt))
        (.call cmd)))

(defn ^CredentialsProvider signing-pass-provider [^String key-pw]
  "Return a new `CredentialsProvider` instance for given `key-pw`."
  (proxy [CredentialsProvider] []
    (supports [items]
      (if (some? (->> items
                      (filter #(when (instance? CredentialItem$CharArrayType %) true))
                      first))
        true
        false))
    (get [uri items]
      (let [^CredentialItem$CharArrayType pw-item (->> items
                                                       (filter #(when (instance? CredentialItem$CharArrayType %) true))
                                                       first)]
        (if (some? pw-item)
          (do (.setValue pw-item (char-array key-pw)) true)
          false)))
    (isInteractive []
      false)))

(declare git-config-load)

(defn gpg-config [^Git repo]
  "Return commit signing config for given `repo`."
  (let [config (GpgConfig. (git-config-load repo))]
    {:sign?       (.isSignCommits config)
     :signing-key (.getSigningKey config)
     :key-format  (.getKeyFormat config)}))

(defn git-commit
  "Record changes to given `repo`.

    Options:

    :all?               If set to true the Commit command automatically stages files that
                        have been modified and deleted, but new files not known by the
                        repository are not affected. This corresponds to the parameter -a
                        on the command line. (default: false)
    :allow-empty?       Whether it should be allowed to create a commit which has the
                        same tree as it's sole predecessor (a commit which doesn't
                        change anything). By default when creating standard commits
                        (without specifying paths) JGit allows to create such commits.
                        When this flag is set to false an attempt to create an \"empty\"
                        standard commit will lead to an EmptyCommitException.
                        (default: true)
    :amend?             Used to amend the tip of the current branch. If set to true, the
                        previous commit will be amended. This is equivalent to --amend
                        on the command line. (default: false)
    :author             A map of format {:name \"me\" :email \"me@foo.net\"}. If no
                        author is explicitly specified the author will be set to the
                        committer or to the original author when amending. (default: nil)
    :committer          A map of format {:name \"me\" :email \"me@foo.net\"}. If no
                        committer is explicitly specified the committer will be deduced
                        from config info in current repository, with current time.
                        (default: nil)
    :insert-change-id?  If set to true a change id will be inserted into the commit
                        message. An existing change id is not replaced. An initial change
                        id (I000...) will be replaced by the change id.
                        (default: nil)
    :no-verify?         Whether this commit should *not* be verified by the pre-commit and
                        commit-msg hooks. (default: false)
    :only               String or coll of strings. If set commit dedicated path(s) only.
                        Full file paths are supported as well as directory paths; in the
                        latter case this commits all files/directories below the specified
                        path. (default: nil)
    :reflog-comment     Override the message written to the reflog or pass nil to specify
                        that no reflog should be written. If an empty string is passed
                        Git's default reflog msg is used. (default: \"\")
    :sign?              Sign the commit? If nil the git config is used (commit.gpgSign).
                        Note that unprotected GPG keys are currently not supported by JGit.
                        (default: nil)
    :signing-key        The GPG key id used for signing. If nil the git config is used (user.signingKey).
                        (default: nil)
    :signing-pw         The key password for the default credentials provider.
                        (default: nil)
    :signing-provider   Pass a custom CredentialsProvider instance, overrides :signing-pw.
                        (default: nil)
  "
  [^Git repo message & {:keys [all? allow-empty? amend? author committer insert-change-id? no-verify? only
                               reflog-comment sign? signing-key signing-pw signing-provider]
                        :or   {all?              false
                               allow-empty?      true
                               amend?            false
                               author            nil
                               committer         nil
                               insert-change-id? false
                               no-verify?        false
                               only              nil
                               reflog-comment    ""
                               sign?             nil
                               signing-key       nil
                               signing-pw        nil
                               signing-provider  nil}}]
  (let [sign? (if (some? sign?)
                sign?
                (:sign? (gpg-config repo)))]
    (as-> (.commit repo) ^CommitCommand cmd
          (.setAll cmd all?)
          (.setAllowEmpty cmd allow-empty?)
          (.setAmend cmd amend?)
          (if (some? author)
            (.setAuthor cmd (:name author) (:email author)) cmd)
          (if (some? committer)
            (.setCommitter cmd (:name committer) (:email committer)) cmd)
          (.setInsertChangeId cmd insert-change-id?)
          (.setMessage cmd message)
          (.setNoVerify cmd no-verify?)
          (if (some? only)
            (doseq-cmd-fn! cmd #(.setOnly ^CommitCommand %1 %2) only) cmd)
          (if (or (nil? reflog-comment)
                  (not (clojure.string/blank? reflog-comment)))
            (.setReflogComment cmd reflog-comment) cmd)
          (.setSign cmd sign?)
          (if (and sign? (some? signing-key))
            (.setSigningKey cmd signing-key) cmd)
          (if (and sign? (some? signing-pw))
            ; see https://bugs.eclipse.org/bugs/show_bug.cgi?id=553116
            (do (.setCredentialsProvider cmd (signing-pass-provider signing-pw)) cmd)
            cmd)
          (if (and sign? (some? signing-provider))
            ; see https://bugs.eclipse.org/bugs/show_bug.cgi?id=553116
            (do (.setCredentialsProvider cmd signing-provider) cmd)
            cmd)
          (.call cmd))))

(defn ^StoredConfig git-config-load [^Git repo]
  "Return mutable JGit StoredConfig object for given `repo`."
  (-> repo .getRepository .getConfig))

(defn git-config-save [^StoredConfig git-config]
  "Save given `git-config` to repo's `.git/config` file."
  (.save git-config))

(defn parse-git-config-key [^String config-key]
  "Parse given Git `config-key` and return a vector of format [section subsection name]."
  (let [keys (str/split config-key #"\.")]
    (case (count keys)
      2 [(first keys) nil (last keys)]
      3 keys
      (throw (Exception. (str "Invalid config-key format: " config-key))))))

(defn git-config-get [^StoredConfig git-config ^String config-key]
  "Return Git config value as string for given Git `config-key`. Note that config keys that are not explicitly set in
  global/current repo config will always return nil and not the default value."
  (->> (parse-git-config-key config-key)
       (apply #(.getString git-config % %2 %3))))

(defn ^StoredConfig git-config-set [^StoredConfig git-config ^String config-key config-value]
  "Set given `config-value` for given Git `config-key`, always returns the passed JGit StoredConfig object."
  (->> (parse-git-config-key config-key)
       (apply #(.setString git-config % %2 %3 (str config-value))))
  git-config)

(defn fetch-cmd ^FetchCommand [^Git repo]
  (-> (.fetch repo)
      ^TransportCommand (.setCredentialsProvider *cred-provider*)
      (.setTransportConfigCallback *transport-callback*)))

(defonce fetch-recurse-submodules-modes
         {:no        SubmoduleConfig$FetchRecurseSubmodulesMode/NO
          :on-demand SubmoduleConfig$FetchRecurseSubmodulesMode/ON_DEMAND
          :yes       SubmoduleConfig$FetchRecurseSubmodulesMode/YES})

(defonce transport-tag-opts
         {:auto-follow TagOpt/AUTO_FOLLOW
          :fetch-tags  TagOpt/FETCH_TAGS
          :no-tags     TagOpt/NO_TAGS})

(defn git-fetch
  "Fetch changes from upstream `repo`.

    Options:

    :callback           Register a progress callback. See JGit FetchCommand.Callback
                        interface. (default: nil)
    :check-fetched?     If set to true, objects received will be checked for validity.
                        (default: false)
    :dry-run?           Whether to do a dry run. (default: false)
    :force?             Update refs affected by the fetch forcefully? (default: false)
    :monitor            Set a progress monitor. See JGit ProgressMonitor interface.
                        (default: nil)
    :recurse-subs       Keyword that corresponds to the --(no-)recurse-submodules
                        options. If nil use the value of the
                        submodule.name.fetchRecurseSubmodules option configured per
                        submodule. If not specified there, use the value of the
                        fetch.recurseSubmodules option configured in git config. If not
                        configured in either, :on-demand is the built-in default.
                          :no
                          :on-demand
                          :yes
                        (default: nil)
    :ref-specs          String or coll of strings of RefSpecs to be used in the fetch
                        operation. (default: nil)
    :remote             The remote (uri or name) used for the fetch operation. If no
                        remote is set \"origin\" is used. (default: nil)
    :rm-deleted-refs?   If set to true, refs are removed which no longer exist in the
                        source. If nil the Git repo config is used, if no config could
                        be found false is used. (default: nil)
    :tag-opt            Keyword that sets the specification of annotated tag behavior
                        during fetch:
                          :auto-follow    Automatically follow tags if we fetch the thing
                                          they point at.
                          :fetch-tags     Always fetch tags, even if we do not have the
                                          thing it points at.
                          :no-tags        Never fetch tags, even if we have the thing it
                                          points at.
                        (default: nil)
    :thin?              Sets the thin-pack preference for fetch operation. (default: true)
  "
  ^FetchResult [^Git repo & {:keys [callback check-fetched? dry-run? force? monitor recurse-subs ref-specs remote
                                    rm-deleted-refs? tag-opt thin?]
                             :or   {callback         nil
                                    check-fetched?   false
                                    dry-run?         false
                                    force?           false
                                    monitor          nil
                                    recurse-subs     nil
                                    ref-specs        nil
                                    remote           nil
                                    rm-deleted-refs? nil
                                    tag-opt          nil
                                    thin?            true}}]
  (as-> (fetch-cmd repo) cmd
        (if (some? callback)
          (.setCallback cmd callback) cmd)
        (.setCheckFetchedObjects cmd check-fetched?)
        (.setDryRun cmd dry-run?)
        (.setForceUpdate cmd force?)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (if (some? recurse-subs)
          (.setRecurseSubmodules cmd (recurse-subs fetch-recurse-submodules-modes)) cmd)
        (if (some? ref-specs)
          (.setRefSpecs cmd ^"[Ljava.lang.String;" (into-array String (seq?! ref-specs))) cmd)
        (if (some? remote)
          (.setRemote cmd remote) cmd)
        (if (some? rm-deleted-refs?)
          (.setRemoveDeletedRefs cmd rm-deleted-refs?) cmd)
        (if (some? tag-opt)
          (.setTagOpt cmd (tag-opt transport-tag-opts)) cmd)
        (.setThin cmd thin?)
        (.call cmd)))

(defn git-fetch-all
  "Fetch all refs from upstream `repo`"
  ([^Git repo]
   (git-fetch-all repo "origin"))
  ([^Git repo remote]
   (git-fetch repo :remote remote :ref-specs ["+refs/tags/*:refs/tags/*" "+refs/heads/*:refs/heads/*"])))

(defn git-init
  "Initialize and return a new Git repository, if no options are passed a non-bare repo is created at user.dir

    Options:

    :bare?    Whether the repository is bare or not. (default: false)
    :dir      The optional directory associated with the init operation. If no directory
              is set, we'll use the current directory. (default: \".\")
    :git-dir  Set the repository meta directory (.git). (default: nil = use default)
  "
  [& {:keys [bare? dir git-dir]
      :or   {bare?   false
             dir     "."
             git-dir nil}}]
  (as-> (InitCommand.) cmd
        (.setBare cmd bare?)
        (.setDirectory cmd (io/as-file dir))
        (if (some? git-dir)
          (.setGitDir cmd (io/as-file git-dir)) cmd)
        (.call cmd)))

(defn git-remote-add
  "Add a new remote to given `repo` and return the JGit RemoteAddCommand instance."
  [^Git repo name ^String uri]
  (doto (.remoteAdd repo)
    (.setName name)
    (.setUri (URIish. uri))
    (.call)))

(defn git-remote-remove
  "Remove a remote with given name from `repo` and return the JGit `RemoteRemoveCommand` instance."
  [^Git repo name]
  (doto (.remoteRemove repo)
    (.setName name)
    (.call)))

(defn git-remote-list
  "Returns a seq of vectors with format [name [^URIish ..]] representing all configured remotes for given `repo`."
  [^Git repo]
  (->> (.remoteList repo)
       .call
       (map (fn [^RemoteConfig r]
              [(.getName r) (.getURIs r)]))))

(defn git-log
  "Returns a seq of maps representing the commit history for current branch of given `repo`. `:range` is equal to
  setting both ´:since` and `:until`. To include the commit referenced by `:since ObjectId` in the returned seq append
  the ObjectId with a `^`, i.e. `:since \"d13c67^\"`.

    Options:

    :all?         Add all refs as commits to start the graph traversal from.
                  (default: false)
    :jgit?        If true returns a seq with the untouched JGit objects instead.
                  (default: false)
    :max-count    Limit the number of commits to output. (default: nil)
    :paths        String or coll of strings; show only commits that affect any of the
                  specified paths. The path must either name a file or a directory exactly
                  and use / (slash) as separator. Note that regex expressions or wildcards
                  are not supported. (default: nil)
    :range        Map with format {:since Resolvable :until Resolvable}. Adds the range
                  since..until. (default: nil)
    :rev-filter   Set a RevFilter for the LogCommand. (default: nil)
    :since        Same as --not until, or ^until; `until` being a Resolvable, i.e.
                  \"HEAD\", ObjectId, etc. (default: nil)
    :skip         Number of commits to skip before starting to show the log output.
                  (default: nil)
    :until        Resolvable (\"master\", ObjectId, etc) to start graph traversal from.
                  (default: nil)
  "
  [^Git repo & {:keys [all? jgit? max-count paths range rev-filter since skip until]
                :or   {all?       false
                       jgit?      false
                       max-count  nil
                       paths      nil
                       range      nil
                       rev-filter nil
                       since      nil
                       skip       nil
                       until      nil}}]
  (as-> (.log repo) ^LogCommand cmd
        (if (some? until)
          (.add cmd (resolve-object until repo)) cmd)
        (if (some? since)
          (.not cmd (resolve-object since repo)) cmd)
        (if (some? range)
          (.addRange cmd (resolve-object (:since range) repo) (resolve-object (:until range) repo)) cmd)
        (if all?
          (.all cmd) cmd)
        (if (some? max-count)
          (.setMaxCount cmd max-count) cmd)
        (if (some? paths)
          (doseq-cmd-fn! cmd #(.addPath ^LogCommand %1 %2) paths) cmd)
        (if (some? rev-filter)
          (.setRevFilter cmd rev-filter) cmd)
        (if (some? skip)
          (.setSkip cmd skip) cmd)
        (.call cmd)
        (if jgit?
          cmd
          (map #(do {:id        (.getId ^RevCommit %)
                     :msg       (.getShortMessage ^RevCommit %)
                     :author    (util/person-ident (.getAuthorIdent ^RevCommit %))
                     :committer (util/person-ident (.getCommitterIdent ^RevCommit %))
                     }) cmd))))

(defonce merge-ff-modes
         {:ff      MergeCommand$FastForwardMode/FF
          :ff-only MergeCommand$FastForwardMode/FF_ONLY
          :no-ff   MergeCommand$FastForwardMode/NO_FF})

(defonce merge-strategies
         {:ours           MergeStrategy/OURS
          :recursive      MergeStrategy/RECURSIVE
          :resolve        MergeStrategy/RESOLVE
          :simple-two-way MergeStrategy/SIMPLE_TWO_WAY_IN_CORE
          :theirs         MergeStrategy/THEIRS})

(defn git-merge
  "Merge given `refs` into current branch. `refs` may be anything supported by the Resolvable protocol, which also
  includes any sequential? with Resolvable(s), i.e. [\"HEAD\", ObjectId, \"d13c67\"].

    Options:

    :commit?    true if this command should commit (this is the default behavior). false
                if this command should not commit. In case the merge was successful but
                this flag was set to false a MergeResult with status
                MergeResult.MergeStatus.MERGED_NOT_COMMITTED is returned. (default: true)
    :ff-mode    Keyword that corresponds to the --ff/--no-ff/--ff-only options. If nil
                use the value of the merge.ff option configured in git config. If this
                option is not configured --ff is the built-in default.
                  :ff
                  :ff-only
                  :no-ff
                (default: nil)
    :message    Set the commit message to be used for the merge commit (in case one is
                created). (default: nil)
    :monitor    Set a progress monitor. See JGit ProgressMonitor interface. (default: nil)
    :squash?    If true, will prepare the next commit in working tree and index as if a
                real merge happened, but do not make the commit or move the HEAD.
                Otherwise, perform the merge and commit the result. In case the merge was
                successful but this flag was set to true a MergeResult with status
                MergeResult.MergeStatus.MERGED_SQUASHED or
                MergeResult.MergeStatus.FAST_FORWARD_SQUASHED is returned. (default: false)
    :strategy   The MergeStrategy to be used. A method of combining two or more trees
                together to form an output tree. Different strategies may employ different
                techniques for deciding which paths (and ObjectIds) to carry from the input
                trees into the final output tree:
                  :ours             Simple strategy that sets the output tree to the first
                                    input tree.
                  :recursive        Recursive strategy to merge paths.
                  :resolve          Simple strategy to merge paths.
                  :simple-two-way   Simple strategy to merge paths, without simultaneous
                                    edits.
                  :theirs           Simple strategy that sets the output tree to the second
                                    input tree.
                (default: :recursive)
  "
  [^Git repo refs & {:keys [commit? ff-mode message monitor squash? strategy]
                     :or   {commit?  true
                            ff-mode  nil
                            message  nil
                            monitor  nil
                            squash?  false
                            strategy :recursive}}]
  (as-> (.merge repo) ^MergeCommand cmd
        (doseq-cmd-fn! cmd #(.include ^MergeCommand %1 ^AnyObjectId %2) (resolve-object refs repo))
        (.setCommit cmd commit?)
        (if (some? ff-mode)
          (.setFastForward cmd (ff-mode merge-ff-modes)) cmd)
        (if (some? message)
          (.setMessage cmd message) cmd)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (.setSquash cmd squash?)
        (.setStrategy cmd (strategy merge-strategies))
        (.call cmd)))

(defonce branch-rebase-modes
         {:interactive BranchConfig$BranchRebaseMode/INTERACTIVE
          :none        BranchConfig$BranchRebaseMode/NONE
          :preserve    BranchConfig$BranchRebaseMode/PRESERVE
          :rebase      BranchConfig$BranchRebaseMode/REBASE})

(defn git-pull
  "Fetch from and integrate with another repository or a local branch.

    Options:

    :ff-mode          Keyword that corresponds to the --ff/--no-ff/--ff-only options. If
                      nil use the value of pull.ff configured in git config. If pull.ff
                      is not configured fall back to the value of merge.ff. If merge.ff
                      is not configured --ff is the built-in default.
                        :ff
                        :ff-only
                        :no-ff
                      (default: nil)
    :monitor          Set a progress monitor. See JGit ProgressMonitor interface.
                      (default: nil)
    :rebase-mode      Keyword that sets the rebase mode to use after fetching:
                        :rebase       Equivalent to --rebase: use rebase instead of merge
                                      after fetching.
                        :preserve     Equivalent to --preserve-merges: rebase preserving
                                      local merge commits.
                        :interactive  Equivalent to --interactive: use interactive rebase.
                        :none         Equivalent to --no-rebase: merge instead of rebasing.
                      When nil use the setting defined in the git configuration, either
                      branch.[name].rebase or, if not set, pull.rebase. This setting
                      overrides the settings in the configuration file.
                      (default: nil)
    :recurse-subs     Corresponds to the --recurse-submodules/--no-recurse-submodules
                      options. If nil use the value of the
                      submodule.name.fetchRecurseSubmodules option configured per
                      submodule. If not specified there, use the value of the
                      fetch.recurseSubmodules option configured in git config. If not
                      configured in either, :on-demand is the built-in default.
                        :no
                        :on-demand
                        :yes
                      (default: nil)
    :remote           The remote (uri or name) to be used for the pull operation. If no
                      remote is set, the branch's configuration will be used. If the
                      branch configuration is missing \"origin\" is used.
                      (default: nil)
    :remote-branch    The remote branch name to be used for the pull operation. If nil,
                      the branch's configuration will be used. If the branch
                      configuration is missing the remote branch with the same name as
                      the current branch is used. (default: nil)
    :strategy         Keyword that sets the merge strategy to use during this pull
                      operation:
                        :ours             Simple strategy that sets the output tree to
                                          the first input tree.
                        :recursive        Recursive strategy to merge paths.
                        :resolve          Simple strategy to merge paths.
                        :simple-two-way   Simple strategy to merge paths, without
                                          simultaneous edits.
                        :theirs           Simple strategy that sets the output tree to
                                          the second input tree.
                      (default: :recursive)
    :tag-opt          Keyword that sets the specification of annotated tag behavior
                      during fetch:
                        :auto-follow    Automatically follow tags if we fetch the thing
                                        they point at.
                        :fetch-tags     Always fetch tags, even if we do not have the
                                        thing it points at.
                        :no-tags        Never fetch tags, even if we have the thing it
                                        points at.
                      (default: nil)

    Example usage:

    (gitp/with-identity {:name \"~/.ssh/id_rsa\" :exclusive true}
      (gitp/git-pull repo :remote \"my-remote\"))
  "
  [^Git repo & {:keys [ff-mode monitor rebase-mode recurse-subs remote remote-branch strategy tag-opt]
                :or   {ff-mode       nil
                       monitor       nil
                       rebase-mode   nil
                       recurse-subs  nil
                       remote        nil
                       remote-branch nil
                       strategy      :recursive
                       tag-opt       nil}}]
  (as-> (.pull repo) cmd
        (if (some? ff-mode)
          (.setFastForward cmd (ff-mode merge-ff-modes)) cmd)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (if (some? rebase-mode)
          (.setRebase cmd ^BranchConfig$BranchRebaseMode (rebase-mode branch-rebase-modes)) cmd)
        (if (some? recurse-subs)
          (.setRecurseSubmodules cmd (recurse-subs fetch-recurse-submodules-modes)) cmd)
        (if (some? remote)
          (.setRemote cmd remote) cmd)
        (if (some? remote-branch)
          (.setRemoteBranchName cmd remote-branch) cmd)
        (.setStrategy cmd (strategy merge-strategies))
        (if (some? tag-opt)
          (.setTagOpt cmd (tag-opt transport-tag-opts)) cmd)
        ^TransportCommand (.setCredentialsProvider cmd *cred-provider*)
        (.setTransportConfigCallback cmd *transport-callback*)
        (.call cmd)))

(defn git-push
  "Update remote refs along with associated objects for given `repo`.

    Options:

    :all?             Push all branches under `refs/heads/*`, equal to
                      :refs \"refs/heads/*:refs/heads/*\". (default: false)
    :atomic?          Requests atomic push (all references updated, or no updates).
                      (default: false)
    :dry-run?         Whether to run the push operation as a dry run. (default: false)
    :force?           Corresponds to --force option. (default: false)
    :monitor          Set a progress monitor. See JGit ProgressMonitor interface.
                      (default: nil)
    :options          String or coll of strings that corresponds to --push-option=<option>.
                      Transmits the given string to the server, which passes them to the
                      pre-receive as well as the post-receive hook. The given string must
                      not contain a NUL or LF character. When multiple options are given,
                      they are all sent to the other side in the order listed. When no
                      --push-option=<option> is given the values of configuration variable
                      push.pushOption are used instead. (default: nil)
    :output-stream    Sets the OutputStream to write sideband messages to. (default: nil)
    :receive-pack     The remote executable providing receive-pack service for pack
                      transports. If no :receive-pack is set, the default value of
                      RemoteConfig.DEFAULT_RECEIVE_PACK will be used. (default: nil)
    :ref-lease-specs  Map or coll of maps with format
                      {:ref \"some-ref\" :expected \"committish\"}. Corresponds to the
                      --force-with-lease option. :ref is the remote ref to protect,
                      :expected is the local commit the remote branch is expected to be at,
                      if it doesn't match the push will fail. (default: nil)
    :ref-specs        Equal to :refs but takes a either a single RefSpec instance or a
                      coll of those. (default: nil)
    :refs             String or coll of strings of name(s) or ref(s) to push. If nil the
                      repo config for the specified :remote is used, if that is also nil
                      the ref is resolved from current HEAD.
                      (default: nil)
    :remote           The remote (uri or name) used for the push operation. If nil
                      \"origin\" is used. (default: nil).
    :tags?            Also push all tags under `refs/tags/*`. (default: false)
    :thin?            Set the thin-pack preference for the push operation. (default: false)

    Example usage:

    Push current branch to remote `my-remote`, including tags and using the current
    user's ssh key for auth:

      (gitp/with-identity {:name \"~/.ssh/id_rsa\" :exclusive true}
        (gitp/git-push repo :remote \"my-remote\" :tags? true))
  "
  [^Git repo & {:keys [all? atomic? dry-run? force? monitor options output-stream receive-pack ref-lease-specs
                       ref-specs refs remote tags? thin?]
                :or   {all?            false
                       atomic?         false
                       dry-run?        false
                       force?          false
                       monitor         nil
                       options         nil
                       output-stream   nil
                       receive-pack    nil
                       ref-lease-specs nil
                       ref-specs       nil
                       refs            nil
                       remote          nil
                       tags?           false
                       thin?           false}}]
  (as-> (.push repo) ^PushCommand cmd
        (if all?
          (.setPushAll cmd) cmd)
        (.setAtomic cmd atomic?)
        (.setDryRun cmd dry-run?)
        (.setForce cmd force?)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (if (some? options)
          (.setPushOptions cmd (seq?! options)) cmd)
        (if (some? output-stream)
          (.setOutputStream cmd output-stream) cmd)
        (if (some? ref-lease-specs)
          (.setRefLeaseSpecs cmd (->> ref-lease-specs
                                      seq?!
                                      (map #(RefLeaseSpec. (:ref %) (:expected %)))
                                      ^List (apply list)))
          cmd)
        (if (some? ref-specs)
          (.setRefSpecs cmd ^List (map #(RefSpec. %) (seq?! ref-specs))) cmd)
        (if (some? refs)
          (doseq-cmd-fn! cmd #(.add ^PushCommand %1 ^String %2) refs) cmd)
        (if (some? remote)
          (.setRemote cmd remote) cmd)
        (if tags? (.setPushTags cmd) cmd)
        (.setThin cmd thin?)
        ^TransportCommand (.setCredentialsProvider cmd *cred-provider*)
        (.setTransportConfigCallback cmd *transport-callback*)
        (.call cmd)))

(defn git-rebase [])

(defn git-revert
  "Revert given commits, which can either be a single resolvable (\"HEAD\", \"a6efda\", etc) or a coll of resolvables.
  Returns a map of format:

      {:reverted  The list of successfully reverted Ref's. Never nil but maybe an empty
                  list if no commit was successfully cherry-picked.
      :unmerged  Any unmerged paths, will be nil if no merge conflicts.
      :error     The result of a merge failure, nil if no merge failure occurred during the revert.
      }

    Options:

    :monitor          Set a progress monitor. See JGit ProgressMonitor interface.
                      (default: nil)
    :our-commit-name  The name to be used in the \"OURS\" place for conflict markers.
                      (default: nil)
    :strategy         Keyword that sets the merge strategy to use during this revert
                      operation:
                           :ours             Simple strategy that sets the output tree
                                             to the first input tree.
                           :recursive        Recursive strategy to merge paths.
                           :resolve          Simple strategy to merge paths.
                           :simple-two-way   Simple strategy to merge paths, without
                                             simultaneous edits.
                           :theirs           Simple strategy that sets the output tree
                                             to the second input tree.
                      (default: :recursive)
  "
  [^Git repo commits & {:keys [monitor our-commit-name strategy]
                        :or   {monitor         nil
                               our-commit-name nil
                               strategy        :recursive}}]
  (let [revert-cmd (.revert repo)]
    (as-> revert-cmd cmd
          ^RevertCommand (doseq-cmd-fn! cmd #(.include ^RevertCommand %1 ^AnyObjectId %2) (resolve-object commits repo))
          (if (some? our-commit-name)
            (.setOurCommitName cmd our-commit-name) cmd)
          (if (some? monitor)
            (.setProgressMonitor cmd monitor) cmd)
          (.setStrategy cmd (strategy merge-strategies))
          (.call cmd)
          {:reverted (.getRevertedRefs revert-cmd)
           :unmerged (.getUnmergedPaths revert-cmd)
           :error    (.getFailingResult revert-cmd)})))

(defn git-rm
  "Remove files from the working tree and from the index. `file-patterns` is a string or coll of strings with the
  repository-relative path of file(s) to remove.

    Options:

    :cached?    true if files should only be removed from index, false if files should
                also be deleted from the working directory. (default: false)
  "
  [^Git repo file-patterns & {:keys [cached?]
                              :or   {cached? false}}]
  (-> (.rm repo)
      ^RmCommand (doseq-cmd-fn! #(.addFilepattern ^RmCommand %1 %2) file-patterns)
      (.setCached cached?)
      (.call)))

(defn git-status
  "Show the working tree status. Returns a map with keys corresponding to the passed `:status` and `:status-fn` args.

    Options:

    :ignore-subs?           Whether to ignore submodules. If nil use repo config.
                            (default: nil)
    :jgit?                  If true just returns the JGit Status object. (default: false)
    :monitor                Set a progress monitor. See JGit ProgressMonitor interface.
                            (default: nil)
    :paths                  String or coll of strings with path(s). Only show the status
                            of files which match the given paths. The path must either
                            name a file or a directory exactly. All paths are always
                            relative to the repository root. If a directory is specified
                            all files recursively underneath that directory are matched.
                            If multiple paths are passed the status of those files is
                            reported which match at least one of the given paths. Note
                            that regex expressions or wildcards are not supported.
                            (default: nil)
    :status                 Keyword or coll of keywords that select which built-in status
                            functions are included in the output, if nil only functions
                            passed through :status-fns are used. Possible keywords:
                              :added
                              :changed
                              :missing
                              :modified
                              :removed
                              :untracked
                            (default: :all)
    :status-fns             Map of format {:output-id fn}. Utilize custom functions that
                            are passed the JGit Status instance, the function's return is
                            included in the output map at the corresponding :output-id
                            key. Example that adds a :clean? and :changes? bool to the
                            output map:
                              {:clean? #(.isClean ^Status %) :changes? #(.hasUncommittedChanges ^Status %)}
                            (default: nil)
    :working-tree-iterator  Set the WorkingTreeIterator to be used. If nil a standard
                            FileTreeIterator is used. (default: nil)
  "
  [^Git repo & {:keys [ignore-subs? jgit? monitor paths status status-fns working-tree-iterator]
                :or   {ignore-subs?          nil
                       jgit?                 false
                       monitor               nil
                       paths                 nil
                       status                :all
                       status-fns            nil
                       working-tree-iterator nil}}]
  (let [status-instance (as-> (.status repo) ^StatusCommand cmd
                              (if (some? ignore-subs?)
                                (.setIgnoreSubmodules cmd ignore-subs?) cmd)
                              (if (some? monitor)
                                (.setProgressMonitor cmd monitor) cmd)
                              (if (some? paths)
                                (doseq-cmd-fn! cmd #(.addPath ^StatusCommand %1 %2) paths) cmd)
                              (if (some? working-tree-iterator)
                                (.setWorkingTreeIt cmd working-tree-iterator) cmd)
                              (.call cmd))
        def-status-fns {:added     #(into #{} (.getAdded ^Status %))
                        :changed   #(into #{} (.getChanged ^Status %))
                        :missing   #(into #{} (.getMissing ^Status %))
                        :modified  #(into #{} (.getModified ^Status %))
                        :removed   #(into #{} (.getRemoved ^Status %))
                        :untracked #(into #{} (.getUntracked ^Status %))}
        selected-def-fns (if (some? status)
                           (if (= status :all)
                             def-status-fns
                             (select-keys def-status-fns (seq?! status)))
                           {})
        output-fns (if (some? status-fns)
                     (merge selected-def-fns status-fns)
                     selected-def-fns)]
    (if jgit?
      status-instance
      (apply merge (for [[k f] output-fns]
                     {k (f status-instance)})))))

(defn git-tag-create
  "Creates an annotated tag with the provided name and (optional) message.

    Options:

    :annotated?   Whether this shall be an annotated tag. Note that :message and :tagger
                  are ignored when this is set to false. (default: true)
    :force?       If set to true the Tag command may replace an existing tag object.
                  This corresponds to the parameter -f on the command line.
                  (default: false)
    :message      The tag message used for the tag. (default: nil)
    :signed?      If set to true the Tag command creates a signed tag. This corresponds
                  to the parameter -s on the command line. (default: false)
    :tagger       Map of format {:name \"me\" :email \"me@foo.net\"}. Sets the tagger of
                  the tag. If nil, a PersonIdent will be created from the info in the
                  given repository.
                  (default: nil)
  "
  [^Git repo tag-name & {:keys [annotated? force? message signed? tagger]
                         :or   {annotated? true
                                force?     false
                                message    nil
                                signed?    false
                                tagger     nil}}]
  (as-> (.tag repo) ^TagCommand cmd
        (.setAnnotated cmd annotated?)
        (.setForceUpdate cmd force?)
        (if (some? message)
          (.setMessage cmd message) cmd)
        (.setName cmd tag-name)
        (.setSigned cmd signed?)
        (if (some? tagger)
          (.setTagger cmd (PersonIdent. ^String (:name tagger) ^String (:email tagger))) cmd)
        (.call cmd)))

(defn git-tag-delete
  "Deletes tag(s) with the provided name(s)."
  [^Git repo & tag-names]
  (-> ^DeleteTagCommand (.tagDelete repo)
      (.setTags (into-array String tag-names))
      (.call)))

(defn git-tag-list
  "Lists the tags in a `repo`, returning them as a seq of strings."
  [^Git repo]
  (->> (.tagList repo)
       (.call)
       (map #(->> ^Ref % .getName (re-matches #"refs/tags/(.*)") second))))

(defn ls-remote-cmd ^LsRemoteCommand [^Git repo]
  (-> (.lsRemote repo)
      ^TransportCommand (.setCredentialsProvider *cred-provider*)
      (.setTransportConfigCallback *transport-callback*)))

(defn git-ls-remote
  "List references in a remote `repo`.

    Options:

    :heads?         Whether to include refs/heads. (default: false)
    :remote         The remote (uri or name) used for the fetch operation. If nil, the
                    repo config will be used. (default: nil)
    :tags?          Whether to include refs/tags in references results. (default: false)
    :upload-pack    The full path of executable providing the git-upload-pack service on
                    remote host. (default: nil)
  "
  [^Git repo & {:keys [heads? remote tags? upload-pack]
                :or   {heads?      false
                       remote      nil
                       tags?       false
                       upload-pack nil}}]
  (as-> (ls-remote-cmd repo) cmd
        (if (some? remote)
          (.setRemote cmd remote) cmd)
        (.setHeads cmd heads?)
        (.setTags cmd tags?)
        (if (some? upload-pack)
          (.setUploadPack cmd upload-pack) cmd)
        (.call cmd)))

(defonce reset-modes
         {:hard  ResetCommand$ResetType/HARD
          :keep  ResetCommand$ResetType/KEEP
          :merge ResetCommand$ResetType/MERGE
          :mixed ResetCommand$ResetType/MIXED
          :soft  ResetCommand$ResetType/SOFT})

(defn git-reset
  "Reset current HEAD to the specified `:ref`, or reset given `:paths`.

    Options:

    :mode         Keyword that sets the reset mode:
                    :hard
                    :keep
                    :merge
                    :mixed
                    :soft
                  (default: :mixed, always nil if :paths is set)
    :monitor      Set a progress monitor. See JGit ProgressMonitor interface.
                  (default: nil)
    :paths        String or coll of strings with repository relative path(s) of file(s)
                  or directory to reset. (default: nil)
    :ref          String with the ref to reset to, defaults to HEAD if nil. (default: nil)
    :ref-log?     If false disables writing a reflog entry for this reset command.
                  (default: true)
  "
  [^Git repo & {:keys [mode monitor paths ref ref-log?]
                :or   {mode     :mixed
                       monitor  nil
                       paths    nil
                       ref      nil
                       ref-log? true}}]
  (as-> (.reset repo) ^ResetCommand cmd
        (if (nil? paths)
          (.setMode cmd (mode reset-modes)) cmd)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (if (some? paths)
          (doseq-cmd-fn! cmd #(.addPath ^ResetCommand %1 %2) paths) cmd)
        (if (some? ref)
          (.setRef cmd ref) cmd)
        (.disableRefLog cmd (not ref-log?))
        (.call cmd)))

(defn submodule-walk
  ([^Git repo]
   (->> (submodule-walk (.getRepository repo) 0)
        (flatten)
        (filter identity)
        (map #(Git/wrap %))))
  ([^Git repo level]
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
  [^Git repo]
  (doseq [subm (submodule-walk repo)]
    (git-fetch-all subm)))

(defn submodule-update-cmd ^SubmoduleUpdateCommand [^Git repo]
  (-> (.submoduleUpdate repo)
      ^TransportCommand (.setCredentialsProvider *cred-provider*)
      (.setTransportConfigCallback *transport-callback*)))

(defn git-submodule-update
  "Update all submodules of given `repo`.

    Options:

    callback        Set a CloneCommand.Callback for this submodule clone operation.
                    (default: nil)
    fetch?          Whether to fetch the submodules before we update them. (default: true)
    fetch-callback  Set a FetchCommand.Callback for submodule fetch operation.
                    (default: nil)
    monitor         Set a progress monitor. See JGit ProgressMonitor interface.
                    (default: nil)
    paths           String or coll of strings with repository-relative submodule path(s)
                    to update. If nil all submodules of given repo are updated.
                    (default: nil)
    strategy        Keyword that sets the merge strategy to use during this update
                    operation:
                      :ours             Simple strategy that sets the output tree to the
                                        first input tree.
                      :recursive        Recursive strategy to merge paths.
                      :resolve          Simple strategy to merge paths.
                      :simple-two-way   Simple strategy to merge paths, without simultaneous
                                        edits.
                      :theirs           Simple strategy that sets the output tree to the
                                        second input tree.
                    (default: :recursive)
  "
  [^Git repo & {:keys [callback fetch? fetch-callback monitor paths strategy]
                :or   {callback       nil
                       fetch?         true
                       fetch-callback nil
                       monitor        nil
                       paths          nil
                       strategy       nil}}]
  (as-> (submodule-update-cmd repo) ^SubmoduleUpdateCommand cmd
        (if (some? callback)
          (.setCallback cmd callback) cmd)
        (.setFetch cmd fetch?)
        (if (some? fetch-callback)
          (.setFetchCallback cmd fetch-callback) cmd)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (if (some? paths)
          (doseq-cmd-fn! cmd #(.addPath ^SubmoduleUpdateCommand %1 %2) paths) cmd)
        (.setStrategy cmd (strategy merge-strategies))
        (.call cmd)))

(defn git-submodule-sync
  "Set the remote URL in a submodule's repository to the current value in the .gitmodules file of given `repo`.

    Options:

    :paths    String or coll of strings with repository-relative submodule path(s) to
              update. If nil all submodules of given `repo` are updated. (default: nil)
  "
  [^Git repo & {:keys [paths] :or {paths nil}}]
  (as-> (.submoduleSync repo) ^SubmoduleSyncCommand cmd
        (if (some? paths)
          (doseq-cmd-fn! cmd #(.addPath ^SubmoduleSyncCommand %1 %2) paths) cmd)
        (.call cmd)))

(defn git-submodule-init
  "Copy the `url` and `update` fields from the working tree's .gitmodules file to given `repo` config file for each
  submodule not currently present in the repository's config file.

    Options:

    :paths    String or coll of strings with repository-relative submodule path(s) to
              initialize. If nil all submodules of given `repo` are used. (default: nil)
  "
  [^Git repo & {:keys [paths] :or {paths nil}}]
  (as-> (.submoduleInit repo) ^SubmoduleInitCommand cmd
        (if (some? paths)
          (doseq-cmd-fn! cmd #(.addPath ^SubmoduleInitCommand %1 %2) paths) cmd)
        (.call cmd)))

(defn git-submodule-add
  "Clone the submodule from given `uri` to given `path`, register it in the .gitmodules file and the repository config
  file, and also add the submodule and .gitmodules file to the index.

    Options:

    :name     Set the submodule name, if omitted the name is derived from given path.
              (default: nil)
    :monitor  Set a progress monitor. See JGit ProgressMonitor interface. (default: nil)
  "
  [^Git repo uri path & {:keys [name monitor]
                         :or   {name    nil
                                monitor nil}}]
  (as-> (.submoduleAdd repo) cmd
        (.setURI cmd uri)
        (.setPath cmd path)
        (if (some? monitor)
          (.setName cmd name) cmd)
        (if (some? monitor)
          (.setProgressMonitor cmd monitor) cmd)
        (.call cmd)))

(defn git-stash-create
  "Stash changes in the working directory and index in a commit.

    Options:

    :index-msg        Set the message used when committing index changes. The message
                      will be formatted with the current branch, abbreviated commit id,
                      and short commit message when used. (default: nil)
    :person           Map of format {:name \"me\" :email \"me@foo.net\"}. Sets the
                      person to use as the author and committer in the commits made.
                      If nil the `repo` configuration is used. (default: nil)
    :ref              Set the reference to update with the stashed commit id. If nil,
                      no reference is updated. (default: nil)
    :untracked?       Whether to include untracked files in the stash. (default: false)
    :working-dir-msg  Set the message used when committing working directory changes.
                      The message will be formatted with the current branch, abbreviated
                      commit id, and short commit message when used. (default: nil)
  "
  [^Git repo & {:keys [index-msg person ref untracked? working-dir-msg]
                :or   {index-msg       nil
                       person          nil
                       ref             nil
                       untracked?      false
                       working-dir-msg nil}}]
  (as-> (.stashCreate repo) cmd
        (if (some? index-msg)
          (.setIndexMessage cmd index-msg) cmd)
        (if (some? person)
          (.setPerson cmd (PersonIdent. ^String (:name person) ^String (:email person))) cmd)
        (if (some? ref)
          (.setRef cmd ref) cmd)
        (.setIncludeUntracked cmd untracked?)
        (if (some? working-dir-msg)
          (.setWorkingDirectoryMessage cmd working-dir-msg) cmd)
        (.call cmd)))

(defn git-stash-apply
  "Behaves like git stash apply --index, i.e. it tries to recover the stashed index state in addition to the working
  tree state.

    Options:

    :ignore-repo-state?   If true ignores the repository state when applying the stash.
                          (default: false)
    :index?               Whether to restore the index state. (default: true)
    :stash-ref            String with the stash reference to apply. If nil defaults to
                          the latest stashed commit (\"stash@{0}\"). (default: nil)
    :strategy             Keyword that sets the merge strategy to use during this update
                          operation:
                            :ours             Simple strategy that sets the output tree to
                                              the first input tree.
                            :recursive        Recursive strategy to merge paths.
                            :resolve          Simple strategy to merge paths.
                            :simple-two-way   Simple strategy to merge paths, without
                                              simultaneous edits.
                            :theirs           Simple strategy that sets the output tree to
                                              the second input tree.
                          (default: :recursive)
    :untracked?           Restore untracked files? (default: true)
  "
  [^Git repo & {:keys [ignore-repo-state? index? untracked? stash-ref strategy]
                :or   {ignore-repo-state? false
                       index?             true
                       stash-ref          nil
                       strategy           :recursive
                       untracked?         true}}]
  (as-> (.stashApply repo) cmd
        (.ignoreRepositoryState cmd ignore-repo-state?)
        (doto cmd
          (.setApplyIndex index?)
          (.setApplyUntracked untracked?))
        (.setStashRef cmd stash-ref)
        (.setStrategy cmd (strategy merge-strategies))
        (.call cmd)))

(defn git-stash-list
  "List the stashed commits for given `repo`."
  [^Git repo]
  (-> repo
      .stashList
      .call))

(defn git-stash-drop
  "Delete a stashed commit reference. Currently only supported on a traditional file repository using one-file-per-ref
  reflogs.

    Options:

    :all?       If true drop all stashed commits, if false only the :stash-id is dropped.
                (default: false)
    :stash-id   Integer with the stash id to drop. Corresponds to stash@{stash-id}
                (default: 0)
  "
  [^Git repo & {:keys [all? stash-id]
                :or   {all?     false
                       stash-id 0}}]
  (-> (.stashDrop repo)
      (.setAll all?)
      (.setStashRef stash-id)
      (.call)))

(defn git-stash-pop
  "Apply and then drop the latest stash commit."
  [^Git repo]
  (git-stash-apply repo)
  (git-stash-drop repo))

(defn git-clean
  "Remove untracked files from the working tree.

    Options:

    :dirs?      If true directories are also cleaned. (default: false)
    :dry-run?   When true the paths in question will not actually be deleted.
                (default: false)
    :force?     If force is set, directories that are git repositories will also be
                deleted. (default: false)
    :ignore?    Don't report/clean files or dirs that are ignored by a `.gitignore`.
                (default: true)
    :paths      String or coll of strings with repository-relative paths to limit the
                cleaning to. (default: nil)
  "
  [^Git repo & {:keys [dirs? dry-run? force? ignore? paths]
                :or   {dirs?    false
                       dry-run? false
                       force?   false
                       ignore?  true
                       paths    nil}}]
  (as-> (.clean repo) ^CleanCommand cmd
        (.setCleanDirectories cmd dirs?)
        (.setDryRun cmd dry-run?)
        (.setForce cmd force?)
        (.setIgnore cmd ignore?)
        (if (some? paths)
          (.setPaths cmd (set (seq?! paths))) cmd)
        (.call cmd)))

(defn blame-result
  [^BlameResult blame]
  (.computeAll blame)
  (letfn [(blame-line [num]
            (when-let [commit (try
                                (.getSourceCommit blame num)
                                (catch ArrayIndexOutOfBoundsException _ nil))]
              {:author        (util/person-ident (.getSourceAuthor blame num))
               :commit        commit
               :committer     (util/person-ident (.getSourceCommitter blame num))
               :line          (.getSourceLine blame num)
               :line-contents (-> blame .getResultContents (.getString num))
               :source-path   (.getSourcePath blame num)}))
          (blame-seq [num]
            (when-let [cur (blame-line num)]
              (cons cur
                    (lazy-seq (blame-seq (inc num))))))]
    (blame-seq 0)))

(defonce diff-supported-algorithms
         {:histogram DiffAlgorithm$SupportedAlgorithm/HISTOGRAM
          :myers     DiffAlgorithm$SupportedAlgorithm/MYERS})

(defn git-blame
  "Show blame result for given `path`.

    Options:

    :diff-algo        Keyword that sets the used diff algorithm, supported algorithms:
                        :histogram
                        :myers
                      (default: nil)
    :follow-mv?       If true renames are followed using the standard FollowFilter
                      behavior used by RevWalk (which matches git log --follow in the
                      C implementation). This is not the same as copy/move detection
                      as implemented by the C implementation's of git blame -M -C.
                      (default: false)
    :jgit?            If true returns the JGit object instead. (default: false)
    :reverse          Compute reverse blame (history of deletes). Map of format
                      {:start Resolvable :end Resolvable}:
                        :start  Oldest commit to traverse from. Result file will be
                                loaded from this commit's tree.
                        :end    Most recent commit(s) to stop traversal at. Usually
                                an active branch tip, tag, or HEAD. Accepts a single
                                Resolvable or a coll with those.
                      (default: nil)
    :start            A Resolvable that sets the start commit. (default: nil)
    :text-comparator  Pass a JGit RawTextComparator. (default: nil)
  "
  [^Git repo path & {:keys [diff-algo follow-mv? jgit? reverse start text-comparator]
                     :or   {diff-algo       nil
                            follow-mv?      false
                            jgit?           false
                            reverse         nil
                            start           nil
                            text-comparator nil}}]
  (as-> (.blame repo) cmd
        (.setFilePath cmd path)
        (if (some? diff-algo)
          (.setDiffAlgorithm cmd (diff-algo diff-supported-algorithms)) cmd)
        (.setFollowFileRenames cmd follow-mv?)
        (if (some? reverse)
          (.reverse cmd
                    ^AnyObjectId (resolve-object (:start reverse) repo)
                    (->> (:end reverse) seq?! ^ObjectId (map #(resolve-object % repo))))
          cmd)
        (if (some? start)
          (.setStartCommit cmd (resolve-object start repo)) cmd)
        (if (some? text-comparator)
          (.setTextComparator cmd text-comparator) cmd)
        (.call cmd)
        (if jgit?
          cmd
          (blame-result cmd))))

(defn get-blob-id ^ObjectId [^Git repo ^RevCommit commit ^String path]
  (let [tree-walk (TreeWalk/forPath (.getRepository repo) path (.getTree commit))]
    (when tree-walk
      (.getObjectId tree-walk 0))))

(defn get-blob
  [repo commit path]
  (when-let [blob-id (get-blob-id repo commit path)]
    (.getName blob-id)))

(defn git-notes
  "Return list of note objects for given `:ref`.

    Options:

    :ref    The name of the ref in \"refs/notes/\" to read notes from. Note, the default
            value of JGit's Constants.R_NOTES_COMMITS will be used if nil is passed.
            (default: \"commits\")
  "
  ([^Git repo & {:keys [^String ref]
                 :or   {ref "commits"}}]
   (-> (.notesList repo)
       (.setNotesRef (str "refs/notes/" ref))
       .call)))

(defn git-notes-show
  "Return note string for given `:ref`.

    Options:

    :ref    The name of the ref in \"refs/notes/\" to read notes from. Note, the default
            value of JGit's Constants.R_NOTES_COMMITS will be used if nil is passed.
            (default: \"commits\")
  "
  [^Git repo & {:keys [^String ref]
                :or   {ref "commits"}}]
  (let [repository (-> repo .getRepository)]
    (->> (git-notes repo :ref ref)
         (map #(String. (.getBytes (.open repository (.getData ^Note %))) (StandardCharsets/UTF_8)))
         (map #(str/split % #"\n"))
         first)))

(defn git-notes-add
  "Add a note for a given `:commit` and `:ref`, replacing any existing note for that commit.

    Options:

    :commit The RevCommit object the note should be added to. When nil the current \"HEAD\"
            is used. (default: nil)
    :ref    The name of the ref in \"refs/notes/\" to read notes from. Note, the default
            value of JGit's Constants.R_NOTES_COMMITS will be used if nil is passed.
            (default: \"commits\")
  "
  [^Git repo ^String message & {:keys [^RevCommit commit ^String ref]
                                :or   {commit nil
                                       ref    "commits"}}]
  (-> (.notesAdd repo)
      (.setMessage message)
      (.setNotesRef (str "refs/notes/" ref))
      (.setObjectId (or commit (get-head-commit repo)))
      .call))

(defn git-notes-append
  "Append a note for a given `:commit` and `:ref`, given message is concatenated with a `\n` char.

    Options:

    :commit The RevCommit object the note should be added to. When nil the current \"HEAD\"
            is used. (default: nil)
    :ref    The name of the ref in \"refs/notes/\" to read notes from. Note, the default
            value of JGit's Constants.R_NOTES_COMMITS will be used if nil is passed.
            (default: \"commits\")
  "
  [^Git repo ^String message & {:keys [^RevCommit commit ^String ref]
                                :or   {commit nil
                                       ref    "commits"}}]
  (as-> (git-notes-show repo :ref ref) $
        (conj $ message)
        (str/join "\n" $)
        (git-notes-add repo $ :ref ref :commit commit)))
