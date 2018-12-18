(ns vsts-work-migrate.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [vsts-work-migrate.config :as cfg]
            [vsts-work-migrate.vsts-api :as api]
            [vsts-work-migrate.mappings :as mappings]
            [vsts-work-migrate.helpers :as wi-helpers]
            [cheshire.core :as json]))

(defn query-work-items
  [query]
  (api/query-work-items (cfg/source-instance)
                        (get-in (cfg/config) [:ado-migrate.config/source
                                              :ado-migrate.config/project])
                        query))

(defn print-query-result [query-result]
  (println "Found" (count (:workItems query-result)) "results")
  query-result)


(defn fetch-work-items
  [query-response]
  (if (zero? (count (:workItems query-response)))
    []
    (let [get-id (fn [{url :url}]
                   {:id (->> url
                             (re-matches #".+/wit/workItems/(\d+)")
                             last
                             Integer/parseInt)})
          fetch-children (fn [{:keys [relations] :as work-item}]
                           (let [children
                                 (filter
                                  #(= "System.LinkTypes.Hierarchy-Forward"
                                      (:rel %)) relations)]
                             (assoc work-item
                                    :children
                                    (fetch-work-items
                                     {:workItems
                                      (map get-id children)}))))

          work-item-data (api/get-work-items (cfg/source-instance)
                                             (map :id (:workItems query-response)))
          work-items (:value work-item-data)]
      (map fetch-children work-items))))

(defn map-work-items
  [mapping work-item-data]
  (letfn [(rec-map-work-item
            [work-item]
            (let [mapped-parent (mappings/map-via mapping work-item)]
              (assoc mapped-parent
                     :children
                     (map rec-map-work-item (:children mapped-parent [])))))]
    (->> work-item-data
         (map rec-map-work-item)
         (group-by :id)

         ;; change to map: id -> work-item
         (map (fn [[k vs]] [k (first vs)]))
         (into {}))))


(defn save-work-items [options file res]
  (when-not (:dry-run options)
    (spit file (json/generate-string res {:pretty true}))
    (println "Saved output in" (.getAbsolutePath file)))
  res)


(defn dump
  [output-path {:keys [work-items] :as options}]
  (let [work-items-results
        (if (clojure.string/ends-with? (.toLowerCase work-items) ".wiql")
          (query-work-items (slurp work-items))
          {:workItems [(api/get-work-items
                        (cfg/source-instance)
                        (Integer/parseInt work-items 10))]})]
    (->> work-items-results
         fetch-work-items
         (save-work-items options output-path))))

(defn recreate-work-items
  [work-items options]
  (let [work-items-list (vals work-items)]

    (loop [ordered-list work-items-list
           all-created-items {}]
      (if-let [next-item (first ordered-list)]
        (if (get all-created-items (:id next-item)) ;; already created
          (recur (rest ordered-list) all-created-items) ;; continue

          ;; create tree rooted at next-item
          (let [created-items (wi-helpers/create-all-work-items
                               (cfg/target-instance)
                               (get-in (cfg/config)
                                       [:ado-migrate.config/target
                                        :ado-migrate.config/project])
                               next-item
                               options)]
            (recur (rest ordered-list) (merge all-created-items created-items))))
        all-created-items))))


(defn copy
  [work-items options]
  (let [work-items-results
        (if (clojure.string/ends-with? (.toLowerCase work-items) ".wiql")
          (query-work-items (slurp work-items))
          {:workItems [(api/get-work-items
                        (cfg/source-instance)
                        (Integer/parseInt work-items 10))]})
        work-items (->> work-items-results
                        fetch-work-items
                        (map-work-items (:ado-migrate.config/mapping (cfg/config))))]
    (recreate-work-items work-items options)))

(defn delete-tree
  [instance created-items options]
  (doseq [id (map :id (vals created-items))]
    (if-not (:dry-run options)
      (do
        (println "Deleting item" id)
        (api/delete-work-item instance id))
      (do
        (println "DRY RUN: Not deleting item" id)))))


(defn delete [saved-results-to-delete options]
  (delete-tree (cfg/target-instance) saved-results-to-delete options))
