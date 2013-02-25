(defproject clj-jgit "0.2.1"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.eclipse.jgit "2.1.0.201209190230-r"]
                 [org.clojure/core.memoize "0.5.2"]]
  :dev-dependencies [[com.stuartsierra/lazytest "1.2.3"]
                     [fs "1.3.2"]
                     [midje "1.4.0"]
                     [lein-marginalia "0.7.1"]
                     [lein-clojars "0.9.0"]
                     [lein-midje "1.0.10"]]
  :repositories {"stuartsierra-releases" "http://stuartsierra.com/maven2"
                 "jgit-repository" "http://download.eclipse.org/jgit/maven"})
