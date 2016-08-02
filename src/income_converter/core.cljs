(ns income-converter.core
  (:require
   [cljsjs.react]
   [cljs.reader :as reader]
   [sablono.core :as sab :include-macros true]
   [cljs.core.async :refer [<! chan sliding-buffer put! close! timeout]])
  (:require-macros
   [cljs.core.async.macros :refer [go-loop go]]))

(enable-console-print!)

;; constants

(def soc-sec-rate 0.123)
(def medicare-rate 0.030)
(def soc-sec-salary-cutoff 113700)

(def hourly-wage-step 5)

(def inputs [{:key :hours-per-week
              :type "text"
              :label "Hours per week"}
             {:key :weeks-off
              :type "text"
              :label "Weeks off"}
             {:key :health-ins-diff
              :type "text"
              :label "Monthly health insurance diff"}
             {:key :min-hourly-wage
              :type "range"
              :label "First hourly wage in table"}
             {:key :max-hourly-wage
              :type "range"
              :label "Last hourly wage in table"}])

;; user-alterable state

(def default-data {:hours-per-week 30
                   :weeks-off 4
                   :health-ins-diff 200

                   :min-hourly-wage 5
                   :max-hourly-wage 205})

(defn initial-state
  "Recover from localStorage or use default, but in either case
   use the 'data' values for both 'data' and 'display' (no need
   to persist messiness and user errors through a refresh)"
  []
  (let [app-data
        (if-let [stored-edn (. js/localStorage (getItem "app-data"))]
          (reader/read-string stored-edn)
          default-data)]
    {:data app-data
     :display app-data}))

(defn clear-app-data
  "a way to clear app data, for dev only"
  []
  (. js/localStorage (removeItem "app-data")))

#_(clear-app-data)

(defonce app-state (atom (initial-state)))

(defn input->int [input]
  (if (= input "")
    0
    (js/parseInt input)))

(declare render)

(defn update-app-state!
  "updates the display-state with what the person
   actually typed, but does some validation and
   transformation of what it stores as data-state"
  [key val]
  (swap! app-state assoc-in [:display key] val)
  (when-let [n (input->int val)]
    (swap! app-state assoc-in [:data key] n)
    (. js/localStorage (setItem "app-data" (:data @app-state))))
  (render))


(defn dollar-str [n]
  (. n (toLocaleString #js [] #js {:style "currency"
                                   :currency "USD"
                                   :maximumFractionDigits 0})))

(defn row [{:keys [hourly-wage
                   hours-per-week
                   weeks-off
                   health-ins-diff]}]
  (let [weekly-income (* hourly-wage hours-per-week)
        yearly-income (* weekly-income (- 52 weeks-off))
        if-w2 (- yearly-income (* health-ins-diff 12))
        soc-sec-tax (* (min yearly-income soc-sec-salary-cutoff)
                       (/ soc-sec-rate 2))
        medicare-tax (* yearly-income (/ medicare-rate 2))
        if-1099 (- if-w2 (+ soc-sec-tax medicare-tax))]
    (sab/html
     [:tr
      (for [n [hourly-wage weekly-income yearly-income if-w2 if-1099]]
        [:td (dollar-str n)])])))

(defn main-table [{:keys [hours-per-week
                          weeks-off
                          health-ins-diff

                          min-hourly-wage
                          max-hourly-wage]}]
  (sab/html
   [:table.main-table
    [:thead
     [:tr
      [:th "hourly wage"]
      [:th "weekly income"]
      [:th "yearly income"]
      [:th "If contractor gets W-2, FTE salary equiv is:"]
      [:th "If contractor gets 1099, FTE salary equiv is:"]]]
    [:tbody
     (let [wage-range (range min-hourly-wage
                             (inc max-hourly-wage)
                             hourly-wage-step)]
       (map #(row {:hourly-wage %
                   :hours-per-week hours-per-week
                   :weeks-off weeks-off
                   :health-ins-diff health-ins-diff})
            wage-range))]]))

(defn input-row [{:keys [val label type update!]}]
  (let [label-text (str label (when (= type "range") (str ": " val)))]
    (sab/html
     [:div.input-row
      [:label label-text]
      [:input {:value val
               :type type
               :min 0
               :max 300
               :step 5
               :title val
               :on-change #(update! (aget % "target" "value"))}]])))

(defn input-section [input-vals]
  (sab/html
   [:div.input-section
    (for [{:keys [key type label]} inputs]
      (input-row {:val (get input-vals key)
                  :label label
                  :type type
                  :update! (partial update-app-state! key)}))]))

(defn page [{:keys [data display]}]
  (sab/html
   [:div.page
    [:h1.main-title "Income conversion chart"]
    (input-section display)
    (main-table data)]))

(defn render []
  (println @app-state)

  (let [node (.getElementById js/document "app")]
    (.render js/React (page @app-state) node)))

(render)



(defn on-js-reload []
  #_(reset! app-state initial-state)
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  )
