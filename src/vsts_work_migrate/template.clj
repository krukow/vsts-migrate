(ns vsts-work-migrate.template
  (:require [net.cgrand.enlive-html :as html]
            [clj-time.core :as time]
            [clj-time.format :as format]))

(def ^:dynamic *dummy-objectives*
     {:title "Enlive Template2 Tutorial"
      :work-items [{:title "Auto Provisioning MVP (without secrets storage)"
                    :target-date "August 3rd"
                    :confidence "Medium"
                    :vsts-link "https://msmobilecenter.visualstudio.com/Project-100/_workitems?id=7417&_a=edit&fullScreen=true"}]})


(def ^:dynamic *objective-sel*
  [[:#key-objectives-vsts] :> html/first-child])

(html/defsnippet objective "templates/weekly.html" *objective-sel*
  [{:keys [title target-date confidence vsts-link]}]
  [:li]
  (html/content
   (html/html
    [:i (str title ":")]
    [:table
     [:tr
      [:td "Target Date:"]
      [:td [:b target-date]]]
     [:tr
      [:td "Confidence:"]
      [:td [:b confidence]]]]
    [:span "Reduce uncertainty by:"]
    [:ul [:li "bla bla bla"]]
    [:span [:a {:href vsts-link} "VSTS work-item"]]
    [:br]
    [:br])))

(defn on-monday []
  (let [now (time/now)]
    (time/plus
     now
     (time/days (mod (- 8 (time/day-of-week now))
                     8)))))

(html/deftemplate main-template "templates/weekly.html"
  [objectives]
  [:span#title] (html/content "Eng. status "
                              (format/unparse (format/formatters :year-month-day) (on-monday))
                              ": Auto Provisioning, Azure Billing, Test Cloud")
  [:ul#key-objectives-vsts]
  (html/content (map #(objective %) objectives)))

(defn run-template
  [input-template objectives]
  (let [my-template
        (html/template
          input-template
          [objectives]
          [:#key-objectives-vsts]
          (html/content (map #(objective %) objectives))
          [:#weekly-progress]
          (html/move [[:#this-week] :> :li] [:#last-week] html/content)
          [:#this-week] (html/content (html/html [:li])))]
    (my-template objectives)))
