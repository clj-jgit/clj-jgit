# clj-jgit 

Clojure wrapper for using the JGit library to manipulate Git repositories in a "pure Java" fashion.

You can view latest auto-generated API documentation here: [clj-jgit.github.io/clj-jgit](http://clj-jgit.github.io/clj-jgit).

## Installation ##

Last stable version is available on [Clojars](http://clojars.org/clj-jgit).

[![Clojars Project](http://clojars.org/clj-jgit/latest-version.svg)](http://clojars.org/clj-jgit)

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
(def my-repo
  (:repo (git-clone-full "https://github.com/clj-jgit/clj-jgit.git" "local-folder/clj-jgit")))
;=> #'user/my-repo

;; A bit redundant for a fresh repo, but always good to check the repo status
;; before making any changes
(git-status my-repo)
;=> {:untracked #{}, :removed #{}, :modified #{}, :missing #{}, :changed #{}, :added #{}}

;; List existing branches
(git-branch-list my-repo)
;=> (#<LooseUnpeeled Ref[refs/heads/master=526f58f0b09621ce27fbae575991c8311a515430]>)

;; Create a new local branch to store our changes
(git-branch-create my-repo "my-branch")
;=> #<LooseUnpeeled Ref[refs/heads/my-branch=526f58f0b09621ce27fbae575991c8311a515430]>

;; Prove to ourselves that it was created
(git-branch-list my-repo)
;=> (#<LooseUnpeeled Ref[refs/heads/master=526f58f0b09621ce27fbae575991c8311a515430]> #<LooseUnpeeled Ref[refs/heads/my-branch=526f58f0b09621ce27fbae575991c8311a515430]>)

;; Check out our new branch

(git-checkout my-repo "my-branch")
;=> #<LooseUnpeeled Ref[refs/heads/my-branch=526f58f0b09621ce27fbae575991c8311a515430]>

;; Now go off and make your changes.
;; For example, let's say we added a file "foo.txt" at the base of the project.
(git-status my-repo)
;=> {:untracked #{"foo.txt"}, :removed #{}, :modified #{}, :missing #{}, :changed #{}, :added #{}}

;; Add the file to the index
(git-add my-repo "foo.txt")
;=> #<DirCache org.eclipse.jgit.dircache.DirCache@81db25>

;; Check for status change
(git-status my-repo)
;=> {:untracked #{}, :removed #{}, :modified #{}, :missing #{}, :changed #{}, :added #{"foo.txt"}}

;; Now commit your changes, specifying author and committer if desired
(git-commit my-repo "Add file foo.txt" {"Daniel Gregoire" "daniel@example.com"})
;=> #<RevCommit commit 5e116173db370bf400b3514a4b093ec3d98a2666 1310135270 -----p>

;; Status clean
(git-status my-repo)
;=> {:untracked #{}, :removed #{}, :modified #{}, :missing #{}, :changed #{}, :added #{}}

(git-clean my-repo :clean-dirs? true, :ignore? true)
;=> ...

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
("v0.0.1" "v0.0.2" "v0.0.3" "v0.8.10" "v2.0.0")
(git-tag-delete my-repo "v2.0.0")
["refs/tags/v2.0.0"]
(git-tag-list my-repo)
("v0.0.1" "v0.0.2" "v0.0.3" "v0.8.10")
```

## Detailed Usage ##

Currently, this library leverages the "porcelain" API exposed by JGit, which allows the use of methods/functions similar to the ones available on the command-line when using Git for "basic" purposes. If enough interest arises, this tool will wrap more of the lower-level functions in the future, but for now it acts as a basic Java replacement for the command-line version of Git.

### Cloning a Repository ###

```clj
(git-clone-full "url-to-read-only-repo" "optional-local-folder")
```

JGit's default `git-clone` simply clones the `.git` folder, but doesn't pull down the actual project files. This library's `git-clone-full` function, on the other hand, performs a `git-clone` following by a `git-fetch` of the master branch and a `git-merge`.

### Authentication

Any command that may require authentication (clone, push, etc) can be wrapped with the `with-identity` macro.

```clj
;; Use current user's ssh key, no password
(with-identity {:name "~/.ssh/id_rsa" :exclusive true}
  (git-push my-repo :tags true))

;; Use some other key that has a password
(with-identity {:name "/path/to/the/private/key" :passphrase "$ecReT" :exclusive true}
  (git-pull my-repo))
```

### Loading an Existing Repository ###

In order to use most of the functions in JGit's API, you need to have a repository object to play with. Here are ways to load an existing repository:

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
(git-log my-repo)
;=> (#<RevCommit commit 36748f70c687e8d2bc92d262692cd03ffc6c2473 1304696936 ----sp> ...)

;; Log for range
(git-log my-repo "36748f70" "master^3")
;=> (#<RevCommit commit 36748f70c687e8d2bc92d262692cd03ffc6c2473 1304696936 ----sp> ...)
```

```clj
;; This macro allows you to create a universal handler with name "repo"
(with-repo "/path/to/a/repo"
  (git-log repo))
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

Copyright (C) 2011 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
