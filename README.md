# clj-jgit #

Clojure wrapper for using the JGit library to manipulate Git repositories in a "pure Java" fashion.

## Quickstart Tutorial ##

This brief tutorial will show you how to: 

1. Clone a remote repository
2. Create a local branch for your changes
3. Checkout that branch, and
4. Add and commit those changes

```clj
;; Clone a repository into a folder of my choosing
(def my-repo
  (git-clone-full "https://github.com/semperos/clj-jgit.git" "local-folder/clj-jgit")
;=> #<Git org.eclipse.jgit.api.Git@1689405>

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
```

## Detailed Usage ##

Currently, this library leverages the "porcelain" API exposed by JGit, which allows the use of methods/functions similar to the ones available on the command-line when using Git for "basic" purposes. If enough interest arises, this tool will wrap more of the lower-level functions in the future, but for now it acts as a basic Java replacement for the command-line version of Git.

### Cloning a Repository ###

```clj
(git-clone-full "url-to-read-only-repo" "optional-local-folder")
```

JGit's default `git-clone` simply clones the `.git` folder, but doesn't pull down the actual project files. This library's `git-clone-full` function, on the other hand, performs a `git-clone` following by a `git-fetch` of the master branch and a `git-merge`.

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

This uses a lower level JGit API, so it's not entirely compatible with the API shown above. Porcelain API used above is using
Git class instance as a repo handler, internal JGit API uses instances of Repository class.

```clj
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
  (println repo)
  ;=> {:raw #<FileRepository Repository[/Users/railsmonk/clj-jgit/.git]>, 
  ;    :git #<Git org.eclipse.jgit.api.Git@96f1ee5>, 
  ;    :walk #<RevWalk org.eclipse.jgit.revwalk.RevWalk@5e555139>}
)

;; Returns a universal handler for a repo (can be used only for low-level API)
(def repo (universal-repo "/path/to/a/repo"))
```

```clj
;; List of pairs of branch refs and RevCommits associated with them
(branch-list-with-heads repo)
;=> ([#<org.eclipse.jgit.storage.file.RefDirectory$LooseUnpeeled, Name: refs/heads/master, ObjectId: 3b9c98bc151bb4920f9799cfa6c32c536ed64348> 
      #<RevCommit commit 3b9c98bc151bb4920f9799cfa6c32c536ed64348 1339922123 -----p>])

;; Find a ObjectId instance for a repo and given commit-ish
(def commit-obj-id (find-object-id repo "38dd57264cf5c05fb77211c8347d1f16e4474623"))
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
(commit-info repo (find-rev-commit repo "38dd57264cf"))

; Returns
{:repo {:git #<Git org.eclipse.jgit.api.Git@21d306ef>, 
        :raw #<FileRepository Repository[/Users/railsmonk/clj-jgit/.git]>, 
        :walk #<RevWalk org.eclipse.jgit.revwalk.RevWalk@256c4642>}, 
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
  (map #(commit-info repo) (git-log (:git repo))))
```
### Making Changes ###

TODO

## Caveat Windows Users

Cygwin will cause this library to hang. Make sure to remove `C:\cygwin\bin` from your PATH before attempting to use this library.

## License

Copyright (C) 2011 FIXME

Distributed under the Eclipse Public License, the same as Clojure.
