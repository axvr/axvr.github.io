(ns uk.axvr.www.site
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.fs :as fs]
            [uk.axvr.refrain :as r]
            [uk.axvr.www.date :as date]
            [markdown.core :as md]
            [hiccup.core :refer [html] :rename {html hiccup->html}])
  (:import [java.io File]))

(defn update* [m k f & args]
  (if (contains? m k)
    (apply update m k f args)
    m))

(defn inject
  "Replace {{x}} tags in text with value of :x in replacements map."
  [text replacements]
  (str/replace text
               #"\{\{ *([\w_-]+) *\}\}"
               (comp str replacements keyword second)))

(defn remove-comments
  "Remove HTML comments (and HTML-entity encoded HTML comments) from a string."
  [s]
  (str/replace s #"<!(&ndash;.*?&ndash;|--.*?--)>" ""))

(defn parse-md [f]
  (-> f
      slurp
      (md/md-to-html-string-with-meta
       :heading-anchors true
       :reference-links? true
       :footnotes? true)
      (update :html remove-comments)))

(defn attach-path [{:keys [rel-path] :as page}]
  (let [bread-path (-> rel-path
                       (str/replace-first #"(?:index)?\.html$" "")
                       (str/replace #"_" " ")
                       (str/split
                         (re-pattern (str/re-quote-replacement File/separator)))
                       next)]
    (assoc page
           :path (when-not (= (first bread-path) "") bread-path)
           :url-path (as-> rel-path it
                       (str it)
                       (str/split it
                                  (re-pattern (str/re-quote-replacement File/separator)))
                       (remove empty? it)
                       (str/join "/" it)
                       (str/replace-first it #"(?:index)?\.html$" "")
                       (str "/" it)))))

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
                 (when misc? (str "misc" separator))
                 (->> path
                      (map
                        (fn [idx itm]
                          (if (zero? idx)
                            itm
                            [:a {:href (apply str (repeat idx "../"))} itm]))
                        (range (dec (count path)) -1 -1))
                      (interpose separator))]])))))

(defn ->essay-date
  "Convert essay published and updated dates into a pretty date to display on the site."
  [{:keys [published updated]}]
  (let [format-date #(.format (date/formatter "MMMM yyyy")
                              (date/parse %))
        close? (fn [d1 d2]
                 (let [date #(.format (date/formatter "MM yyyy") (date/parse %))]
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
                      (when subtitle (str ": " subtitle))
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
  ;; TODO: only if hiccup?
  (if (seq head)
    (let [head (if (keyword? (first head)) [head] head)]
      (assoc page :head
             (str/join "\n" (map #(hiccup->html %) head))))
    page))

(defn htmlise-path [file]
  (-> file fs/file fs/strip-ext (str ".html") fs/file))

(defn construct-page [{:as page, :keys [template]}]
  (-> page
      (update :target htmlise-path)
      (update :rel-path htmlise-path)
      attach-path
      attach-redirect
      attach-extra-head-tags
      attach-keywords
      attach-breadcrumbs
      attach-intro
      attach-page-title
      (as-> $
        (update* $ :content inject $)
        (assoc $ :final-page (inject template $)))))

(defn write-page [{:keys [final-page target]}]
  (fs/create-dirs (fs/parent target))
  (spit target final-page))

(defmulti process-file
  (comp fs/extension :source))

(defmethod process-file :default [{:keys [source target]}]
  (fs/create-dirs (fs/parent target))
  (fs/copy source target))

(defmethod process-file "edn"
  [{:as file, :keys [source template]}]
  (-> source
      slurp
      edn/read-string
      eval
      (update* :content #(hiccup->html %))
      (merge file)
      construct-page
      write-page))

(defmethod process-file "md"
  [{:as file, :keys [source template]}]
  (let [{:keys [html metadata]} (parse-md source)]
    (-> metadata
        (merge file {:content html})
        construct-page
        write-page)))

(defn- dest-path [file src-root dest-root]
  (-> (str file)
      (str/replace-first (str/re-quote-replacement src-root)
                         (str dest-root))
      fs/file))

(defn build! [{:as config, :keys [template target-dir source-dir]}]
  (doseq [f (filterv fs/regular-file? (file-seq source-dir))]
    (process-file (assoc config
                         :source f
                         :target (dest-path f source-dir target-dir)
                         :rel-path (dest-path f source-dir "")))))
