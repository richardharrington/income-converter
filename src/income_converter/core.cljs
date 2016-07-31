(ns income-converter.core
  (:require
   [cljsjs.react]
   [sablono.core :as sab :include-macros true]
   [cljs.core.async :refer [<! chan sliding-buffer put! close! timeout]])
  (:require-macros
   [cljs.core.async.macros :refer [go-loop go]]))

(enable-console-print!)


(def soc-sec-rate 0.123)
(def medicare-rate 0.030)
(def soc-sec-salary-cutoff 113700)

; These will eventually be user input
(def hours-per-week 60)
(def weeks-off 5)
(def monthly-health-ins-diff 200)

(defn dollar-str [n]
  (. n (toLocaleString #js [] #js {:style "currency"
                                   :currency "USD"
                                   :maximumFractionDigits 0})))

(defn row [hourly-wage]
  (let [weekly-income (* hourly-wage hours-per-week)
        yearly-income (* weekly-income (- 52 weeks-off))
        if-w2 (- yearly-income (* monthly-health-ins-diff 12))
        soc-sec-tax (* (min yearly-income soc-sec-salary-cutoff)
                       (/ soc-sec-rate 2))
        medicare-tax (* yearly-income (/ medicare-rate 2))
        if-1099 (- if-w2 (+ soc-sec-tax medicare-tax))]
    (sab/html
     [:tr
      (for [n [hourly-wage weekly-income yearly-income if-w2 if-1099]]
        [:td (dollar-str n)])])))

(defn main-table [{:keys [min-hourly-wage
                          max-hourly-wage
                          hourly-wage-inc]}]
  (sab/html
   [:table
    [:tr
     [:th "hourly wage"]
     [:th "weekly income"]
     [:th "yearly income"]
     [:th "If contractor gets W-2, FTE salary equiv is:"]
     [:th "If contractor gets 1099, FTE salary equiv is:"]]
    (map row (range min-hourly-wage max-hourly-wage hourly-wage-inc))]))

(defn input-component [{:keys [hours-per-week
                               weeks-off
                               monthly-health-ins-diff]}]
  (sab/html
   [:input {:onChange #(println (aget % "target" "value"))}]
   [:div "input something here"]))

(defn page [props]
  (sab/html
   [:div.page
    [:h1 "hi"]
    (input-component {})
    (main-table props)]))


;; define your app data so that it doesn't get over-written on reload

(def initial-state {:min-hourly-wage 5
                    :max-hourly-wage 205
                    :hourly-wage-inc 5})

(defonce app-state (atom initial-state))

(defn on-js-reload []
  #_(reset! app-state initial-state)
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)

(defn render []

  (let [node (.getElementById js/document "app")]
    (.render js/React (page @app-state) node)))

(render)
