(ns vsts-work-migrate.cli
  (:require [clojure.tools.cli :as cli]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [vsts-work-migrate.core :as core]
            [vsts-work-migrate.config :as cfg])
  (:gen-class :main true))


(defn- dump-usage
  [cli-summary]
  (binding [*out* *err*]
    (println "USAGE:")
    (println cli-summary)
    (println "\nTools:
    dump-from-mseng             - Dump (and pretty print) WIQL query results from MSEng instance. Arguments: <wiql-file-path> <mappings.json> <dump-file-path>
    copy-to-msmobilecenter      - Copy WIQL query result to msmobilecenter. Arguments: <dump-file-path> <Project> <saved-results-output-path>
    delete-from-msmobilecenter  - Delete previously copied work-items from msmobilecenter (i.e. \"undo\" creation). Arguments: <saved-results-output-path>.
    ")))


(def cli-options
  [["-h" "--help"]
   ["-d" "--dry-run"]])

(declare dump-from-mseng copy-to-msmobilecenter delete-from-msmobilecenter)

(defn -main
  [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options)]
    ;; Handle parsed command line options
    (cond
      errors (binding [*out* *err*]
               (println "** Failed to parse command line options:" errors)
               (dump-usage summary)
               (System/exit 1))
      (:help options) (do
                        (dump-usage summary)
                        (System/exit 0)))

    (when (clojure.string/blank? (cfg/mseng-personal-access-token))
      (println "You must specify environment variable:" "MSENG_ACCESS_TOKEN")
      (System/exit 1))
    (when (clojure.string/blank? (cfg/msmobilecenter-personal-access-token))
      (println "You must specify environment variable:" "MOBILECENTER_ACCESS_TOKEN")
      (System/exit 1))



    ;; Launch selected tool
    (try
      (case (first arguments)
        "dump-from-mseng" (dump-from-mseng options (rest arguments))
        "copy-to-msmobilecenter" (copy-to-msmobilecenter options (rest arguments))
        "delete-from-msmobilecenter" (delete-from-msmobilecenter options (rest arguments))
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

;;    dump-from-mseng             - Dump (and pretty print) WIQL query results from MSEng instance. Arguments: <wiql-file-path> <mappings.json> <dump-file-path>

(defn dump-from-mseng [options args]
  (let [[wiql-file-path mappings-file-path dump-file-path] args]
    (when-not (and wiql-file-path (.exists (io/file wiql-file-path)))
      (println "WIQL File does not exist:" wiql-file-path)
      (throw (RuntimeException. (str "File does not exist:" wiql-file-path))))
    (when-not (and mappings-file-path (.exists (io/file mappings-file-path)))
      (println "Mappings File does not exist:" mappings-file-path)
      (throw (RuntimeException. (str "File does not exist:" mappings-file-path))))
    (when-not (and dump-file-path (io/file dump-file-path))
      (println "Ouput file" dump-file-path)
      (throw (RuntimeException. (str "Output file invalid:" dump-file-path))))

    (println "Dumping" wiql-file-path "from mseng via mapping:" mappings-file-path)

    (println "Options" options)

    (core/dump wiql-file-path mappings-file-path dump-file-path options)))


;;    copy-to-msmobilecenter      - Copy WIQL query result to msmobilecenter. Arguments: <dump-file-path> <Project> <saved-results-output-path>
(defn copy-to-msmobilecenter [options args]
  (let [[dump-file-path project saved-results-output-path] args]
    (when-not (and dump-file-path (.exists (io/file dump-file-path)))
      (println "Dump File does not exist:" dump-file-path)
      (throw (RuntimeException. (str "File does not exist:" dump-file-path))))

    (when-not project
      (println "Project must be specified")
      (throw (RuntimeException. "Project not specified" )))

    (when-not (and saved-results-output-path (io/file saved-results-output-path))
      (println "Ouput file invalid" saved-results-output-path)
      (throw (RuntimeException. (str "Output file invalid:" saved-results-output-path))))

    (println "Copying results:" dump-file-path "to msmobilecenter and saving to" saved-results-output-path)

    (println "Options:" options)


    (let [created-items (core/recreate-work-items
                         cfg/msmobilecenter-instance
                         project
                         (json/parse-string (slurp (io/file dump-file-path)) true)
                         options)
          saved-results-file (io/file saved-results-output-path)]
      (println "Saving created items as mapping from mseng items to msmobilcenter items as" (.getAbsolutePath saved-results-file))
      (spit saved-results-file (json/generate-string created-items {:pretty true})))))


;; SOME BUG HERE NOT ALL DELETED
;;    delete-from-msmobilecenter  - Delete previously copied work-items from msmobilecenter (i.e. \"undo\" creation). Arguments: <saved-results-output-path>.
(defn delete-from-msmobilecenter
  [options args]
  (let [[saved-results-output-path] args]
    (when-not (and saved-results-output-path (.exists
                                              (io/file saved-results-output-path)))
      (println "Saved results File does not exist:" saved-results-output-path)
      (throw (RuntimeException. (str "File does not exist:" saved-results-output-path))))


    (println "Deleting work items from msmobilecenter" saved-results-output-path)

    (println "Options:" options)


    (let [deleted-items (core/delete-tree
                         cfg/msmobilecenter-instance
                         (json/parse-string (slurp (io/file saved-results-output-path))
                                            true)
                         options)]
      (println "Done deleting items"))))
