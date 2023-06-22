(ns clj-jgit.diff
  (:import (java.io ByteArrayOutputStream OutputStream)
           (java.util List)
           (org.eclipse.jgit.diff DiffFormatter EditList HistogramDiff RawText RawTextComparator)
           (org.eclipse.jgit.treewalk.filter TreeFilter)))

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

(defn get-diff-formatter
  "Returns a new `org.eclipse.jgit.diff.DiffFormatter` instance. Note that depending on the used `format` method some
  options are ignored, i.e. setting a `prefix` or `path-filter` has no effect when formatting raw-text-diffs.

    Options:
    :abbreviation-length    `integer` that sets the number of digits to show for commit-ids.
                            (default: 7)
    :binary-file-threshold  `integer` that sets the maximum file size for text files, in bytes.
                            Files larger than this size will be assumed to be binary, even if they aren't.
                            (default: 52428800)
    :context-lines          `integer` that sets the number of lines of context shown before and after a modification.
                            (default: 3)
    :detect-renames?        `boolean` that sets rename detection. Ignored if `repository` is not set. Once enabled the
                            detector can be configured away from its defaults by obtaining the instance directly by
                            invoking `getRenameDetector()` on the formatter instance. Ignored when `:repository` is nil.
                            (default: false)
    :monitor                Set a progress monitor. See JGit ProgressMonitor interface.
                            (default: nil)
    :output-stream          The `OutputStream` instance the formatted diff is written to.
                            (default: (ByteArrayOutputStream.))
    :path-filter            An `org.eclipse.jgit.treewalk.filter.TreeFilter` instance that limits the result, i.e.:
                            `(PathFilter/create \"project.clj\")`. See `org.eclipse.jgit.treewalk.filter` namespace
                            for all available filters or implement your own.
                            (default: PathFilter/ALL)
    :prefix-new             A purely cosmetic `string` used as path prefix for the new side in the output. Can be empty
                            but not nil.
                            (default: \"b/\")
    :prefix-old             A purely cosmetic `string` used as path prefix for the old side in the output. Can be empty
                            but not nil.
                            (default: \"a/\")
    :quote-paths?           `boolean` that sets whether path names should be quoted. By default, the setting of
                            `git config core.quotePath` is used. Ignored when `:repository` is nil.
    :repository             `Repository` instance the formatter can load object contents from. Once a repository has
                            been set, the formatter must be released to ensure the internal ObjectReader is able to
                            release its resources.
                            (default: nil)
  "
  ^DiffFormatter [& {:keys [abbreviation-length binary-file-threshold context-lines detect-renames? monitor
                            output-stream path-filter quote-paths? repository]
                     :or   {abbreviation-length   7
                            binary-file-threshold 52428800
                            context-lines         3
                            detect-renames?       nil
                            monitor               nil
                            output-stream         (ByteArrayOutputStream.)
                            path-filter           TreeFilter/ALL
                            quote-paths?          nil
                            repository            nil}}]
  (let [formatter (doto (DiffFormatter. output-stream)
                    (.setAbbreviationLength abbreviation-length)
                    (.setBinaryFileThreshold binary-file-threshold)
                    (.setContext context-lines)
                    (.setProgressMonitor monitor)
                    (.setPathFilter path-filter))]
    (when repository
      (.setRepository formatter repository)
      (when (some? detect-renames?) (.setDetectRenames formatter detect-renames?))
      (when (some? quote-paths?) (.setQuotePaths formatter quote-paths?)))
    formatter))

(defn diff-string-formatted
  "Compare given strings `a` and `b` and write a patch-style formatted string to given `:output-stream`. Always returns
  the `output-stream`.

  Options:

  :comparator-mode  Keyword that sets how whitespace changes are handled:
                      `:ws-ignore-all`      Ignores all whitespace
                      `:ws-ignore-leading`  Ignore leading whitespace
                      `:ws-ignore-trailing` Ignores trailing whitespace
                      `:ws-ignore-change`   Ignores whitespace occurring between non-whitespace characters
                      `:default`            No special treatment
  :context-lines    `integer` that sets the number of lines of context shown before and after a modification.
                    (default: 3)
  :output-stream    The `OutputStream` instance the formatted diff is written to. Note that the returned stream is not
                    flushed or closed, when in doubt use `with-open`.
                    (default: (ByteArrayOutputStream.))
  "
  ^OutputStream [a b & {:keys [comparator-mode context-lines output-stream]
                        :or   {comparator-mode :default
                               context-lines    3
                               output-stream   (ByteArrayOutputStream.)}}]
  (let [raw-a (get-raw-text a)
        raw-b (get-raw-text b)
        edit-list (diff-raw-text raw-a raw-b :comparator-mode comparator-mode)]
    (-> (get-diff-formatter :output-stream output-stream :context-lines context-lines)
        (.format edit-list raw-a raw-b))
    output-stream))

#_{:clj-kondo/ignore [:unresolved-symbol]}
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
