(ns uk.axvr.www.date
  (:import [java.util Locale]
           [java.time ZoneId format.DateTimeFormatter]))

(defn formatter
  "Create a fully configured java.time.format.DateTimeFormatter object."
  [pattern & {:keys [locale zone]}]
  (.. DateTimeFormatter
      (ofPattern pattern)
      (withLocale (or locale Locale/UK))
      (withZone (ZoneId/of (or zone "GMT")))))

;; TODO: if given a date object, return it.
(defn parse
  "Parse a date in ISO-8601 format into a java.time.format.Parsed object."
  [date]
  (when date
    (let [date (if (re-find #"T" date) date (str date "T12:00:00Z"))
          fmt (formatter "yyyy-MM-dd'T'HH:mm[:ss[.SSS[SSS]]][z][O][X][x][Z]")]
      (.parse fmt date))))
