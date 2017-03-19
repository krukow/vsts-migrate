(ns vsts-work-migrate.config
  (:require [clojure.string :as string]
            [clj-http.conn-mgr :as conn-mgr]))

(defn- environment-config
  [name]
  (let [env (System/getenv name)]
    (if (string/blank? env)
      nil
      env)))


(defn mseng-personal-access-token
  []
  (environment-config "MSENG_ACCESS_TOKEN"))

(def mseng-http-options
  {:basic-auth (str ":" (mseng-personal-access-token))
   :socket-timeout 30000 ;; in milliseconds
   :conn-timeout 10000
   :connection-manager (conn-mgr/make-reusable-conn-manager {})
   :content-type :json
   :accept :json
   :debug false})

(defn msmobilecenter-personal-access-token
  []
  (environment-config "MOBILECENTER_ACCESS_TOKEN"))

(def msmobilecenter-http-options
  {:basic-auth (str ":" (msmobilecenter-personal-access-token))
   :socket-timeout 30000  ;; in milliseconds
   :conn-timeout 10000
   :connection-manager (conn-mgr/make-reusable-conn-manager {})
   :accept :json})

(def mseng-instance
  {:name "mseng.visualstudio.com"
   :http-options mseng-http-options})

(def msmobilecenter-instance
  {:name "msmobilecenter.visualstudio.com"
   :http-options msmobilecenter-http-options})
