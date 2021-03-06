(ns vsts-work-migrate.mappings
  (:require [clojure.string :as str]))


;; Applies a mapping to a work-item
;; the mapping is specified in e.g. mappings.json
;; and specifies fields, e.g., System.WorkItemType
;; to a map e.g.,
;; {"System.WorkItemType": {"Scenario":"Epic",
;;         "Feature":"Feature",
;;         "User Story":"User Story",
;;         "Task":"Task",
;;         "Bug":"Bug"}}

(defn- map-work-item
  [mapping work-item]
  (let [red (fn [work-item key]
              (update-in work-item
                         [:fields (keyword nil (name key))]
                         (fn [old-val]
                           (if (string? old-val)
                             (loop [rules (get mapping key)]
                               (if-let [rules (seq rules)]
                                 (let [[re-str sub] (first rules)
                                       re (re-pattern re-str)]
                                   (if (re-seq re old-val)
                                     (clojure.string/replace old-val re sub)
                                     (recur (next rules))))
                                 old-val))
                             old-val))))]
      (reduce red work-item (keys mapping))))


(defn- map-relation [item relation]
  (let [u (get relation :url) ; relation url
        id (last (str/split u #"/"))] ; relation id

    (-> relation
        (assoc :target-id (try (Integer/parseInt id 10)
                               (catch NumberFormatException e id)))
        (assoc :source-id (:id item)))))


(defn map-via
  [mapping work-item]
  (let [mapped-data (map-work-item mapping work-item)

        ;; Note this handles the fact that bugs in MSEng
        ;; are not using System.Description but Microsoft.VSTS.TCM.ReproSteps
        mapped-data (if-let [repro-steps (get-in mapped-data
                                                   [:fields
                                                    :Microsoft.VSTS.TCM.ReproSteps])]
                        (update-in mapped-data
                                   [:fields :System.Description]
                                   (fn [desc]
                                     (str repro-steps
                                          "\n"
                                          desc)))
                        mapped-data)

        mapped-data (update-in mapped-data
                               [:fields :System.Description]
                               (fn [desc]
                                 (str desc
                                      "\n\n"
                                      "Copied from: " (:url work-item))))]

    (update-in mapped-data [:relations]
               (fn [relations]
                 (map #(map-relation mapped-data %)
                      relations)))))
