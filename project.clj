(defproject clj-jgit "1.0.0-beta2"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.eclipse.jgit/org.eclipse.jgit "5.4.0.201906121030-r"]
                 [org.eclipse.jgit/org.eclipse.jgit.ssh.apache "5.4.0.201906121030-r"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.10.1"]]}}
  :plugins [[lein-marginalia "0.9.1"]]
  :repositories {"jgit-repository" "https://repo.eclipse.org/content/groups/releases/"})
