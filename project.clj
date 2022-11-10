(defproject clj-jgit "1.1.0-SNAPSHOT"
  :description "Clojure wrapper for JGit"
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.11.1"]
                                  [org.clojure/tools.namespace "1.3.0"]]
                   :source-paths ["dev/src" "test"]}}
  :repl-options {:init-ns dev}
  :middleware [lein-tools-deps.plugin/resolve-dependencies-with-deps-edn]
  :plugins [[lein-marginalia "0.9.1"]
            [lein-tools-deps "0.4.5"]]
  :lein-tools-deps/config {:config-files [:install :user :project]}
  :repositories {"jgit-repository" "https://repo.eclipse.org/content/groups/releases/"})
