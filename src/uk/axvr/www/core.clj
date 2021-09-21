(ns uk.axvr.www.core
  (:require [clojure.edn     :as edn]
            [clojure.string  :as str]
            [clojure.java.io :as io]
            [markdown.core   :as md]
            [hiccup.core     :refer  [html]
                             :rename {html hiccup->html}])
  (:import [java.io File FileInputStream FileOutputStream]))


;; TODO: conditional generation.
;; TODO: meta tags (description, keywords, author, etc.).
;; TODO: redirects.


;;; -----------------------------------------------------------
;;; Helpers


(defn file-ext
  "Extract the file extension from a java.io.File object."
  [f]
  (when (.isFile f)
    (second
      (re-matches #"^.*\.([\w_-]+)$" (.getName f)))))


(defn edn-file? [f]
  (= (file-ext f) "edn"))


(defn inject
  "Replace {{x}} tags in text with value of :x in replacements."
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
  (comp eval edn/read-string slurp))


(defn md->html [md]
  (remove-comments
    (md/md-to-html-string
      md
      :footnotes?       true
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
(def dist-dir   (-> "dist"   io/resource io/file))
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


(defn attach-intro [{:keys [title subtitle intro?]
                     :or   {intro? true}
                     :as   page}]
  (assoc page
         :intro
         (when (and intro? title)
           (hiccup->html
             [:div {:class "intro"}
              [:h1 title]
              (when subtitle
                [:h2 subtitle])]))))


(defn attach-page-title
  "Build full page title."
  [{:keys [page-title site title subtitle author article?] :as page}]
  (assoc page
         :page-title
         (cond
           page-title page-title
           title (str title
                      (when subtitle
                        (str ": " subtitle))
                      (when (and article? author (not= author site))
                        (str " \u2014 " author))
                      " | "
                      site)
           :else site)))


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
  (doseq [file (->> dir
                    file-seq
                    reverse
                    butlast
                    (remove #(. % isHidden)))]
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
  (wipe-dir dist-dir)
  (copy-dir static-dir dist-dir)
  (build-pages))
