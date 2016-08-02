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

(def input-labels [{:key :hours-per-week
                    :label "Hours per week"}
                   {:key :weeks-off
                    :label "Weeks off"}
                   {:key :health-ins-diff
                    :label "Differential in monthly health insurance payment"}
                   {:key :min-hourly-wage
                    :label "Minimum hourly wage in table"}
                   {:key :max-hourly-wage
                    :label "Maximum hourly wage"}])

;; user-alterable state

(def default-data {:hours-per-week 30
                   :weeks-off 4
                   :health-ins-diff 200

                   :min-hourly-wage 5
                   :max-hourly-wage 205})

(defn initial-state
  "Recover from localStorage or use default, but in either case
   use the data values for both data and display (no need to persist
   messiness and user errors through a refresh)"
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



(defn dollar-str [n]
  (. n (toLocaleString #js [] #js {:style "currency"
                                   :currency "USD"
                                   :maximumFractionDigits 0})))

(defn round-up-to-next-wage-step [n]
  (* hourly-wage-step (Math/ceil (/ n hourly-wage-step))))

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
   [:table
    [:thead
     [:tr
      [:th "hourly wage"]
      [:th "weekly income"]
      [:th "yearly income"]
      [:th "If contractor gets W-2, FTE salary equiv is:"]
      [:th "If contractor gets 1099, FTE salary equiv is:"]]]
    [:tbody
     (let [wage-range (range (round-up-to-next-wage-step min-hourly-wage)
                             (inc max-hourly-wage)
                             hourly-wage-step)]
       (map #(row {:hourly-wage %
                   :hours-per-week hours-per-week
                   :weeks-off weeks-off
                   :health-ins-diff health-ins-diff})
            wage-range))]]))

(defn input-row [{:keys [val label update!]}]
  (sab/html
   [:div.input-row
    [:input {:value val :on-change #(update! (aget % "target" "value"))}]
    [:label label]]))

(defn input-component [{:keys [update-app-state!] :as input-vals}]
  (sab/html
   [:div.input-section
    (for [{:keys [key label]} input-labels]
      (input-row {:val (get input-vals key)
                  :label label
                  :update! (partial update-app-state! key)}))]))


(defn input->int [input]
  (if (= input "")
    0
    (js/parseInt input)))

(declare render)

(defn page [{:keys [data display]}]
  (sab/html
   [:div.page
    [:h1 "Income conversion chart"]
    (input-component (assoc display :update-app-state!
                            (fn [key val]
                              (swap! app-state assoc-in [:display key] val)
                              (when-let [n (input->int val)]
                                (swap! app-state assoc-in [:data key] n)
                                (. js/localStorage (setItem "app-data" (:data @app-state))))
                              (render))))
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
