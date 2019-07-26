(ns clj-jgit.util
  (:require [clojure.java.io :as io])
  (:import (java.io IOException)
           (org.eclipse.jgit.internal.storage.file RefDirectory$LooseRef)
           (org.eclipse.jgit.lib PersonIdent)))

(defn recursive-delete-file
  "Delete file `f`. If `f` is a directory, recursively deletes all files and directories within the directory. Raises an
  exception if it fails, unless `silently` is true."
  [f & [silently]]
  (let [file (io/file f)]
    (if (.exists file)
      (do (doseq [child (reverse (file-seq file))]
            (io/delete-file child silently))
          true)
      (or silently
          (throw (IOException. (str "Couldn't find " f)))))))

(defn seq?!
  "If given `obj` isn't sequential? returns a vec with `obj` as single element, else just returns it's input."
  [obj]
  (if (sequential? obj) obj (vector obj)))

(defn doseq-cmd-fn!
  "Repeatedly executes function `f` for each entry in `param-seq`. The function is passed the `cmd-instance` as first
  arg and a single `param-seq` entry as second arg. If `param-seq` isn't sequential? it's wrapped into a vector.
  Returns given `cmd-instance`.

  Example that executes `.addFilepattern` on a JGit AddCommand instance for each given file, nicely threaded:

      (-> (.add repo)
        (doseq-cmd-fn! #(.addFilepattern ^AddCommand %1 %2) [\"file1.txt\" \"file2.txt\"])
        (.call))
  "
  [cmd-instance f param-seq]
    (doseq [p (seq?! param-seq)]
      (f cmd-instance p))
    cmd-instance)

(defn name-from-uri
  "Given a URI to a Git resource, derive the name (for use in cloning to a directory)"
  [uri]
  (second (re-find #"/([^/]*)\.git$" uri)))

(defmacro when-present
  "Special `when` macro for checking if an attribute isn't available or is an empty string"
  [obj & body]
  `(when (not (or (nil? ~obj) (empty? ~obj)))
     ~@body))

(defmethod print-method RefDirectory$LooseRef
  [^RefDirectory$LooseRef o w]
  (print-simple
    (str "#<" (.replaceFirst (str (.getClass o)) "class " "") ", "
         "Name: " (.getName o) ", "
         "ObjectId: " (.getName (.getObjectId o)) ">") w))

(defn normalize-path
  "Removes a leading slash from a path"
  [path]
  (if (= path "/")
    "/"
    (if (= (first path) \/)
      (subs path 1)
      path)))

(defn person-ident
  "Convert given JGit `PersonIdent` object into a map"
  [^PersonIdent person]
  (when person
    {:name     (.getName person)
     :email    (.getEmailAddress person)
     :date     (.getWhen person)
     :timezone (.getTimeZone person)}))
