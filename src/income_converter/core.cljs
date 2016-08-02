(ns income-converter.core
  (:require
   [cljsjs.react]
   [sablono.core :as sab :include-macros true]
   [cljs.core.async :refer [<! chan sliding-buffer put! close! timeout]])
  (:require-macros
   [cljs.core.async.macros :refer [go-loop go]]))

(enable-console-print!)


;; define your app data so that it doesn't get over-written on reload

(def initial-state {:data    {:hours-per-week 30
                              :weeks-off 4
                              :monthly-health-ins-diff 200

                              :min-hourly-wage 5
                              :max-hourly-wage 205}

                    :display {:hours-per-week 30
                              :weeks-off 4
                              :monthly-health-ins-diff 200

                              :min-hourly-wage 5
                              :max-hourly-wage 205}})

(defonce app-state (atom initial-state))



(def soc-sec-rate 0.123)
(def medicare-rate 0.030)
(def soc-sec-salary-cutoff 113700)

(def hourly-wage-inc 5)

(defn dollar-str [n]
  (. n (toLocaleString #js [] #js {:style "currency"
                                   :currency "USD"
                                   :maximumFractionDigits 0})))

(defn round-up-to-wage-inc [n]
  (* hourly-wage-inc (Math/ceil (/ n hourly-wage-inc))))

(defn row [{:keys [hourly-wage
                   hours-per-week
                   weeks-off
                   monthly-health-ins-diff]}]
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

(defn main-table [{:keys [hours-per-week
                          weeks-off
                          monthly-health-ins-diff

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
     (let [wage-range (range (round-up-to-wage-inc min-hourly-wage)
                             (inc max-hourly-wage)
                             hourly-wage-inc)]
       (map #(row {:hourly-wage %
                   :hours-per-week hours-per-week
                   :weeks-off weeks-off
                   :monthly-health-ins-diff monthly-health-ins-diff})
            wage-range))]]))

(defn input-row [{:keys [val display update!]}]
  (sab/html
   [:div.input-row
    [:input {:value val :on-change #(update! (aget % "target" "value"))}]
    [:label display]]))

(defn input-component [{:keys [update-app-state!

                               hours-per-week
                               weeks-off
                               monthly-health-ins-diff

                               min-hourly-wage
                               max-hourly-wage]}]
  (sab/html
   [:div.input-section
    (map input-row [{:val hours-per-week
                     :display "Hours per week"
                     :update! (partial update-app-state! :hours-per-week)}
                    {:val weeks-off
                     :display "Weeks off"
                     :update! (partial update-app-state! :weeks-off)}
                    {:val monthly-health-ins-diff
                     :display "Differential in monthly health insurance payment"
                     :update! (partial update-app-state! :monthly-health-ins-diff)}
                    {:val min-hourly-wage
                     :display "Minimum hourly wage in table"
                     :update! (partial update-app-state! :min-hourly-wage)}
                    {:val max-hourly-wage
                     :display "Maximum hourly wage"
                     :update! (partial update-app-state! :max-hourly-wage)}])]))


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
                                (swap! app-state assoc-in [:data key] n))
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
