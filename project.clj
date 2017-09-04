(defproject clj-jgit "0.9.1-SNAPSHOT"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.eclipse.jgit/org.eclipse.jgit "4.8.0.201706111038-r" :exclusions [com.jcraft/jsch]]
                 [fs "1.3.3"]
                 [com.jcraft/jsch "0.1.54"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]]}}
  :plugins [[lein-marginalia "0.9.0"]]
  :repositories {"jgit-repository" "https://repo.eclipse.org/content/groups/releases/"})
