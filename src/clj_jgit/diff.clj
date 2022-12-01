(ns clj-jgit.diff
  (:import (java.io ByteArrayOutputStream OutputStream)
           (java.util List)
           (org.eclipse.jgit.diff DiffFormatter EditList HistogramDiff RawText RawTextComparator)))

(defonce raw-text-comparator-modes {:default            RawTextComparator/DEFAULT            ; No special treatment
                                    :ws-ignore-all      RawTextComparator/WS_IGNORE_ALL      ; Ignores all whitespace
                                    :ws-ignore-leading  RawTextComparator/WS_IGNORE_LEADING  ; Ignore leading whitespace
                                    :ws-ignore-trailing RawTextComparator/WS_IGNORE_TRAILING ; Ignores trailing whitespace
                                    :ws-ignore-change   RawTextComparator/WS_IGNORE_CHANGE}) ; Ignores whitespace occurring between non-whitespace characters

(defn get-raw-text
  "Returns a new `org.eclipse.jgit.diff.RawText` instance for given string `s`"
  ^RawText [^String s]
  (-> s .getBytes RawText.))

(defn diff-raw-text
  "Compare given `RawText` instances `a` and `b`, returns a new `org.eclipse.jgit.diff.EditList` instance"
  ^EditList [a b & {:keys [comparator-mode] :or {comparator-mode :default}}]
  (let [comparator (comparator-mode raw-text-comparator-modes)]
    (-> (HistogramDiff.)
        (.diff comparator a b))))

(defn diff-string
  "Compare given strings `a` and `b`, returns a new `org.eclipse.jgit.diff.EditList` instance"
  ^EditList [a b & {:keys [comparator-mode] :or {comparator-mode :default}}]
  (diff-raw-text (get-raw-text a) (get-raw-text b) :comparator-mode comparator-mode))

(defn diff-string-formatted
  "Compare given strings `a` and `b` and write a patch-style formatted string to given `:output-stream`. Always returns
  the `output-stream`.

  Options:

  :comparator-mode  Keyword that sets how whitespace changes are handled:
                    :ws-ignore-all      Ignores all whitespace
                    :ws-ignore-leading  Ignore leading whitespace
                    :ws-ignore-trailing Ignores trailing whitespace
                    :ws-ignore-change   Ignores whitespace occurring between non-whitespace characters
                    :default            No special treatment
  :output-stream    The `OutputStream` instance the formatted diff is written to. Note that the returned stream is not
                    flushed or closed, when in doubt use `with-open`.
                    (default: (ByteArrayOutputStream.))
  "
  ^OutputStream [a b & {:keys [comparator-mode output-stream]
                        :or   {comparator-mode :default
                               output-stream   (ByteArrayOutputStream.)}}]
  (let [raw-a (get-raw-text a)
        raw-b (get-raw-text b)
        edit-list (diff-raw-text raw-a raw-b :comparator-mode comparator-mode)]
    (doto (DiffFormatter. output-stream)
      (.format edit-list raw-a raw-b))
    output-stream))

(comment
  ; JGit also supports diffing of sequences, subsequences and hashed sequences. Each type requires prior implementation of
  ; its abstract classes, see https://download.eclipse.org/jgit/site/6.0.0.202111291000-r/apidocs/org/eclipse/jgit/diff/package-summary.html

  ; Simple example for diffing Sequences below, sadly `proxy` and `reify` don't support adding new methods to abstract classes.

  ; Note: requires aot compilation to generate the classes, i.e. for a lein project add `:aot [clj-jgit.diff]`

(gen-class
  :name clj-jgit.diff.Sequence
  :extends org.eclipse.jgit.diff.Sequence
  :state state
  :init init
  :prefix "seq-"
  :constructors {[java.util.List] []}
  :methods [[get [Integer] Object]])

(defn seq-init
  "Set internal `state` field to given seq `s`"
  [s]
  [[List] s])

(defn seq-size [this]
  (count (.state this)))

(defn seq-get [this i]
  (nth (.state this) i))

(gen-class
  :name clj-jgit.diff.SequenceComparator
  :extends org.eclipse.jgit.diff.SequenceComparator
  :prefix "seq-comp-")

(defn seq-comp-equals [_this col-a index-a col-b index-b]
  (= (.get col-a index-a)
     (.get col-b index-b)))

(defn seq-comp-hash [_this col index]
  (.hashCode (.get col index)))

(defn diff-seq
  "Compare given sequences `a` and `b`, returns a new `org.eclipse.jgit.diff.EditList` instance"
  ^EditList [a b]
  (-> (HistogramDiff.)
      (.diff (SequenceComparator.) (Sequence. a) (Sequence. b))))

)
