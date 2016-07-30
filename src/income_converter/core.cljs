(ns income-converter.core
  (:require
   [cljsjs.react]
   [sablono.core :as sab :include-macros true]
   [cljs.core.async :refer [<! chan sliding-buffer put! close! timeout]])
  (:require-macros
   [cljs.core.async.macros :refer [go-loop go]]))

(enable-console-print!)

(defn table-comp [{:keys [hourly-gross
                          gross-income
                          if-W-2
                          if-1099] :as state}]
  (println state)
  (sab/html [:table
             [:tr
              [:th "hourly wage"]
              [:th "gross income"]
              [:th "If contractor gets W-2, FTE salary equiv is:"]
              [:th "If contractor gets 1099, FTE salary equiv is:"]]
             [:tr
              [:td (*  hourly-gross 200)
               ]
              [:td gross-income]
              [:td if-W-2]
              [:td if-1099]
              ]]))


;; define your app data so that it doesn't get over-written on reload

(def initial-state {:hourly-gross 10
                    :gross-income 18800
                    :if-W-2 16400
                    :if-1099 14962})

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
