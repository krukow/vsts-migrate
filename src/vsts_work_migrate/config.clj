(ns vsts-work-migrate.config
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [clj-http.conn-mgr :as conn-mgr]
            [clojure.tools.reader.edn :as edn]))

(defn- environment-config
  [name]
  (let [env (System/getenv name)]
    (if (string/blank? env)
      nil
      env)))

(def ^:dynamic *debug-http* false)
(def ^:dynamic *source-access-token* nil)
(def ^:dynamic *target-access-token* nil)
(def ^:dynamic *config-override* nil)

(defn- debug-from-env?
  []
  (let [env (System/getenv "DEBUG")]
    (boolean (or *debug-http* (= "1" env)))))

(defn- load-config-from-file
  [path]
  (cond
    (nil? path)
    {}

    (not (.exists (io/file path)))
    (throw (RuntimeException.
            (str "ADO_MIGRATE_CONFIG File: " path " set, but does not exist.")))

    :else (-> (io/file path)
              slurp
              edn/read-string)))

(defn config-file
  []
  (environment-config "ADO_MIGRATE_CONFIG"))

(defn deep-merge
  "Merges maps of similar shapes (used for default overriding config files).
  The default must have all the keys present."
  [default overrides]
  (letfn [(deep-merge-rec [a b]
            (if (map? a)
              (merge-with deep-merge-rec a b)
              b))]
    (reduce deep-merge-rec nil (list default overrides))))

(def default-config {})

(defn set-config-override!
  [config-override]
  (when-not (map? config-override)
    (throw (ex-info "Invalid Configuration"
                    {:reason "Configuration must be a map"})))

  (when-not (every? #(contains? config-override %)
                    [:ado-migrate.config/source
                     :ado-migrate.config/target
                     :ado-migrate.config/mapping])
    (throw (ex-info "Invalid Configuration"
                    {:reason "Invalid keys"
                     :required-keys
                     [:ado-migrate.config/source
                      :ado-migrate.config/target
                      :ado-migrate.config/mapping]
                     :found-keys (keys config-override)})))

  (alter-var-root (var *config-override*) (constantly config-override)))

(defn- config-override
  []
  (if *config-override*
    *config-override*
    (load-config-from-file (config-file))))

(defn config
  []
  (deep-merge default-config (config-override)))

(defn source-access-token
  []
  (or *source-access-token* (environment-config "ADO_SOURCE_ACCESS_TOKEN")))

(defn target-access-token
  []
  (or *target-access-token* (environment-config "ADO_TARGET_ACCESS_TOKEN")))

(defn source-http-options
  []
  {:basic-auth (str ":" (source-access-token))
   :socket-timeout 30000 ;; in milliseconds
   :conn-timeout 10000
   :connection-manager (conn-mgr/make-reusable-conn-manager {})
   :content-type :json
   :accept :json})

(defn target-http-options
  []
  {:basic-auth (str ":" (target-access-token))
   :socket-timeout 30000 ;; in milliseconds
   :conn-timeout 10000
   :connection-manager (conn-mgr/make-reusable-conn-manager {})
   :content-type :json
   :accept :json})

(defn source-instance
  []
  {:name (get-in (config) [:ado-migrate.config/source
                           :ado-migrate.config/instance])
   :http-options (assoc (source-http-options) :debug (debug-from-env?))})

(defn target-instance
  []
  {:name (get-in (config) [:ado-migrate.config/target
                           :ado-migrate.config/instance])
   :http-options (assoc (target-http-options) :debug (debug-from-env?))})
