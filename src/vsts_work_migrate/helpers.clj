(ns vsts-work-migrate.helpers
  (:require [clojure.string :as str]
            [vsts-work-migrate.vsts-api :as api]))


;; misc trash-can of utilities

(defn get-system-field
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

;; todo refactor: loop/recur, items-to-print, leverage work-item-tree tree-seq
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
            (println (get-system-field next-item :WorkItemType) (str "(" (:id next-item) "):") (get-system-field next-item :Title) )
            (.remove items-to-print (:id next-item))
            (doseq [work-item (rest (work-item-tree next-item grouped-by-parents))]
              (print (indentation-for-work-item work-item))
              (println (get-system-field work-item :WorkItemType) (str "(" (:id work-item) ")" ":") (get-system-field work-item :Title) )
              (.remove items-to-print (:id work-item))))
          (recur (rest ordered-list)))))
    work-items))

(defn operations-to-create-work-item
  [work-item parent-already-created-work-item]
  (let [ops [{:op "add"
              :path "/fields/System.Title"
              :value (get-system-field work-item :Title)}
             {:op "add"
              :path "/fields/System.AreaPath"
              :value (get-system-field work-item :AreaPath)}
             {:op "add"
              :path "/fields/System.State"
              :value (get-system-field work-item :State)}
             {:op "add"
              :path "/fields/System.Description"
              :value (get-system-field work-item :Description)}
             {:op "add"
              :path "/fields/System.AssignedTo"
              :value (get-system-field work-item :AssignedTo)}
             {:op "add"
              :path "/fields/Microsoft.VSTS.Common.Priority"
              :value (get-in work-item [:fields "Microsoft.VSTS.Common.Priority"])}
             {:op "add"
              :path "/fields/System.History"
              :value (get-system-field work-item :History)}
             {:op "add"
              :path "/fields/System.CreatedBy"
              :value (get-system-field work-item :CreatedBy)}
             {:op "add"
              :path "/fields/System.Tags"
              :value (get-system-field work-item :Tags)}]

        ops-with-parent (if parent-already-created-work-item
                          (conj ops
                                {:op "add"
                                 :path "/relations/-"
                                 :value {:rel "System.LinkTypes.Hierarchy-Reverse"
                                         :url (:url parent-already-created-work-item)
                                         :attributes {}}})
                          ops)]
    (filter :value ops-with-parent)))


(defn create-tree
  [instance project tree options]
  (loop [old->new {} ;; maps old work item ids to new created work items
         tree tree] ;; a tree-seq of parent/children work items
    (let [old-item (first tree) ;; current node to create
          old-item-parent-id (parent-id old-item) ;; parent of item
          items-ops (operations-to-create-work-item
                     old-item
                     (get old->new old-item-parent-id))
          created-item (if-not (:dry-run options)
                         (api/create-work-item instance project
                                               (get-system-field old-item :WorkItemType)
                                               items-ops)
                         old-item)]
      (if (:dry-run options)
        (print "WOULD CREATE: ")
        (print "CREATED: "))
      (print (indentation-for-work-item created-item))
      (println (get-system-field old-item :WorkItemType) (str "(" (:id old-item) "):") (get-system-field old-item :Title))
      (let [updated-old->new (assoc old->new (get old-item :id) created-item)]
        (if-let [next-items (next tree)]
        (recur updated-old->new next-items)
        updated-old->new)))))


(defn create-all-work-items
  [instance project root grouped-by-parents options]
  (create-tree instance
               project
               (work-item-tree root grouped-by-parents)
               options))

(defn view-model [work-item]
  (let [team-project (get-system-field work-item :TeamProject)
        target-date-time (get-in work-item [:fields
                                            :Microsoft.VSTS.Scheduling.TargetDate])
        target-date (first (clojure.string/split target-date-time #"T"))
        html-url (str "https://msmobilecenter.visualstudio.com/"
                      team-project
                      "/_workitems?id="
                      (:id work-item)
                      "&_a=edit&fullScreen=true")]
    {:title (get-system-field work-item :Title)
     :target-date target-date
     :confidence (get-in work-item [:fields :Simpleagileprocess.CloseDateConfidence])
     :vsts-link html-url}))
