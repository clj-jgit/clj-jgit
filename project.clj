(defproject clj-jgit "1.1.0"
  :description "Clojure wrapper for JGit"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.11.2"]
                                  [org.clojure/tools.namespace "1.3.0"]]
                   :source-paths ["dev/src" "test"]}}
  :repl-options {:init-ns dev}
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :plugins [[lein-marginalia "0.9.1"]
            [lein-tools-deps "0.4.5"]]
  :lein-tools-deps/config {:config-files [:install :user :project]}
  :repositories {"jgit-repository" "https://repo.eclipse.org/content/groups/releases/"})
