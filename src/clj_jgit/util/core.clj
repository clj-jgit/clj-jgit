(ns clj-jgit.util.core)

(defn name-from-uri
  "Given a URI to a Git resource, derive the name (for use in cloning to a directory)"
  [uri]
  (second (re-find #"/([^/]*)\.git$" uri)))