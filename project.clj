(defproject clj-jgit "0.3.6"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.eclipse.jgit "2.3.1.201302201838-r"]
                 [org.clojure/core.memoize "0.5.2"]]
  :profiles {:dev {:dependencies [[midje "1.5.1"]
                                  [com.stuartsierra/lazytest "1.2.3"]
                                  [lein-clojars "0.9.0"]
                                  [fs "1.3.2"]]
                   :plugins [[lein-midje "3.0.1"]
                             [lein-marginalia "0.7.1"]]}}
  :repositories {"stuartsierra-releases" "http://stuartsierra.com/maven2"
                 "jgit-repository" "http://download.eclipse.org/jgit/maven"})
