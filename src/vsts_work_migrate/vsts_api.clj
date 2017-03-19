(ns vsts-work-migrate.vsts-api
   (:require [clojure.java.io :as io]
             [clojure.string :as str]
             [clj-http.client :as client]
             [vsts-work-migrate.config :as cfg]
             [cheshire.core :as json])
   (:import java.net.URLEncoder))


(defn work-item-comments-url
  [instance id]
  (str "https://" (:name instance)
       "/_apis/wit/workitems/" id "/comments?api-version=3.0-preview"))

(defn work-item-url [instance id]
  (str "https://" (:name instance)
       "/DefaultCollection/_apis/wit/workitems/" id "?api-version=3.0-preview"))


(defn work-items-url [instance ids]
  (str "https://" (:name instance)
       "/DefaultCollection/_apis/wit/workitems?api-version=3.0-preview&ids=" (str/join "," ids)
       "&$expand=relations"))

;PATCH https://{instance}/DefaultCollection/{project}/_apis/wit/workitems/${workItemTypeName}?api-version={version}

(defn work-items-create-url [instance project work-item-type-name]
  (str "https://" (:name instance)
       "/DefaultCollection/" project "/_apis/wit/workitems/$" work-item-type-name "?bypassRules=true&api-version=3.0-preview"))



(defn wiql-url [instance project]
  (str "https://" (:name instance)
       "/DefaultCollection/" project "/_apis/wit/wiql?api-version=3.0-preview"))

(defn get-work-item-comments
  [instance id]
  (let [response (client/get (work-item-comments-url instance id)
                             (:http-options instance))]
      (json/parse-string (:body response) true)))


(defn get-work-items
  [instance ids]
  (let [response (client/get (work-items-url instance ids)
                             (:http-options instance))]
    (json/parse-string (:body response) true)))


(defn create-work-item
  [instance project work-item-type-name work-item-patch]
  (let [response (client/patch (work-items-create-url instance project work-item-type-name)
                               (merge
                                (:http-options instance)
                                {:body (json/generate-string work-item-patch)
                                 :content-type "application/json-patch+json"
                                 }))]
    (json/parse-string (:body response) true)))


(defn delete-work-item
  [instance id]
  (let [response (client/delete (work-item-url instance id)
                                (:http-options instance))]
    response))

(defn query-work-items
  [instance project query]
  (let [response
        (client/post (wiql-url instance project)
                     (merge
                      (:http-options instance)
                      {:form-params {:query query} :content-type :json}))]
    (json/parse-string (:body response) true)))
