(defproject clj-jgit "0.3.9"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.eclipse.jgit "2.3.1.201302201838-r"]
                 [org.clojure/core.memoize "0.5.3"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.5.1"]
                                  [midje "1.5.1"]
                                  [com.stuartsierra/lazytest "1.2.3"]
                                  [lein-clojars "0.9.0"]
                                  [fs "1.3.2"]]} }
  :plugins [[lein-midje "3.0.1"]
            [lein-marginalia "0.7.1"]]
  :repositories {"stuartsierra-releases" "http://stuartsierra.com/maven2"
                 "jgit-repository" "http://download.eclipse.org/jgit/maven"})
