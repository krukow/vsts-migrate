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



(defn work-item-children [work-item]
  (seq (:children work-item)))

(defn work-item-tree
  [root]
  (tree-seq #(work-item-children %)
            #(work-item-children %)
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
  [instance project root options]
  (create-tree instance
               project
               (work-item-tree root)
               options))
