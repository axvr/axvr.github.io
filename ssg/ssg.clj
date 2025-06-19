(ns ssg
  "A simple Static Site Generator (SSG) for my personal website."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [markdown.core :as md]
            [hiccup.core :as hiccup])
  (:import (java.io File)
           (java.util Locale)
           (java.time ZoneId)
           (java.time.format DateTimeFormatter)))

;; TODO: Atom feed generation?

;; ----------------------------
;; Helpers.

(defn assocf [m k f & args]
  (if-let [r (apply f m args)]
    (assoc m k r)
    m))

(defn inject
  "Replace `{{x}}` tags in text with value of `:x` in replacements map."
  [text replacements]
  (str/replace text #"\{\{ *([\w/_.-]+) *\}\}" (comp str replacements keyword second)))

(defn date-formatter [pattern & {:keys [locale zone]}]
  (.. DateTimeFormatter
      (ofPattern pattern)
      (withLocale (or locale Locale/UK))
      (withZone (ZoneId/of (or zone "GMT")))))

(defn parse-date [date]
  (when date
    (let [date (if (re-find #"T" date) date (str date "T12:00:00Z"))
          fmt (date-formatter "yyyy-MM-dd'T'HH:mm[:ss[.SSS[SSS]]][z][O][X][x][Z]")]
      (.parse fmt date))))

;; ----------------------------
;; Markdown parsing.

(defn scrollable-tables
  "Wraps HTML and Markdown tables in a `div` of class `table-container`."
  [text state]
  (or (cond
        (:codeblock state) nil

        (str/starts-with? text "<table>")
        [(str "<div class=\"table-container\">" text "</div>") state]

        (str/starts-with? text "<p><table>")
        [(str/replace-first text #"<p>" "<div class=\"table-container\">") state]

        (str/ends-with? text "</table>")
        ;; FIXME: `:skip-next-line?` does not work, so insert `<p>` tag instead.
        [(str text "</div><p>") state])
      [text state]))

(defn parse-md-file [file]
  (md/md-to-html-string-with-meta
   (slurp file)
   :heading-anchors true
   :reference-links? true
   :footnotes? true
   :custom-transformers [scrollable-tables]))

;; ----------------------------
;; Page construction.

(defn build-title [{html-title :html/title, :keys [title site-name]}]
  (or html-title
      (and title (format "%s | %s" title site-name))
      site-name))

(defn build-preamble [{:keys [title subtitle published updated]}]
  (when title
    (hiccup/html
     [:div {:class "preamble"}
      [:h1 title]
      (when subtitle [:h2 subtitle])
      (when published
        (let [format-date #(.format (date-formatter "MMMM yyyy") (parse-date %))]
          [:time {:class "date"
                  :title (str published (when updated (format " (rev. %s" updated)))
                  :datetime published}
           (str (format-date published)
                (when updated
                  (format "&ensp;(rev. %s)" (format-date updated))))]))])))

(defn build-breadcrumbs [{:keys [path]}]
  (when (seq path)
    (hiccup/html
     (into [:nav {:class "bread"}]
           (comp
            (map #(str/replace % #"_" " "))
            (map-indexed (fn [idx segment]
                           (let [depth (- (count path) idx)]
                             (if (zero? depth)
                               segment
                               [:a {:href (apply str (repeat depth "../"))} segment]))))
            (interpose " &rsaquo; "))
           (cons "home" path)))))

(defn build-page [page]
  ;; TODO: spec validation.
  ;; {:post [(s/valid? )]}
  (-> page
      (update :path #(let [l (fs/strip-ext (peek %)), p (pop %)]
                       (if (= l "index") p (conj p l))))
      (assocf :output-file #(apply fs/file (flatten [(:output-dir %) (:path %) "index.html"])))
      (update :og/type #(or % "website"))
      (assocf :html/title build-title)
      (assocf :html/preamble build-preamble)
      (assocf :html/breadcrumbs build-breadcrumbs)
      (assocf :html/output #(inject (:template %) %))))

;; ----------------------------
;; File processing.

(defn write-page! [{:keys [:html/output output-file :html/breadcrumbs]}]
  (fs/create-dirs (fs/parent output-file))
  (spit output-file output))

(defmulti process-file! :file-type)

(defmethod process-file! :default [{:keys [input-file output-file]}]
  (fs/create-dirs (fs/parent output-file))
  (fs/copy input-file output-file))

(defmethod process-file! "md"
  [{:as conf, :keys [input-file template]}]
  (let [{:keys [html metadata]} (parse-md-file input-file)]
    (-> conf
        (merge metadata {:html/content html})
        build-page
        write-page!)))

(defmethod process-file! "edn"
  [{:as conf, :keys [input-file template]}]
  (-> conf
      (merge (-> input-file slurp edn/read-string eval))
      (update :html/content #(hiccup/html %))
      build-page
      write-page!))

(defn path-breakdown [prefix f]
  (into []
        (map str)
        (-> (str f)
            (str/replace-first (str/re-quote-replacement (str prefix)) "")
            fs/components)))

(defn build [conf]
  (let [{:keys [input-dir output-dir exclusions]} conf]
    (fs/delete-tree output-dir)
    (doseq [f (file-seq input-dir)]
      (when (fs/regular-file? f)
        (let [path (path-breakdown input-dir f)]
          (when-not (or (re-find #"^\." (first path))
                        (exclusions (first path)))
            (-> conf
                (assoc :path        path
                       :file-type   (fs/extension f)
                       :input-file  f
                       :output-file (apply fs/file output-dir path))
                process-file!)))))))

(build
 {:site-name   "Alex Vear"
  :site-url    "https://www.alexvear.com"
  :description "Some default description."
  :template    (slurp "template.html")
  :input-dir   (fs/file "..")
  :output-dir  (fs/file ".." ".dist")
  :exclusions  #{"README.md" "COPYING" "TODO" "do" "ssg"}})
