(ns income-converter.core
  (:require
   [cljsjs.react]
   [cljs.reader :as reader]
   [goog.i18n.NumberFormat]
   [sablono.core :as sab :include-macros true]))

(enable-console-print!)

;; constants

(def soc-sec-rate 0.123)
(def medicare-rate 0.030)
(def soc-sec-salary-cutoff 113700)

(def max-hourly-wage 200)
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
             {:key :low-hourly-wage
              :type "range"
              :label "Minimum hourly wage"}
             {:key :high-hourly-wage
              :type "range"
              :label "Maximum hourly wage"}])


;; user-alterable state

(def default-data {:hours-per-week 30
                   :weeks-off 4
                   :health-ins-diff 200

                   :low-hourly-wage 30
                   :high-hourly-wage 45})

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

(defonce app-state (atom (assoc (initial-state) :show-instructions? false)))


;; helpers

(defn input->int
  "This function is parseInt, except that the empty string
   -- meaning the user deleted everything in the box --
   counts as 0."
  [input]
  (if (= input "")
    0
    (js/parseInt input)))

(defn dollar-str
  "round to the nearest dollar, no decimal places"
  [n]
  (let [with-decimal
        (.format (goog.i18n.NumberFormat.
                  (.-CURRENCY goog.i18n.NumberFormat.Format)) n)]
    (subs with-decimal 0 (- (count with-decimal) 3))))



;; react components

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

                          low-hourly-wage
                          high-hourly-wage]}]
  (sab/html
   [:table.main-table
    [:thead
     [:tr
      [:th "Hourly wage"]
      [:th "Weekly income"]
      [:th "Yearly income"]
      [:th "If you'll be paid as an employee on a W-2, FTE salary equiv is:"]
      [:th "If you'll be an independent contractor, FTE salary equiv is:"]]]
    [:tbody
     (let [wage-range (range low-hourly-wage
                             (inc high-hourly-wage)
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
               :max max-hourly-wage
               :step 5
               :title val
               :on-change #(update! (.. % -target -value))}]])))

(defn input-section [update-app-state! display-vals]
  (sab/html
   [:div.input-section
    (for [{:keys [key type label]} inputs]
      (input-row {:val (get display-vals key)
                  :label label
                  :type type
                  :update! (partial update-app-state! key)}))]))

(defn header [toggle-show-instructions! show-instructions?]
  (sab/html
   [:div.header
    [:h1.main-title "Income conversion chart"]
    [:div.instructions-toggle {:on-click toggle-show-instructions!}
     (str "[" (if show-instructions? "hide" "show") " instructions]")]
    [:div.instructions {:class (when show-instructions? "show")}
     [:h2.sub-hed "A glorified excel spreadsheet for comparing hourly gigs to salaried jobs with benefits"]
     [:h4.sub-hed "When you're looking at hourly gigs and you want to find out what the salaried equivalents are -- that is, the salary you'd have to make in order to have the same amount of money left over after paying for taxes and health insurance -- just type in the following:"]
     [:ol
      [:li "The amount of hours you expect to work every week"]
      [:li "The total number of weeks you expect to take off each year"]
      [:li "The estimated health insurance subsidy: the difference between what you'd expect to pay for insurance on the open market, and what you'd expect to pay if you had an employer who was covering most of it"]]
     [:h4.sub-hed "The app will then take care of calculating Social Security and Medicare payroll taxes for those who are working as independent contractors and not W-2 employees."]
     [:h4.sub-hed [:em "Note: This is a work in progress. Among the many things not taken into account yet are Medicaid and Affordable Care Act subsidies below certain income thresholds, and overtime pay for hourly employees."]]]]))

(defn page [update-app-state! toggle-show-instructions! {:keys [show-instructions? data display]}]
  (sab/html
   [:div.page
    (header toggle-show-instructions! show-instructions?)
    (input-section update-app-state! display)
    (main-table data)]))


;; rendering and updating stuff

;; TODO: abstract out UI updating, so we don't have these two versions
;; that look almost the same but not; for updating numbers and updating
;; the show/hide instructions button.

(defn make-app-state-updater
  "takes a rendering function and returns a function that
   uses what the person actually typed to update the
   display-state, but does some validation and
   transformation of what it stores as data-state"
  [render]
  (fn [key val]
    (swap! app-state assoc-in [:display key] val)
    (when-let [n (input->int val)]
      (swap! app-state assoc-in [:data key] n)
      (. js/localStorage (setItem "app-data" (:data @app-state))))
    (render)))

(defn make-show-hide-instructions-state-updater
  [render]
  (fn []
    (swap! app-state update :show-instructions? not)
    (render)))

(defn render []
  (let [update-app-state! (make-app-state-updater render)
        toggle-show-instructions! (make-show-hide-instructions-state-updater render)
        node (.getElementById js/document "app")]
    (.render js/React (page update-app-state! toggle-show-instructions! @app-state) node)))

(render)



;; not currently used

(defn on-js-reload []
  #_(reset! app-state (initial-state)))
