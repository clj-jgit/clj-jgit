(defproject clj-jgit "0.1"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.eclipse.jgit "1.3.0.201202151440-r"]
                 [org.clojure/core.memoize "0.5.1"]]
  :dev-dependencies [[com.stuartsierra/lazytest "1.2.3"]
                     [midje "1.4.0"]
                     [lein-marginalia "0.7.1"]
                     [lein-clojars "0.9.0"]
                     [lein-midje "1.0.10"]]
  :repositories {"stuartsierra-releases" "http://stuartsierra.com/maven2"
                 "jgit-repository" "http://download.eclipse.org/jgit/maven"})
