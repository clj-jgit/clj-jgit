# clj-jgit

Clojure wrapper for using the JGit library to manipulate Git repositories in a "pure Java" fashion.

You can view latest auto-generated API documentation here: [clj-jgit.github.io/clj-jgit](http://clj-jgit.github.io/clj-jgit).

## Installation ##

Last stable version is available on [Clojars](http://clojars.org/clj-jgit).

[![Clojars Project](http://clojars.org/clj-jgit/latest-version.svg)](http://clojars.org/clj-jgit)

Note: If you don't plan on signing commits you may exclude `org.eclipse.jgit/org.eclipse.jgit.gpg.bc`, i.e. for lein:

```
:dependencies [[clj-jgit "1.x.x" :exclusions [org.eclipse.jgit/org.eclipse.jgit.gpg.bc]]]
```

Note: Java 11 or newer is required since `clj-jgit-1.0.2`

## Quickstart Tutorial ##

This brief tutorial will show you how to:

1. Clone a remote repository
2. Create a local branch for your changes
3. Checkout that branch, and
4. Add and commit those changes
5. Manage tags

```clj
;; First require or use the porcelain namespace, assuming a quick REPL session:
(use 'clj-jgit.porcelain)

;; Clone a repository into a folder of my choosing and keep the jgit repository object at my-repo
(def my-repo (git-clone "https://github.com/clj-jgit/clj-jgit.git" :dir "local-folder/clj-jgit"))
;=> #'user/my-repo

;; A bit redundant for a fresh repo, but always good to check the repo status
;; before making any changes
(git-status my-repo)
;=> {:added #{}, :changed #{}, :missing #{}, :modified #{}, :removed #{}, :untracked #{}}

;; List existing branches
(git-branch-list my-repo)
;=> ("master")

;; Create a new local branch to store our changes
(git-branch-create my-repo "my-branch")
;=> #<org.eclipse.jgit.internal.storage.file.RefDirectory$LooseUnpeeled, Name: refs/heads/my-branch, ...>

;; Prove to ourselves that it was created
(git-branch-list my-repo)
;=> ("master" "my-branch")

;; Check out our new branch

(git-checkout my-repo "my-branch")
;=> #<org.eclipse.jgit.internal.storage.file.RefDirectory$LooseUnpeeled, Name: refs/heads/my-branch, ...>

;; Now go off and make your changes.
;; For example, let's say we added a file "foo.txt" at the base of the project.
(git-status my-repo)
;=> {:added #{}, :changed #{}, :missing #{}, :modified #{}, :removed #{}, :untracked #{"foo.txt"}}

;; Add the file to the index
(git-add my-repo "foo.txt")
;=> #object[org.eclipse.jgit.dircache.DirCache 0x3d9e3c3a "org.eclipse.jgit.dircache.DirCache@3d9e3c3a"]

;; Check for status change
(git-status my-repo)
;=> {:added #{"foo.txt"}, :changed #{}, :missing #{}, :modified #{}, :removed #{}, :untracked #{}}

;; Now commit your changes, specifying author and committer if desired
(git-commit my-repo "Add file foo.txt" :committer {:name "Daniel Gregoire" :email "daniel@example.com"})
;=> #object[org.eclipse.jgit.revwalk.RevCommit 0x7283e79c "commit b6feeb3baab8fa0422390b2d2a737b1e21610401 -----p"]

;; Status clean
(git-status my-repo)
;=> {:added #{}, :changed #{}, :missing #{}, :modified #{}, :removed #{}, :untracked #{}}

(git-clean my-repo :dirs? true, :ignore? true)
;=> #{}

;; Blame
(first (git-blame my-repo "README.md"))
;=> {:author {:name "Ilya Sabanin",
              :email "ilya@sabanin.com",
              :timezone #<ZoneInfo ...>},
     :commit #<RevCommit commit fabdf5cf4abb72231461177238349c21e23fa46a 1352414190 -----p>,
     :committer {:name "Ilya Sabanin",
                 :email "ilya@wildbit.com",
                 :timezone #<ZoneInfo ...>},
     :line 66,
     :source-path "README.md"}

;; Tagging
(git-tag-create my-repo "v2.0.0")
;=> #<org.eclipse.jgit.internal.storage.file.RefDirectory$LooseUnpeeled, Name: refs/tags/v2.0.0, ObjectId: ...
(git-tag-list my-repo)
;=> ("v0.0.1" "v0.0.2" "v0.0.3" "v0.8.10" "v2.0.0")
(git-tag-delete my-repo "v2.0.0")
;=> ["refs/tags/v2.0.0"]
(git-tag-list my-repo)
;=> ("v0.0.1" "v0.0.2" "v0.0.3" "v0.8.10")
```

## Detailed Usage ##

Currently, this library leverages the "porcelain" API exposed by JGit, which allows the use of methods/functions similar
to the ones available on the command-line when using Git for "basic" purposes. If enough interest arises, this tool will
wrap more of the lower-level functions in the future, but for now it acts as a basic Java replacement for the
command-line version of Git.

### Cloning a Repository ###

```clj
(git-clone "url-to-repo" :dir "optional-local-folder")
```

### Authentication

Per default JGit expects a standard SSH key setup in `~/.ssh`, things should just work unless your key is password
protected. For further configuration any command may be wrapped with the `with-credentials` or `with-identity` macro:

```clj
;; Use custom SSH key location with key pw, also accept all unknown server keys and add them to `known_hosts` file:
(with-identity {:name "my.key" :key-dir "/some/path" pw: "$ecRet" :trust-all? true}
  (git-push my-repo :tags true))

;; Use user/pw auth instead of key based auth
(with-credentials {:login "someuser" :pw "$ecReT"}
  (git-pull my-repo))
```

### GPG signing of Commits

A working GPG key setup is required for signing:

```
$ gpg --gen-key
$ gpg --fingerprint
pub   rsa4096 2019-12-01 [SC]
      20AB A393 B992 6661 63BE  1346 5022 F878 A751 2BA8 <- the fingerprint
uid           [ultimate] Foo Bar <foo@bar.net>
sub   rsa4096 2018-12-01 [E]
```

GPG signing is used if:

* enabled per commit: `(git-commit ... :signing? true :signing-key "A7512BA8")`
* git is configured via `commit.gpgSign = true`, i.e.:

```
(-> my-repo
    git-config-load
    (git-config-set "commit.gpgSign" true)
    (git-config-set "user.signingKey" "A7512BA8")
    git-config-save)
```

Note: You may use as few as 2 digits of the fingerprint as long the id is unique in your keyring, no spaces.

If GPG-signing a commit is requested but no GpgSigner is installed, an `org.eclipse.jgit.api.errors.ServiceUnavailableException` will be thrown. 

### Loading an Existing Repository ###

In order to use most of the functions in JGit's API, you need to have a repository object to play with. Here are ways to
load an existing repository:

```clj
;; Simples method is to point to the folder
(load-repo "path-to-git-repo-folder")
;; In order to remain consistent with JGit's default behavior,
;; you can also point directly at the .git folder of the target repo
(load-repo "path-to-git-repo-folder/.git")
```

This function throws a `FileNotFoundException` if the directory in question does not exist.

### Querying repository

This uses internal JGit API, so it may require some additional knowledge of JGit.

```clj
(ns clj-jgit.porcelain)

;; Log
(git-log my-repo :max-count 1)
;=> ({:id #object[org.eclipse.jgit.revwalk.RevCommit], :msg "fix git-commit docs, typo in readme", :author ...

;; Log for range
(git-log my-repo :since "5a7b97" :until "master^1")
;=> ...
```

```clj
;; This macro allows you to create a universal handler with name "repo"
(with-repo "/path/to/a/repo"
  (git-log repo))
```

```clj
;; Notes Add
(git-notes-add my-repo "my note")
;=> (#object[org.eclipse.jgit.notes.Note 0x1237ddce "Note[3c14d1f8917761rc71db03170866055ef88b585f -> 10500018fca9b3425b50de67a7258a12cba0c076]"])
```

```clj
;; Notes Append
(git-notes-add my-repo "my appended note")
;=> (#object[org.eclipse.jgit.notes.Note 0x1237ddce "Note[3c14d1f8917761rc71db03170866055ef88b585f -> 10500018fca9b3425b50de67a7258a12cba0c076]"])
```

```clj
;; Notes List
(git-notes my-repo)
;=> (#object[org.eclipse.jgit.notes.Note 0x1237ddce "Note[3c14d1f8917761rc71db03170866055ef88b585f -> 10500018fca9b3425b50de67a7258a12cba0c076]"])
```

```clj
;; Notes Show
(git-notes-show my-repo)
;=> ["my note" "my appended note"]
```

```clj
(ns clj-jgit.querying)

;; Creates a RevWalk instance needed to traverse the commits for the repo.
;; Commits found through a RevWalk can be compared and used only with other
;; commits found with a same RevWalk instance.
(def rev-walk (new-rev-walk repo))

;; List of pairs of branch refs and RevCommits associated with them
(branch-list-with-heads repo rev-walk)
;=> ([#<org.eclipse.jgit.storage.file.RefDirectory$LooseUnpeeled, Name: refs/heads/master, ObjectId: 3b9c98bc151bb4920f9799cfa6c32c536ed64348>
      #<RevCommit commit 3b9c98bc151bb4920f9799cfa6c32c536ed64348 1339922123 -----p>])

;; Find an ObjectId instance for a repo and given commit-ish, tree-ish or blob
(def commit-obj-id (resolve-object repo "38dd57264cf5c05fb77211c8347d1f16e4474623"))
;=> #<ObjectId AnyObjectId[38dd57264cf5c05fb77211c8347d1f16e4474623]>

;; Show all the branches where commit is present
(branches-for repo commit-obj-id)
;=> ("refs/heads/master")

;; List of all revision objects in the repository, for all branches
(rev-list repo)
;=> #<RevCommitList [commit 3b9c98bc151bb4920f9799cfa6c32c536ed64348 1339922123 ----sp, ... ]>
```

```clj
;; Gather information about specific commit
(commit-info repo (find-rev-commit repo rev-walk "38dd57264cf"))

; Returns
{:repo #<Git org.eclipse.jgit.api.Git@21d306ef>,
:changed_files [[".gitignore" :add]
                ["README.md" :add]
                ["project.clj" :add]
                ["src/clj_jgit/core.clj" :add]
                ["src/clj_jgit/util/print.clj" :add]
                ["test/clj_jgit/test/core.clj" :add]],
:raw #<RevCommit commit 38dd57264cf5c05fb77211c8347d1f16e4474623 1304645414 ----sp>,
:author "Daniel Gregoire",
:email "daniel.l.gregoire@gmail.com",
:message "Initial commit",
:branches ("refs/heads/master"),
:merge false,
:time #<Date Fri May 06 09:30:14 KRAT 2011>,
:id "38dd57264cf5c05fb77211c8347d1f16e4474623"}

;; You can also combine this with Porcelain API, to get a list of all commits in a repo with detailed information
(with-repo "/path/to/repo.git"
  (map #(commit-info repo %) (git-log repo)))

;; Branches lookup is an expensive operation, especially for repos with many branches.
;; commit-info spends most of it time trying to detect list of branches commit belongs to.

; If you don't require branches list in commit info, you can use:
(commit-info-without-branches repo rev-walk rev-commit)

; If you want branches list, but want it to work faster, you can generate commit map that turns
; commits and branches into a map for fast branch lookups. In real-life this can give 30x-100x speed
; up when you are traversing lists of commits, depending on amount of branches you have.
(let [rev-walk (new-rev-walk repo)
      commit-map (build-commit-map repo rev-walk)
      commits (git-log repo)]
  (map (partial commit-info repo rev-walk commit-map) commits))
```
### Contribute ###

If you want to contribute just fork the repository, work on the code, cover it with tests and submit a pull request through Github.

Any questions related to clj-jgit can be discussed in the [Google Group](https://groups.google.com/forum/#!forum/clj-jgit).

## Caveat Windows Users

Cygwin will cause this library to hang. Make sure to remove `C:\cygwin\bin` from your PATH before attempting to use this library.

## License

Copyright (C) 2019 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
