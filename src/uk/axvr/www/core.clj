(ns uk.axvr.www.core
  (:require [clojure.edn     :as edn]
            [clojure.string  :as str]
            [clojure.java.io :as io]
            [markdown.core   :as md]
            [hiccup.core     :refer  [html]
                             :rename {html hiccup->html}])
  (:import java.io.File
           java.util.Locale
           [java.time Instant ZoneId format.DateTimeFormatter]))


(defn file-ext
  "Extract the file extension from a java.io.File object."
  [f]
  (when (.isFile f)
    (second
      (re-matches #"^.*\.([\w_-]+)$" (.getName f)))))


(defn edn-file? [f]
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


(def read-edn
  ;; NOTE: can't use read-string and #= macro because it requires source to be
  ;; wrapped in a string.
  (comp eval edn/read-string slurp))


(defn md->html [md]
  (remove-comments
    (md/md-to-html-string
      md
      :heading-anchors  true
      :reference-links? true)))


(defn relative-path [from to]
  (let [path (if (.isFile from)
               (.getParent from)
               from)]
    (io/file path to)))


(defn attach-content [{:keys [f-in content] :as page}]
  (assoc page
         :content
         (if (string? content)
           (let [file (relative-path f-in content)]
             ((if (= "md" (file-ext file))
                md->html
                identity)
              (slurp file)))
           (hiccup->html content))))


(def pages-dir  (-> "pages"  io/resource io/file))
(def dist-dir   (io/file (.getParent pages-dir) "dist"))
(def static-dir (-> "static" io/resource io/file))


(defn output-file [f-in]
  (-> (str f-in)
      (str/replace-first
        (str/re-quote-replacement (str pages-dir))
        (str dist-dir))
      (str/replace-first
        #"\.edn$"
        ".html")
      io/file))


(defn page-path [f-in]
  (let [path (-> (str f-in)
                 (str/replace-first
                   (str/re-quote-replacement (str pages-dir File/separator))
                   "")
                 (str/replace-first #"(?:index)?\.edn$" "")
                 (str/replace #"_" " ")
                 (str/split
                   (re-pattern (str/re-quote-replacement File/separator))))]
    (when-not (= (first path) "")
      path)))


(defn attach-breadcrumbs [{:keys [f-in misc?] :as page}]
  (assoc page
         :breadcrumbs
         (let [path      (page-path f-in)
               separator " &rsaquo; "]
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


(defn date-format [pattern & {:keys [locale zone]}]
  (.. DateTimeFormatter
      (ofPattern pattern)
      (withLocale (or locale Locale/UK))
      (withZone (ZoneId/of (or zone "GMT")))))


(defn parse-date [date]
  (let [fmt (date-format "yyyy-MM-dd['T'HH:mm[:ss[.SSS[SSS]]][z][O][X][x][Z]]")]
    (.parse fmt date)))


(defn ->essay-date [{:keys [published updated]}]
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


(defn attach-intro [{:keys [title subtitle] :as page}]
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
  [{:keys [page-title site title subtitle author] :as page}]
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


(defn attach-redirect
  [{:keys [redirect] :as page}]
  (if redirect
    (assoc page
           :redirect
           (str "<meta http-equiv=\"refresh\" content=\"0; url=" redirect "\" />"))
    page))


(defn attach-noindex
  [{:keys [noindex?] :as page}]
  (if noindex?
    (assoc page
           :noindex
           (str "<meta name=\"robots\" content=\"noindex\">"))
    page))


(defn build-pages []
  (let [config   (-> "config.edn" io/resource read-edn)
        template (-> "template.html" io/resource slurp)
        pages    (->> pages-dir
                      file-seq
                      (filter edn-file?)
                      (map #(merge
                              config
                              {:f-in  %
                               :f-out (output-file %)}
                              (read-edn %)))
                      (map attach-noindex)
                      (map attach-redirect)
                      (map attach-keywords)
                      (map attach-content)
                      (map attach-breadcrumbs)
                      (map attach-intro)
                      (map attach-page-title)
                      (map #(assoc % :content (inject (:content %) %)))
                      (map #(assoc % :result (inject template %))))]
    (doseq [page pages]
      (.mkdirs (io/file (.getParent (:f-out page))))
      (spit (:f-out page) (:result page)))))


(defn wipe-dir [dir]
  (doseq [file (->> dir file-seq reverse butlast)]
    (.delete file)))


(defn copy-dir [from to]
  (doseq [f (->> from file-seq (filter #(. % isFile)))]
    (let [out-f (-> (str f)
                    (str/replace-first
                      (str/re-quote-replacement from)
                      (str to))
                    io/file)
          dirs (io/file (.getParent out-f))]
      (.mkdirs dirs)
      (io/copy f out-f))))


(defn build [& args]
  (.mkdirs dist-dir)
  (wipe-dir dist-dir)
  (copy-dir static-dir dist-dir)
  (build-pages))
