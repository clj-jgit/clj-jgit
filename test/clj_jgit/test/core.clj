(ns clj-jgit.test.core
  (:use [clj-jgit.util])
  (:use [clojure.test]))

(deftest test-name-from-uri
  (let [uris ["ssh://example.com/~/www/project.git"
              "~/your/repo/path/project.git"
              "http://git.example.com/project.git"
              "https://git.example.com/project.git"]]
    (is (every? #(= %1 "project") (map #(name-from-uri %) uris))
        "Repository name must be 'project'")))

(deftest )
