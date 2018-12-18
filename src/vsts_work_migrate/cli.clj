(ns vsts-work-migrate.cli
  (:require [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [clojure.tools.reader.edn :as edn]
            [vsts-work-migrate.core :as core]
            [vsts-work-migrate.config :as cfg])
  (:gen-class :main true))


(defn- dump-usage
  [cli-summary]
  (binding [*out* *err*]
    (println "USAGE:")
    (println cli-summary)
    (println "\nTools:
    dump        - Dump work items from source. Arguments: <dump-file-path>
    copy        - Copy WIQL query result to target.   Arguments: <saved-results-output-path>
    delete      - Delete previously copied work-items from target (i.e. \"undo\" creation). Arguments: <saved-results-output-path>.
    ")))


(def cli-options
  [["-h" "--help"]
   ["-d" "--dry-run"]
   ["-c" "--config-file"]
   ["-w" "--work-items"]])

(declare dump copy delete)

(defn -main
  [& args]
  (let [{:keys [options arguments summary errors]}
        (cli/parse-opts args cli-options)]
    ;; Handle parsed command line options
    (cond
      errors (binding [*out* *err*]
               (println "** Failed to parse command line options:" errors)
               (dump-usage summary)
               (System/exit 1))
      (:help options) (do
                        (dump-usage summary)
                        (System/exit 0)))

    (when (clojure.string/blank? (cfg/source-access-token))
      (println "You must specify environment variable:" "ADO_SOURCE_ACCESS_TOKEN")
      (System/exit 1))
    (when (clojure.string/blank? (cfg/target-access-token))
      (println "You must specify environment variable:" "ADO_TARGET_ACCESS_TOKEN")
      (System/exit 1))

    (let [config-path (or (:config-file options) (cfg/config-file))]
      (when (or (clojure.string/blank? config-path)
                (not (.exists (io/file config-path))))
        (println "You must specify configuration via --config-file or ADO_MIGRATE_CONFIG")
        (System/exit 1))

      (cfg/set-config-override! (-> (io/file config-path)
                                   slurp
                                   edn/read-string)))

    ;; Launch selected tool
    (try
      (case (first arguments)
        "dump"     (dump   options  (rest arguments))
        "copy"     (copy   options (rest arguments))
        "delete"   (delete options (rest arguments))
        (binding [*out* *err*]
          (println "** No such tool:" (first arguments))
          (dump-usage summary)
          (System/exit 1)))
      (catch Exception e
        (do
          (println e "Uncaught throwable")
          (if (System/getenv "DEBUG") (.printStackTrace e))
          (System/exit 27))))
    (System/exit 0)))



(defn dump [options args]
  (let [[dump-file-path] args]
    (when-not (:work-items options)
      (println "--work-items not specified...")
      (throw (RuntimeException. (str "Must specify --work-items"))))

    (when-not (and dump-file-path (io/file dump-file-path))
      (println "Ouput file" dump-file-path)
      (throw (RuntimeException. (str "Output file invalid:" dump-file-path))))

    (println "Dumping " (:work-items options) " from source...")

    (core/dump (io/file dump-file-path) options)))


(defn copy [options args]
  (let [[saved-results-output-path] args]
    (when-not (:work-items options)
      (println "--work-items not specified...")
      (throw (RuntimeException. (str "Must specify --work-items"))))

    (when-not (and saved-results-output-path (io/file saved-results-output-path))
      (println "Ouput file invalid: " saved-results-output-path)
      (throw (RuntimeException. (str "Output file invalid: " saved-results-output-path))))

    (println "Copying results: " (:work-items options) " to target and saving to " saved-results-output-path)

    (let [created-items (core/copy
                         (:work-items options)
                         options)
          saved-results-file (io/file saved-results-output-path)]
      (println "Saving created items: " (.getAbsolutePath saved-results-file))
      (spit saved-results-file (json/generate-string created-items {:pretty true})))))


(defn delete
  [options args]
  (throw (RuntimeException. "Not implemented yet"))
  (let [[saved-results-output-path] args]
    (when-not (and saved-results-output-path (.exists
                                              (io/file saved-results-output-path)))
      (println "Saved results File does not exist:" saved-results-output-path)
      (throw (RuntimeException. (str "File does not exist:" saved-results-output-path))))


    (println "Deleting work items from msmobilecenter" saved-results-output-path)

    (let [deleted-items (core/delete
                         (json/parse-string (slurp (io/file saved-results-output-path))
                                            true)
                         options)]
      (println "Done deleting items"))))
