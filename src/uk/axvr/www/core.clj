(ns uk.axvr.www.core
  (:require [clojure.edn      :as edn]
            [clojure.string   :as str]
            [clojure.java.io  :as io]
            [clojure.data.xml :as xml]
            [markdown.core    :as md]
            [hiccup.core      :refer [html] :rename {html hiccup->html}])
  (:import java.util.Locale
           [java.io File FileWriter]
           [java.time Instant ZoneId format.DateTimeFormatter]))

(def read-edn
  (comp eval edn/read-string slurp))

(defn wipe-dir
  "Delete the contents of a directory, but not the directory itself."
  [dir]
  (doseq [file (some->> dir file-seq reverse butlast)]
    (.delete file)))

(defn copy-dir
  "Copy the contents of a directory to another."
  [from to]
  (doseq [f (some->> from file-seq (filter #(. % isFile)))]
    (let [out-f (-> (str f)
                    (str/replace-first
                      (str/re-quote-replacement from)
                      (str to))
                    io/file)
          dirs (io/file (.getParent out-f))]
      (.mkdirs dirs)
      (io/copy f out-f))))

(defn file-ext
  "Extract the file extension from a java.io.File object."
  [f]
  (when (.isFile f)
    (second
      (re-matches #"^.*\.([\w_-]+)$" (.getName f)))))

(defn edn-file?
  "Returns true if file (f) is an EDN file."
  [f]
  (= (file-ext f) "edn"))

(defn inject
  "Replace {{x}} tags in text with value of :x in replacements map."
  [text replacements]
  (str/replace
    text
    #"\{\{ *([\w_-]+) *\}\}"
    (comp str replacements keyword second)))

(defn remove-comments
  "Remove HTML comments (and HTML-entity encoded HTML comments) from a string."
  [s]
  (str/replace s #"<!(&ndash;.*?&ndash;|--.*?--)>" ""))

(defn md->html
  "Compile Markdown to HTML."
  [md]
  (remove-comments
    (md/md-to-html-string
      md
      :heading-anchors  true
      :reference-links? true)))

(defn relative-path
  "Construct a relative file path from one file/dir to another."
  [from to]
  (let [path (if (.isFile from)
               (.getParent from)
               from)]
    (io/file path to)))

(def pages-dir (-> "pages"  io/resource io/file))
(def dist-dir  (io/file (.getParent pages-dir) "dist"))

(defn attach-content
  "Attach content to a page."
  [{:keys [f-in content] :as page}]
  (assoc page
         :content
         (if (string? content)
           (let [file (relative-path f-in content)]
             ((if (= "md" (file-ext file))
                md->html
                identity)
              (slurp file)))
           (hiccup->html content))))

(defn output-file
  "Create java.io.File object representing the output file of the page."
  [f-in]
  (-> (str f-in)
      (str/replace-first
        (str/re-quote-replacement (str pages-dir))
        (str dist-dir))
      (str/replace-first
        #"\.edn$"
        ".html")
      io/file))

;; TODO: refactor/clean this.
(defn attach-path [{:keys [f-in] :as page}]
  (let [path (-> (str f-in)
                 (str/replace-first
                   (str/re-quote-replacement (str pages-dir File/separator))
                   ""))
        bread-path (-> path
                       (str/replace-first #"(?:index)?\.edn$" "")
                       (str/replace #"_" " ")
                       (str/split
                         (re-pattern (str/re-quote-replacement File/separator))))
        url-path (as-> path it
                   (str/split it
                     (re-pattern (str/re-quote-replacement File/separator)))
                   (remove empty? it)
                   (str/join "/" it)
                   (str/replace-first it #"(?:index)?\.edn$" "")
                   (str "/" it))]
    (assoc page
           :path (when-not (= (first bread-path) "") bread-path)
           :url-path url-path)))

(defn attach-breadcrumbs [{:keys [path misc?] :as page}]
  (assoc page
         :breadcrumbs
         (let [separator " &rsaquo; "]
           (when path
             (hiccup->html
               [:nav {:class "bread"}
                [:span
                 [:a {:href "/"} "home"]
                 separator
                 (when misc?
                   (str "misc" separator))
                 (->> path
                      (map
                        (fn [idx itm]
                          (if (zero? idx)
                            itm
                            [:a {:href (apply str (repeat idx "../"))} itm]))
                        (range (dec (count path)) -1 -1))
                      (interpose separator))]])))))

(defn date-format
  "Create a fully configured java.time.format.DateTimeFormatter object."
  [pattern & {:keys [locale zone]}]
  (.. DateTimeFormatter
      (ofPattern pattern)
      (withLocale (or locale Locale/UK))
      (withZone (ZoneId/of (or zone "GMT")))))

(defn parse-date
  "Parse a date in ISO-8601 format into a java.time.format.Parsed object."
  [date]
  (when date
    (let [date (if (re-find #"T" date) date (str date "T12:00:00Z"))
          fmt (date-format "yyyy-MM-dd'T'HH:mm[:ss[.SSS[SSS]]][z][O][X][x][Z]")]
      (.parse fmt date))))

(defn ->essay-date
  "Convert essay published and updated dates into a pretty date to display on the site."
  [{:keys [published updated]}]
  (letfn [(format-date [d]
            (.format (date-format "MMMM yyyy")
                     (parse-date d)))
          (close? [d1 d2]
            (let [date #(.format (date-format "MM yyyy") (parse-date %))]
              (= (date d1) (date d2))))]
    (when published
      [:time
       {:class "date"
        :title (if updated
                 (str published " (rev. " updated ")")
                 published)
        :datetime published}
       (if (and updated (not (close? published updated)))
         (str (format-date published)
              "&ensp;(rev. "
              (format-date updated)
              ")")
         (format-date published))])))

(defn attach-intro
  "Build and attach the intro/header section of the page."
  [{:keys [title subtitle] :as page}]
  (assoc page
         :intro
         (when title
           (hiccup->html
             [:div {:class "intro"}
              [:h1 title]
              (when subtitle
                [:h2 subtitle])
              (->essay-date page)]))))

(defn attach-page-title
  "Build full page title."
  [{:keys [page-title site title subtitle] :as page}]
  (assoc page
         :page-title
         (cond
           page-title page-title
           title (str title
                      (when subtitle
                        (str ": " subtitle))
                      " | "
                      site)
           :else site)))

(defn attach-keywords [page]
  (update page :keywords #(str/join ", " %)))

;; TODO: conj onto :head.
(defn attach-redirect
  [{:keys [redirect] :as page}]
  (if redirect
    (assoc page
           :redirect
           (hiccup->html
             [:meta {:http-eqive "refresh"
                     :content (str "0; url=" redirect)}]))
    page))

(defn attach-extra-head-tags [{:keys [head] :as page}]
  (if (seq head)
    (let [head (if (keyword? (first head)) [head] head)]
      (assoc page :head
             (str/join "\n" (map #(hiccup->html %) head))))
    page))

(defn copy-required-files
  "Copies files required by a page to the dist."
  [page]
  (let [{:keys [requires]} page]
    (when (seq requires)
      (doseq [f requires]
        (let [rel-in  (relative-path (:f-in page) f)
              rel-out (relative-path (:f-out page) f)]
          (if (.isDirectory rel-in)
            (copy-dir rel-in rel-out)
            (io/copy rel-in rel-out)))))))

(defn build-pages []
  (let [config   (-> "config.edn" io/resource read-edn)
        template (-> "template.html" io/resource slurp)]
    (->> pages-dir
         file-seq
         (filter edn-file?)
         (map #(merge
                 config
                 {:f-in  %
                  :f-out (output-file %)}
                 (read-edn %)))
         (map attach-path)
         (map attach-redirect)
         (map attach-extra-head-tags)
         (map attach-keywords)
         (map attach-content)
         (map attach-breadcrumbs)
         (map attach-intro)
         (map attach-page-title)
         (map #(assoc % :content (inject (:content %) %)))
         (map #(assoc % :final-page (inject template %))))))

(defn generate-pages [pages]
  (wipe-dir dist-dir)
  (doseq [page pages]
    (.mkdirs (io/file (.getParent (:f-out page))))
    (spit (:f-out page) (:final-page page))
    (copy-required-files page)))

(defn ->atom-date [d]
  (let [fmt (date-format "yyyy-MM-dd'T'HH:mm:ssX" :zone "UTC")]
    (.format fmt d)))

(xml/alias-uri 'atom "http://www.w3.org/2005/Atom")

(defn atom-entry [page]
  (let [url (str "https://www.alexvear.com" (:url-path page))]
    [::atom/entry
     [::atom/title (:title page)]
     (when-let [subtitle (:subtitle page)]
       [::atom/subtitle subtitle])
     (when-let [summary (:summary page)]
       [::atom/summary summary])
     (when-let [id (:id page)]
       [::atom/id (str "urn:uuid:" id)])
     [::atom/link
      {:type "text/html"
       :rel "alternate"
       :title (:title page)
       :href url}]
     [::atom/published (->atom-date (parse-date (:published page)))]
     [::atom/updated (->atom-date (parse-date (or (:updated page) (:published page))))]
     [::atom/author
      [::atom/name (:author page)]]
     [::atom/content
      {:type "html"
       :xml:base url}
      (:content page)]]))

(defn atom-feed [output entries]
  (with-open [out (FileWriter. output)]
    (xml/emit
      (xml/sexp-as-element
        (into
          [::atom/feed
           {:xmlns "http://www.w3.org/2005/Atom"
            :xml:base "https://www.alexvear.com"}
           [::atom/id "https://www.alexvear.com"]
           [::atom/title "Alex Vear"]
           [::atom/subtitle "Alex Vear's Essays"]
           [::atom/updated (->atom-date (Instant/now))]
           [::atom/link
            {:rel "alternative"
             :type "text/html"
             :href "https://www.alexvear.com"}]
           [::atom/link
            {:ref "self"
             :type "application/atom+xml"
             :href "/essays/atom.xml"}]
           [::atom/icon "/favicon.jpg"]
           [::atom/author
            [::atom/name "Alex Vear"]]]
          entries))
      out)))

(defn generate-feed [pages]
  (->> pages
       (filter #(contains? % :published))
       (sort-by :published String/CASE_INSENSITIVE_ORDER)
       (reverse)
       (map atom-entry)
       (atom-feed (io/file dist-dir "essays" "atom.xml"))))

(defn build [& _]
  (let [pages (build-pages)]
    (generate-pages pages)
    (generate-feed pages)))
