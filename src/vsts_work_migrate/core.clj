(ns vsts-work-migrate.core
   (:require [clojure.java.io :as io]
             [clojure.string :as str]
             [clojure.pprint :as pp]
             [vsts-work-migrate.config :as cfg]
             [vsts-work-migrate.vsts-api :as api]
             [vsts-work-migrate.mappings :as mappings]
             [vsts-work-migrate.helpers :as wi-helpers]
             [vsts-work-migrate.template :as template]
             [cheshire.core :as json]))

(defn query-work-items
  [query]
  (api/query-work-items cfg/mseng-instance "Mobile%20Center" query))

(defn print-query-result [query-result]
  (println "Found" (count (:workItems query-result)) "results")
  query-result)

(defn fetch-work-items
  [query-response]
  (println "Fetching work item data for "
           (count (:workItems query-response))
           "work items")
  (let [work-item-data (api/get-work-items cfg/mseng-instance
                                           (map :id (:workItems query-response)))]
    (:value work-item-data)))

(defn map-work-items
  [mapping work-item-data]
  (->> work-item-data
       (map #(mappings/map-via mapping %))
       (group-by :id)

       ;; change to map: id -> work-item
       (map (fn [[k vs]] [k (first vs)]))
       (into {})))


(defn save-work-items [options file res]
  (when-not (:dry-run options)
    (spit file (json/generate-string res {:pretty true}))
    (println "Saved output in" (.getAbsolutePath file)))
  res)


(defn dump
  [query-file mapping-file output options]
  (let [query (slurp query-file)
        mapping (json/parse-string (slurp mapping-file))]
    (println "Query:")
    (println query)
    (println "Mapping:")
    (pp/pprint mapping)
    (->> query
         query-work-items
         print-query-result
         fetch-work-items
         (map-work-items mapping)
         wi-helpers/pretty-print-work-items
         (save-work-items options (io/file output)))))



(defn recreate-work-items
  [instance project work-items options]
  (let [work-items-list (vals work-items)
        grouped-by-type (group-by #(get-in % [:fields (keyword "System.WorkItemType")]) work-items-list)
        epics     (get grouped-by-type "Epic")
        features  (get grouped-by-type "Feature")
        stories   (get grouped-by-type "User Story")
        tasks     (get grouped-by-type "Task")
        bugs      (get grouped-by-type "Bug")
        grouped-by-parents (group-by wi-helpers/parent-id work-items-list)]

    (loop [ordered-list (concat epics features stories tasks bugs)
           all-created-items {}]
      (if-let [next-item (first ordered-list)]
        (if (get all-created-items (:id next-item)) ;; already created
          (recur (rest ordered-list) all-created-items) ;; continue

          ;; create tree rooted at next-item
          (let [created-items (wi-helpers/create-all-work-items
                                          instance
                                          project
                                          next-item
                                          grouped-by-parents
                                          options)]
            (recur (rest ordered-list) (merge all-created-items created-items))))
        all-created-items))))


(defn delete-tree
  [instance created-items options]
  (doseq [id (map :id (vals created-items))]
    (if-not (:dry-run options)
      (do
        (println "Deleting item" id)
        (api/delete-work-item instance id))
      (do
        (println "DRY RUN: Not deleting item" id)))))

(defn template
  [instance input-template work-item-ids output-file options]
  (let [items (api/get-work-items instance work-item-ids)
        view-model (map wi-helpers/view-model (:value items))
        weekly-items (api/get-work-items instance
                                         (get options :weekly-items []))
        weekly-items-view-model (map wi-helpers/view-model (:value weekly-items))]
    (println "View model for main work items: " work-item-ids)
    (clojure.pprint/pprint view-model)

    (println "View model for weekly work items: " (get options :weekly-items []))
    (clojure.pprint/pprint weekly-items-view-model)

    (if-not (:dry-run options)
      (do
        (println "Printing template for work-items" work-item-ids)
        (spit output-file (apply str (template/run-template input-template view-model weekly-items-view-model)))
        (println "Wrote result to " output-file))
      (println "DRY RUN: Not writing file"))))
