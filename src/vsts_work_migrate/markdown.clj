(ns vsts-work-migrate.markdown
  (:import (com.overzealous.remark Remark Options)))


(defn- markdown-options
  []
  (let [opt (Options/markdown)]
    (set! (. opt inlineLinks) true)
    (set! (. opt tables)  com.overzealous.remark.Options$Tables/CONVERT_TO_CODE_BLOCK)
    opt))


(defn html->markdown
  [html]
  (comment  (let [remark (Remark. (markdown-options))]
              (if-not (clojure.string/blank? html)
                (str "<div style=\"display:none;width:0;height:0;overflow:hidden;position:absolute;font-size:0;\" id=__md>" (.convertFragment remark html) "</div>")
                "")))
  html)
