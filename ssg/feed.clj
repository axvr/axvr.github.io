(ns uk.axvr.www.feed
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [uk.axvr.www.date :as date]
            [uk.axvr.refrain :as r])
  (:import [java.io File FileWriter]
           [java.time Instant]))

(xml/alias-uri 'atom "http://www.w3.org/2005/Atom")

(defn ->atom-date [d]
  (.format (date/formatter "yyyy-MM-dd'T'HH:mm:ssX" :zone "UTC") d))

(defn ->atom-entry [{:as page, :keys [base-url]}]
  (let [url (str base-url (:url-path page))]
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
     [::atom/published (->atom-date (date/parse (:published page)))]
     [::atom/updated (->atom-date (date/parse (or (:updated page) (:published page))))]
     [::atom/author [::atom/name (:author page)]]
     [::atom/content
      {:type "html", :xml:base url}
      (:content page)]]))

(defn atom-feed [output entries]
  (with-open [out (FileWriter. output)]
    (-> [::atom/feed
         {:xmlns "http://www.w3.org/2005/Atom"
          :xml:base "https://www.alexvear.com"}
         [::atom/id "https://www.alexvear.com"]
         [::atom/title "Alex Vear"]
         [::atom/subtitle "Alex Vear's Blog"]
         [::atom/updated (->atom-date (Instant/now))]
         [::atom/link
          {:rel "alternative"
           :type "text/html"
           :href "https://www.alexvear.com"}]
         [::atom/link
          {:ref "self"
           :type "application/atom+xml"
           :href "/blog/atom.xml"}]
         [::atom/icon "/favicon.jpg"]
         [::atom/author
          [::atom/name "Alex Vear"]]]
        (into entries)
        xml/sexp-as-element
        (xml/emit out))))

(defn generate-feed [pages]
  (->> pages
       (filter #(contains? % :published))
       (sort-by :published String/CASE_INSENSITIVE_ORDER)
       (reverse)
       (mapv atom-entry)
       (atom-feed (io/file dist-dir "blog" "atom.xml"))))

(defn build! [{:as config, :keys [source-dir feed-source target-dir feed-target]}]
  (let [source (fs/file source-dir feed-source)
        target (fs/file target-dir feed-target)
        config (merge config (r/->map source target))]
    (into []
          (comp
           (filter fs/regular-file?)
           (map read-file))
          (file-seq source))))
