(ns vsts-work-migrate.core
   (:require [clojure.java.io :as io]
             [clojure.string :as str]
             [vsts-work-migrate.config :as cfg]
             [vsts-work-migrate.vsts-api :as api]
             [vsts-work-migrate.markdown :as markdown]
             [cheshire.core :as json]))



(defn- map-relation [item relation]
  (let [u (get relation :url)
        id (last (str/split u #"/"))]
    (assoc (assoc relation :target-id (try (Integer/parseInt id 10) (catch NumberFormatException e id)))
           :source-id (:id item))))

(defn- map-via
  [mapping full-data]
  (let [mapped-keys (keys mapping)
        mapped-data (loop [result full-data
                           keys-to-map mapped-keys]
                      (if (empty? keys-to-map)
                        result
                        (let [key (first keys-to-map)]
                          (recur (update-in result [:fields (keyword key)]
                                            (fn [x]
                                              (or (get-in mapping [key x])
                                                  (do
                                                    (println "*WARNING*" "Unable to map" x "to a value in" (keys (get mapping key)))
                                                    x))))
                                 (rest keys-to-map)))))

        repro-as-desc (if-let [repro-steps (get-in mapped-data
                                                   [:fields
                                                    :Microsoft.VSTS.TCM.ReproSteps])]
                        (update-in mapped-data
                                   [:fields :System.Description]
                                   (fn [desc]
                                     (str/join
                                      "\n"
                                      [repro-steps
                                       (or desc "")])))
                        mapped-data)

        ]
    (-> repro-as-desc
        (assoc :relations (map #(map-relation repro-as-desc %)
                               (:relations repro-as-desc))))))


(defn fetch-work-items
  [limit query-response]
  (println "Fetching work item data for " (min limit (count (:workItems query-response))) "work items")
  (let [work-item-data (api/get-work-items cfg/mseng-instance (map :id (take limit (:workItems query-response))))]
    (:value work-item-data)))

(defn map-work-items
  [mapping work-item-data]
  (let [grouped (group-by :id (map #(map-via mapping %) work-item-data))]
    (into {} (map (fn [[k vs]]
                     [k (first vs)]) grouped))))

(defn validate-work-items
  [mapped-work-items]
  ;;TODO what to validate
  (comment (let [required-ids (into #{} (mapcat (fn [x] (map :target-id x)) (map :relations mapped-work-items)))]
             (println required-ids)
             (doseq [id required-ids]
               (if-not (get (group-by :id mapped-work-items) id)
                 ))))
  mapped-work-items)

(defn get-item-field
  [item field]
  (get-in item [:fields (keyword (str/join "." ["System" (name field)]))]))

(defn parent-id
  [work-item]
  (let [rel (:relations work-item)
        parents (filter #(= "System.LinkTypes.Hierarchy-Reverse" (:rel %)) rel)]
    (case (count parents)
      0 nil
      1 (:target-id (first parents))
      :else (throw
             (RuntimeException. (str "Work item " work-item "has several parents"))))))


(defn work-item-parent? [work-item parent-grouped-work-items]
  (not-empty (get parent-grouped-work-items (:id work-item))))

(defn work-item-children [work-item parent-grouped-work-items]
  (get parent-grouped-work-items (:id work-item)))

(defn work-item-tree
  [root grouped-work-items]
  (tree-seq #(work-item-parent? % grouped-work-items)
            #(work-item-children % grouped-work-items)
            root))

(defn indentation-for-work-item [work-item]
  (let [type (get-in work-item [:fields (keyword "System.WorkItemType")])]
    (case type
      "Epic"       ""
      "Feature"    "  "
      "User Story" "    "
      "Task"       "        "
      "Bug"        "        "
      :else "*******")))

(defn pretty-print-work-items
  [work-items]
  (let [work-items-list (vals work-items)
        grouped-by-type (group-by #(get-in % [:fields (keyword "System.WorkItemType")]) work-items-list)
        epics (get grouped-by-type "Epic")
        features (get grouped-by-type "Feature")
        stories (get grouped-by-type "User Story")
        tasks (get grouped-by-type "Task")
        bugs (get grouped-by-type "Bug")
        grouped-by-parents (group-by parent-id work-items-list)
        items-to-print (java.util.HashMap. work-items)]

    (loop [ordered-list (concat epics features stories tasks bugs)]
      (if-let [next-item (first ordered-list)]
        (do
          (when (get items-to-print (:id next-item))
            (println (get-item-field next-item :WorkItemType) (str "(" (:id next-item) "):") (get-item-field next-item :Title) )
            (.remove items-to-print (:id next-item))
            (doseq [work-item (rest (work-item-tree next-item grouped-by-parents))]
              (print (indentation-for-work-item work-item))
              (println (get-item-field work-item :WorkItemType) (str "(" (:id work-item) ")" ":") (get-item-field work-item :Title) )
              (.remove items-to-print (:id work-item))))
          (recur (rest ordered-list)))))
    work-items))


(defn save-work-items [options file res]
  (when-not (:dry-run options)
    (spit file (json/generate-string res {:pretty true}))
    (println "Saved output in" (.getAbsolutePath file)))
  res)

(defn print-query-result [query-result]
  (println "Found" (count (:workItems query-result)) "results")
  query-result)

(defn dump
  [query-file mapping-file output options]
  (let [limit (or (:limit options) 3000)
        query (slurp query-file)
        mapping (json/parse-string (slurp mapping-file))]
    (println query)
    (println "Limit: " limit)
    (->> (api/query-work-items cfg/mseng-instance "Mobile%20Center" query)
         print-query-result
         (fetch-work-items limit)
         (map-work-items mapping)
         validate-work-items
         pretty-print-work-items
         (save-work-items options (io/file output)))))


;; Design strategy to create an item, children and setup links
;; * Sort via top-level type, first epics, then features, User stories, bugs/tasks
;; * Process each in a depth first manner, setting parent links
;;      Feature f1
;;        User story U1
;;           Task 1
;;           Task 2
;; * Save the ids/title/type of everything created and save to a log

;; Decide on which fields to carry through
;; * Title
;; * TeamProject: selected at invocation time
;; * AreaPath: via mapping
;; * WorkItemType: via mapping
;; * State: via mapping
;; * Created By ?
;; * Description (try convert to markdown?)
;; * AssignedTo
;; * Microsoft.VSTS.Common.Priority

;; CLI interface
;; Interface to dry run and to selective create work items
;; Log items created
;; Delete newly created items
;; Disable notifications!
;; Communicate out status and plan for work item migration and build/release def


(defn operations-to-create-work-item
  [work-item parent-already-created-work-item]
  (let [ops [{:op "add"
              :path "/fields/System.Title"
              :value (get-item-field work-item :Title)}
             {:op "add"
              :path "/fields/System.AreaPath"
              :value (get-item-field work-item :AreaPath)}
             {:op "add"
              :path "/fields/System.State"
              :value (get-item-field work-item :State)}
             {:op "add"
              :path "/fields/System.Description"
              :value (get-item-field work-item :Description)}
             {:op "add"
              :path "/fields/System.AssignedTo"
              :value (get-item-field work-item :AssignedTo)}
             {:op "add"
              :path "/fields/Microsoft.VSTS.Common.Priority"
              :value (get-in work-item [:fields "Microsoft.VSTS.Common.Priority"])}
             {:op "add"
              :path "/fields/System.History"
              :value (get-item-field work-item :History)}
             {:op "add"
              :path "/fields/System.CreatedBy"
              :value (get-item-field work-item :CreatedBy)}
             {:op "add"
              :path "/fields/System.Tags"
              :value (get-item-field work-item :Tags)}]

        ops-with-parent (if parent-already-created-work-item
                          (conj ops
                                {:op "add"
                                 :path "/relations/-"
                                 :value {:rel "System.LinkTypes.Hierarchy-Reverse"
                                         :url (:url parent-already-created-work-item)
                                         :attributes {}}})
                          ops)]
    (filter :value ops-with-parent)))



(defn re-create-tree
  [instance project tree options]
  (loop [old->new {}
         tree tree]
    (let [old-item (first tree)
          old-item-parent-id (parent-id old-item)
          items-ops (operations-to-create-work-item
                     old-item
                     (get old->new old-item-parent-id))
          created-item (if-not (:dry-run options)
                         (api/create-work-item instance project
                                               (get-item-field old-item :WorkItemType)
                                               items-ops)
                         old-item)]
      (if (:dry-run options)
        (print "WOULD CREATE: ")
        (print "CREATED: "))
      (print (indentation-for-work-item created-item))
      (println (get-item-field old-item :WorkItemType) (str "(" (:id old-item) "):") (get-item-field old-item :Title))
      (let [updated-old->new (assoc old->new (get old-item :id) created-item)]
        (if-let [next-items (next tree)]
        (recur updated-old->new  next-items)
        updated-old->new)))))


(defn delete-tree
  [instance created-items options]
  (doseq [id (map :id (vals created-items))]
    (if-not (:dry-run options)
      (do
        (println "Deleting item" id)
        (api/delete-work-item instance id))
      (do
        (println "DRY RUN: Not deleting item" id)))))



(defn recreate-work-items
  [instance project work-items options]
  (let [work-items-list (vals work-items)
        grouped-by-type (group-by #(get-in % [:fields (keyword "System.WorkItemType")]) work-items-list)
        epics (get grouped-by-type "Epic")
        features (get grouped-by-type "Feature")
        stories (get grouped-by-type "User Story")
        tasks (get grouped-by-type "Task")
        bugs (get grouped-by-type "Bug")
        grouped-by-parents (group-by parent-id work-items-list)]

    (loop [ordered-list (concat epics features stories tasks bugs)
           all-created-items {}]
      (if-let [next-item (first ordered-list)]
        (if (get all-created-items (:id next-item))
          (recur (rest ordered-list) all-created-items)
          (let [next-work-item-tree (work-item-tree next-item
                                                    grouped-by-parents)
                created-items (re-create-tree instance project
                                              next-work-item-tree options)]
            (recur (rest ordered-list) (merge all-created-items created-items))))
        all-created-items))))
