(ns uk.axvr.www.core
  (:require [clojure.java.io :as io]
            [babashka.fs :as fs]
            [uk.axvr.refrain :as r]
            [uk.axvr.www.site :as site]
            #_[uk.axvr.www.feed :as feed]))

(defn config [overrides]
  (-> (r/read-edn-resource "config.edn")
      (merge overrides)
      (update :target-dir fs/file)
      (update :source-dir fs/file)
      (update :template (comp slurp io/resource))))

(defn build [& {:as config-overrides}]
  (let [conf (config config-overrides)]
    (fs/delete-tree (:target-dir conf))
    (site/build! conf)
    ;; TODO
    #_(feed/build! conf)))
