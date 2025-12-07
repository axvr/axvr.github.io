(ns ssg
  "A simple Static Site Generator (SSG) for my personal website."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.data.xml :as xml]
            [clj-yaml.core :as yaml]
            [babashka.fs :as fs]
            [hiccup.core :as hiccup])
  (:import (java.io File FileWriter)
           (java.util Locale)
           (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (org.commonmark.parser Parser)
           (org.commonmark.renderer.html HtmlWriter HtmlRenderer HtmlNodeRendererContext HtmlNodeRendererFactory)
           (org.commonmark.ext.gfm.tables TablesExtension)
           (org.commonmark.ext.gfm.tables.internal TableHtmlNodeRenderer)
           (org.commonmark.ext.gfm.strikethrough StrikethroughExtension)
           (org.commonmark.ext.footnotes FootnotesExtension)
           (org.commonmark.ext.heading.anchor HeadingAnchorExtension)
           (org.commonmark.ext.front.matter YamlFrontMatterExtension YamlFrontMatterVisitor)
           (org.commonmark.ext.task.list.items TaskListItemsExtension)))

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

(defn date-formatter [pattern]
  (.. DateTimeFormatter
      (ofPattern pattern)
      (withLocale Locale/UK)
      (withZone (ZoneId/of "UTC"))))

;; ----------------------------
;; Markdown parsing.

(defn yaml-fix-date [maybe-date]
  (if (instance? java.util.Date maybe-date)
    (.toInstant maybe-date)
    maybe-date))

;; TODO: refactor this.
(defn parse-md-file [file]
  (let [e [(TablesExtension/create)
           (StrikethroughExtension/create)
           (FootnotesExtension/create)
           (HeadingAnchorExtension/create)
           (YamlFrontMatterExtension/create)
           (TaskListItemsExtension/create)]
        p (.. (Parser/builder)
              (extensions e)
              (build))
        d (.parse p (slurp file))
        v (YamlFrontMatterVisitor.)
        _ (.accept d v)
        r (.. (HtmlRenderer/builder)
              ;; Wrap tables in scrollable container.
              (nodeRendererFactory
               (reify HtmlNodeRendererFactory
                 (create [this context]
                   (proxy [TableHtmlNodeRenderer] [context]
                     (renderBlock [tableBlock]
                       (let [^HtmlWriter html (HtmlNodeRendererContext/.getWriter context)]
                         (.line html)
                         (.tag html "div" {"class" "table-container"})
                         (proxy-super renderBlock tableBlock)
                         (.tag html "/div")
                         (.line html)))))))
              (extensions e)
              (build))]
    (into {:html.content (.render r d)}
          (map (fn [[k v]]
                 [(keyword k)
                  (-> v (#(apply str %)) (yaml/parse-string :keywords false) yaml-fix-date)]))
          (.getData v))))

;; ----------------------------
;; Atom feed construction.

(xml/alias-uri 'atom "http://www.w3.org/2005/Atom")

(defn atom-date [d]
  (.format (date-formatter "yyyy-MM-dd'T'HH:mm:ssX") d))

(defn atom-entry [{:as page, :keys [id title published updated site-url]}]
  (let [url (str site-url "/" (apply fs/file (:path page)))]
    [::atom/entry
     [::atom/id (str "urn:uuid:" id)]
     [::atom/title title]
     (when-let [subtitle (:subtitle page)] [::atom/subtitle subtitle])
     (when-let [summary (:description page)] [::atom/summary summary])
     [::atom/link {:type "text/html", :rel "alternate", :title title, :href url}]
     [::atom/published (atom-date published)]
     [::atom/updated (atom-date (or updated published))]
     [::atom/author [::atom/name "Alex Vear"]]
     [::atom/content {:type "html", :xml:base url} (:html.content page)]]))

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
                  (sort-by :published compare)
                  reverse
                  (take 20)
                  (mapv atom-entry)
                  (atom-feed conf))]
    (with-open [out (FileWriter. (fs/file (:output-dir conf) "atom.xml"))]
      (xml/emit (xml/sexp-as-element feed) out))))

;; ----------------------------
;; Page construction.

(defn build-title [{html-title :html.title, :keys [title site-name]}]
  (or html-title
      (and title (format "%s | %s" title site-name))
      site-name))

(defn build-cover [{:keys [title subtitle published updated]}]
  (when title
    (hiccup/html
     [:div {:class "cover"}
      [:h1 title]
      (when subtitle [:h2 subtitle])
      (when published
        (let [format-date #(.format (date-formatter "yyyy-MM-dd") %)]
          [:time {:class "date", :datetime (format-date published)}
           (str (format-date published)
                (when updated
                  (format "&ensp;(updated: %s)" (format-date updated))))]))])))

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
  (-> page
      (update :path #(let [l (fs/strip-ext (peek %)), p (pop %)]
                       (if (= l "index") p (conj p l))))
      (assocf :output-file #(if (:index % true)
                              (apply fs/file (:output-dir %) (conj (:path %) "index.html"))
                              (str (apply fs/file (:output-dir %) (:path %)) ".html")))
      (update :og.type #(or % "website"))
      (assocf :og.title #(or (:og.title %) (:html.title %) (:title %)))
      ;; TODO: spec validation.
      (assocf :html.title build-title)
      (assocf :html.cover build-cover)
      (assocf :html.breadcrumbs build-breadcrumbs)
      (assocf :html.output #(inject (:template %) %))))

;; ----------------------------
;; File processing.

(defmulti enrich-file :file-type)

(defmethod enrich-file :default [f] f)

(defmethod enrich-file "md"
  [{:as conf, :keys [input-file template]}]
  (build-page (merge conf (parse-md-file input-file))))

(defmethod enrich-file "edn"
  [{:as conf, :keys [input-file template]}]
  (-> conf
      (merge (-> input-file slurp edn/read-string eval))
      (update :html.content #(hiccup/html %))
      build-page))

(defmulti write-file! (comp fs/extension :output-file))

(defmethod write-file! :default
  [{:keys [input-file output-file]}]
  (fs/create-dirs (fs/parent output-file))
  (fs/copy input-file output-file))

(defmethod write-file! "html"
  [{output :html.output, :keys [output-file]}]
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
                                 (assoc conf
                                        :path        path
                                        :file-type   (fs/extension f)
                                        :input-file  f
                                        :output-file (apply fs/file output-dir path))))))
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
  :exclusions  #{"README.md" "COPYING" "TODO" "DOING" "DONE" "do" "ssg"}})
