(ns income-converter.core
  (:require
   [cljsjs.react]
   [sablono.core :as sab :include-macros true]
   [cljs.core.async :refer [<! chan sliding-buffer put! close! timeout]])
  (:require-macros
   [cljs.core.async.macros :refer [go-loop go]]))

(enable-console-print!)


(def soc-sec-tax-rate 0.123)
(def medicare-tax-rate 0.030)
(def max-salary-for-soc-sec-tax 113700)
(def employee-share-of-soc-sec (/ soc-sec-tax-rate 2))
(def employee-share-of-medicare (/ medicare-tax-rate 2))

; These will eventually be user input
(def hours-per-week 40)
(def weeks-off-per-year 5)
(def monthly-health-ins-diff 200)


(defn row [hourly-wage]
  (let [gross-income (* hourly-wage hours-per-week (- 52 weeks-off-per-year))
        if-W2 (- gross-income (* monthly-health-ins-diff 12))
        soc-sec (* (min gross-income max-salary-for-soc-sec-tax)
                   employee-share-of-soc-sec)
        medicare (* gross-income employee-share-of-medicare)
        if-1099 (- if-W2 (+ soc-sec medicare))]
    (sab/html
     [:tr
      [:td hourly-wage]
      [:td gross-income]
      [:td if-W2]
      [:td if-1099]])))

(defn table-comp [{:keys [min-hourly-wage
                          max-hourly-wage
                          hourly-wage-inc] :as state}]
  (println state)
  (sab/html
   [:table
    [:tr
     [:th "hourly wage"]
     [:th "gross income"]
     [:th "If contractor gets W-2, FTE salary equiv is:"]
     [:th "If contractor gets 1099, FTE salary equiv is:"]]
    (map row (range min-hourly-wage max-hourly-wage hourly-wage-inc))]))


;; define your app data so that it doesn't get over-written on reload

(def initial-state {:min-hourly-wage 5
                    :max-hourly-wage 205
                    :hourly-wage-inc 5})

(defonce app-state (atom initial-state))

(println @app-state)

(defn on-js-reload []
  #_(reset! app-state initial-state)
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
)

(defn render []

  (let [node (.getElementById js/document "app")]
    (.render js/React (table-comp @app-state) node)))

(render)
