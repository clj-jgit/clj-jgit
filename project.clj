(defproject clj-jgit "0.9.1-SNAPSHOT"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.eclipse.jgit/org.eclipse.jgit "4.11.0.201803080745-r" :exclusions [com.jcraft/jsch]]
                 [com.jcraft/jsch "0.1.54"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]]}}
  :plugins [[lein-marginalia "0.9.1"]]
  :repositories {"jgit-repository" "https://repo.eclipse.org/content/groups/releases/"})
