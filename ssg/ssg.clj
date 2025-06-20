(ns ssg
  "A simple Static Site Generator (SSG) for my personal website."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.data.xml :as xml]
            [babashka.fs :as fs]
            [markdown.core :as md]
            [hiccup.core :as hiccup])
  (:import (java.io File FileWriter)
           (java.util Locale)
           (java.time ZoneId Instant)
           (java.time.format DateTimeFormatter)))

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
;; Atom feed construction.

(xml/alias-uri 'atom "http://www.w3.org/2005/Atom")

(defn atom-date [d]
  (.format (date-formatter "yyyy-MM-dd'T'HH:mm:ssX" :zone "UTC") d))

(defn atom-entry [{:as page, :keys [id title published updated site-url]}]
  (let [url (str site-url "/" (apply fs/file (:path page)))]
    [::atom/entry
     [::atom/id (str "urn:uuid:" id)]
     [::atom/title title]
     (when-let [subtitle (:subtitle page)] [::atom/subtitle subtitle])
     (when-let [summary (:description page)] [::atom/summary summary])
     [::atom/link {:type "text/html", :rel "alternate", :title title, :href url}]
     [::atom/published (atom-date (parse-date published))]
     [::atom/updated (atom-date (parse-date (or updated published)))]
     [::atom/author [::atom/name "Alex Vear"]]
     [::atom/content {:type "html", :xml:base url} (:html/content page)]]))

(defn atom-feed [{:keys [site-name site-url]} entries]
  (into [::atom/feed
         {:xmlns "http://www.w3.org/2005/Atom", :xml:base site-url}
         [::atom/id site-url]
         [::atom/title site-name]
         #_[::atom/subtitle "Alex Vear's Blog"]
         [::atom/updated (atom-date (Instant/now))]
         [::atom/link {:rel "alternative", :type "text/html", :href site-url}]
         [::atom/link {:ref "self", :type "application/atom+xml", :href "/atom.xml"}]
         [::atom/icon "/favicon.jpg"]
         [::atom/author [::atom/name "Alex Vear"]]]
        entries))

(defn generate-feed! [conf pages]
  (let [feed (->> pages
                  (filterv :export)
                  (sort-by :published String/CASE_INSENSITIVE_ORDER)
                  reverse
                  (take 20)
                  (mapv atom-entry)
                  (atom-feed conf))]
    (with-open [out (FileWriter. (fs/file (:output-dir conf) "atom.xml"))]
      (xml/emit (xml/sexp-as-element feed) out))))

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
        (let [format-date #(.format (date-formatter "dd MMMM yyyy") (parse-date %))]
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
      (assocf :output-file #(if (:index % true)
                              (apply fs/file (:output-dir %) (conj (:path %) "index.html"))
                              (str (apply fs/file (:output-dir %) (:path %)) ".html")))
      (update :og/type #(or % "website"))
      (assocf :html/title build-title)
      (assocf :html/preamble build-preamble)
      (assocf :html/breadcrumbs build-breadcrumbs)
      (assocf :html/output #(inject (:template %) %))))

;; ----------------------------
;; File processing.

(defmulti enrich-file :file-type)

(defmethod enrich-file :default [f] f)

(defmethod enrich-file "md"
  [{:as conf, :keys [input-file template]}]
  (let [{:keys [html metadata]} (parse-md-file input-file)]
    (build-page (merge conf metadata {:html/content html}))))

(defmethod enrich-file "edn"
  [{:as conf, :keys [input-file template]}]
  (-> conf
      (merge (-> input-file slurp edn/read-string eval))
      (update :html/content #(hiccup/html %))
      build-page))

(defmulti write-file! (comp fs/extension :output-file))

(defmethod write-file! :default
  [{:keys [input-file output-file]}]
  (fs/create-dirs (fs/parent output-file))
  (fs/copy input-file output-file))

(defmethod write-file! "html"
  [{:keys [:html/output output-file :html/breadcrumbs]}]
  (fs/create-dirs (fs/parent output-file))
  (spit output-file output))

(defn path-breakdown [prefix f]
  (into []
        (map str)
        (-> (str f)
            (str/replace-first (str/re-quote-replacement (str prefix)) "")
            fs/components)))

(defn build [conf]
  (let [{:keys [input-dir output-dir exclusions]} conf
        files (into []
                    (comp
                     (filter fs/regular-file?)
                     (keep (fn [f]
                             (let [path (path-breakdown input-dir f)]
                               (when-not (or (re-find #"^\." (first path))
                                             (exclusions (first path)))
                                 (-> conf
                                     (assoc :path        path
                                            :file-type   (fs/extension f)
                                            :input-file  f
                                            :output-file (apply fs/file output-dir path)))))))
                     (map enrich-file))
                    (file-seq input-dir))]
    (fs/delete-tree output-dir)
    (run! write-file! files)
    (generate-feed! conf files)))

(build
 {:site-name   "Alex Vear"
  :site-url    "https://www.alexvear.com"
  :description "Alex Vear's little corner of the World Wide Web."
  :template    (slurp "template.html")
  :input-dir   (fs/file "..")
  :output-dir  (fs/file ".." ".dist")
  :exclusions  #{"README.md" "COPYING" "TODO" "do" "ssg"}})
