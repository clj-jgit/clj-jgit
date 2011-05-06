(defproject clj-jgit "0.0.1-SNAPSHOT"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.eclipse.jgit "0.12.1"]
                 [clj-file-utils "0.2.1"]]
  :dev-dependencies [[swank-clojure "1.4.0-SNAPSHOT"]]
  :repositories {"jgit-repository" "http://download.eclipse.org/jgit/maven"})
