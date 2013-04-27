(ns clj-jgit.util)

(defn name-from-uri
  "Given a URI to a Git resource, derive the name (for use in cloning to a directory)"
  [uri]
  (second (re-find #"/([^/]*)\.git$" uri)))

(defmacro when-present
  "Special `when` macro for checking if an attribute isn't available or is an empty string"
  [obj & body]
  `(when (not (or (nil? ~obj) (empty? ~obj)))
     ~@body))

(defmethod print-method org.eclipse.jgit.storage.file.RefDirectory$LooseUnpeeled
  [o w]
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
      (apply str (rest path))
      path)))
