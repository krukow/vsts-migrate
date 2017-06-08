(defproject vsts-work-migrate "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :uberjar-name "vsts-migrate.jar"
  :main vsts-work-migrate.cli
  :profiles {:uberjar {:aot :all}}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-http "2.3.0"]
                 [cheshire "5.7.0"]
                 [org.clojure/tools.cli "0.3.5"]
                 [commons-codec/commons-codec "1.10"]
                 [enlive "1.1.6"]
                 [clj-time "0.13.0"]]
  :plugins [[cider/cider-nrepl "0.14.0"]
            [refactor-nrepl "2.2.0"]]

  :repl-options {:nrepl-middleware
                 [cider.nrepl.middleware.apropos/wrap-apropos
                  cider.nrepl.middleware.classpath/wrap-classpath
                  cider.nrepl.middleware.complete/wrap-complete
                  cider.nrepl.middleware.debug/wrap-debug
                  cider.nrepl.middleware.format/wrap-format
                  cider.nrepl.middleware.info/wrap-info
                  cider.nrepl.middleware.inspect/wrap-inspect
                  cider.nrepl.middleware.macroexpand/wrap-macroexpand
                  cider.nrepl.middleware.ns/wrap-ns
                  cider.nrepl.middleware.pprint/wrap-pprint
                  cider.nrepl.middleware.pprint/wrap-pprint-fn
                  cider.nrepl.middleware.refresh/wrap-refresh
                  cider.nrepl.middleware.resource/wrap-resource
                  cider.nrepl.middleware.stacktrace/wrap-stacktrace
                  cider.nrepl.middleware.test/wrap-test
                  cider.nrepl.middleware.trace/wrap-trace
                  cider.nrepl.middleware.out/wrap-out
                  cider.nrepl.middleware.undef/wrap-undef
                  cider.nrepl.middleware.version/wrap-version]})
