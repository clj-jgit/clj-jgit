(defproject clj-jgit "0.8.8"
  :description "Clojure wrapper for JGit"
  :dependencies [[org.eclipse.jgit/org.eclipse.jgit "4.8.0.201706111038-r" :exclusions [com.jcraft/jsch]]
                 [fs "1.3.3"]
                 [com.jcraft/jsch "0.1.54"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [midje "1.5.1"]
                                  [com.stuartsierra/lazytest "1.2.3" :exclusions [org.clojure.contrib/find-namespaces
                                                                                  org.clojure/clojure]]
                                  [lein-clojars "0.9.0"]]} }
  :plugins [[lein-midje "3.0.1"]
            [lein-marginalia "0.7.1"]]
  :repositories {"stuartsierra-releases" "http://stuartsierra.com/maven2"
                 "jgit-repository" "https://repo.eclipse.org/content/groups/releases/"})
