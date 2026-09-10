(ns income-converter.core
  (:require
   ["react-dom/client" :as rdom]
   [cljs.reader :as reader]
   [goog.i18n.NumberFormat]
   [sablono.core :as sab :include-macros true]
   [sablono.interpreter]))

(enable-console-print!)

;; Sablono routes every :input, :select and :textarea carrying a :value
;; through its own class component, which reads the live DOM node with
;; ReactDOM.findDOMNode. React 19 removed findDOMNode, so the first keystroke
;; in any box threw and unmounted the entire app. There is no way to opt out
;; of that wrapper from the call site -- sablono picks it in element-class,
;; for any controlled form element -- so the check that selects it is disabled
;; here.
;;
;; What the wrapper does is work around an old IE bug where onChange arrived
;; after the element's value had already changed (React #7027). React handles
;; controlled inputs correctly on its own now, and sablono has not shipped a
;; release since 2019, so this is not waiting on an upgrade. Removing it also
;; drops the componentWillReceiveProps deprecation warning it caused.
;;
;; test/smoke.mjs types into the inputs; if a sablono change ever makes this
;; ineffective, that fails rather than reaching a user.
(set! sablono.interpreter/controlled-input? (constantly false))

;; helpers

;; Anything parseFloat would accept but we do not want: "Infinity" parses to
;; Infinity, which is a number, prints as ##Inf, and reads back out of
;; localStorage as ##Inf -- so it would persist across a reload. Validate the
;; string first and parse only what matches.
(def ^:private numeric-input-re #"^-?(?:\d+\.?\d*|\.\d+)$")

(defn input->number
  "Reads what the user typed as a number, decimals included.
   The empty string -- meaning the box was cleared -- counts as 0,
   and anything unparseable is nil rather than NaN. NaN is truthy in
   CLJS, so returning it would sail through the when-let in
   update-app-state! and poison the arithmetic in every table row."
  [input]
  (let [s (.trim (str input))]
    (if (= s "")
      0
      (when (re-matches numeric-input-re s)
        (let [n (js/parseFloat s)]
          (when (js/isFinite n)
            n))))))

(defn dollar-str
  "round to the nearest dollar, no decimal places"
  [n]
  (let [fmt (goog.i18n.NumberFormat.
              (.-CURRENCY goog.i18n.NumberFormat.Format))]
    ;; Minimum first: the currency pattern starts at two fraction digits
    ;; either way, and this order never has the minimum above the maximum.
    (.setMinimumFractionDigits fmt 0)
    (.setMaximumFractionDigits fmt 0)
    (.format fmt n)))



;; constants

;; Combined employer + employee payroll tax rates, halved at the point
;; of use to model the employer's share. Tax year 2026; the Social
;; Security wage base is adjusted by the SSA every year, so this cutoff
;; needs revisiting each January. Nothing in the build will notice when
;; it goes stale, so tax-year is rendered next to the table, where the
;; person reading the numbers can.
(def tax-year 2026)
(def soc-sec-rate 0.124)
(def medicare-rate 0.029)
(def soc-sec-salary-cutoff 184500)

;; Self-employment tax is levied on 92.35% of net self-employment
;; earnings, not on all of them -- the statutory factor mirrors the fact
;; that an employer's half of FICA is not part of an employee's wages.
;; It is numerically the same as the take-home factor below, and that is
;; not a coincidence: both exist to undo the same circularity. They are
;; separate defs because one is a statute and the other is arithmetic on
;; the rates above.
(def se-tax-base-factor 0.9235)

(def inputs [{:key :hourly-wage
              :label "Hourly wage"}
             {:key :hours-per-week
              :label "Hours per week"}
             {:key :weeks-off
              :label "Weeks off"}
             {:key :health-ins-diff
              :label "Monthly health insurance diff"}])


;; user-alterable state

(def default-data {:hourly-wage 30
                   :hours-per-week 30
                   :weeks-off 4
                   :health-ins-diff 200})

(defn usable-number?
  [x]
  (and (number? x) (js/isFinite x)))

(defn stored-app-data
  "Persisted data, or nil when there is nothing usable stored.
   Storage written before input->number guarded against NaN and
   Infinity can hold values that no longer read back as usable
   numbers, and storage written before the wage sliders became a
   single hourly-wage box has keys this no longer renders from, so
   anything unreadable, non-numeric or not shaped like default-data
   is discarded in favor of the defaults. A missing key would
   otherwise reach the arithmetic as nil and quietly compute $0."
  []
  (when-let [stored-edn (. js/localStorage (getItem "app-data"))]
    (let [parsed (try
                   (reader/read-string stored-edn)
                   (catch :default _ nil))]
      (when (and (map? parsed)
                 (= (set (keys parsed)) (set (keys default-data)))
                 (every? usable-number? (vals parsed)))
        parsed))))

(defn initial-state
  "Recover from localStorage or use default, but in either case
   use the 'data' values for both 'data' and 'display' (no need
   to persist messiness and user errors through a refresh)"
  []
  (let [app-data (or (stored-app-data) default-data)]
    {:data app-data
     :display app-data}))

(defn clear-app-data
  "a way to clear app data, for dev only"
  []
  (. js/localStorage (removeItem "app-data")))

#_(clear-app-data)

(defonce app-state (atom (assoc (initial-state) :show-instructions? false)))

(defn update-app-state!
  "uses what the person actually typed to update the
   display-state, but does some validation and
   transformation of what it stores as data-state"
  [key val]
  (swap! app-state assoc-in [:display key] val)
  (when-let [n (input->number val)]
    (swap! app-state assoc-in [:data key] n)
    ;; pr-str rather than letting setItem coerce the map: cljs.reader
    ;; reads this back on the next load, so the round trip is
    ;; intentional and not a happy accident of how maps print.
    (. js/localStorage (setItem "app-data" (pr-str (:data @app-state))))))

(defn toggle-show-instructions! []
  (swap! app-state update :show-instructions? not))



;; business logic

(defn employee-fica
  "The employee's half of FICA on a salary. The Social Security half
   stops at the wage base; the Medicare half does not."
  [salary]
  (+ (* (min salary soc-sec-salary-cutoff) (/ soc-sec-rate 2))
     (* salary (/ medicare-rate 2))))

(defn self-employment-tax
  "What a contractor pays: both halves of FICA, on 92.35% of earnings.
   Note the Social Security cap applies to that reduced base, not to
   gross."
  [gross]
  (let [base (* gross se-tax-base-factor)]
    (+ (* (min base soc-sec-salary-cutoff) soc-sec-rate)
       (* base medicare-rate))))

(defn equivalent-salary
  "The salary S whose take-home matches `take-home`, i.e. the S solving

     S - employee-fica(S) = take-home

   The salary being solved for is lower than the hourly gross and so
   carries less payroll tax of its own; subtracting from the gross
   instead -- which is what this app used to do -- short-circuits that
   fixed point. No bisection is needed: employee-fica is piecewise
   linear in S, so there are two closed forms. Try the below-the-cap
   one and use it when its own answer is in fact below the cap."
  [take-home]
  (let [below-cap (/ take-home (- 1 (/ soc-sec-rate 2) (/ medicare-rate 2)))]
    (if (<= below-cap soc-sec-salary-cutoff)
      below-cap
      (/ (+ take-home (* soc-sec-salary-cutoff (/ soc-sec-rate 2)))
         (- 1 (/ medicare-rate 2))))))

(defn row-figures
  "Every figure on one line of the table. Pure, and the only place the
   arithmetic lives; `row` just renders what this returns.

   Only what differs between the two sides of the comparison is
   modelled -- see the instructions panel."
  [{:keys [hourly-wage
           hours-per-week
           weeks-off
           health-ins-diff]}]
  (let [weekly-income (* hourly-wage hours-per-week)
        yearly-income (* weekly-income (- 52 weeks-off))
        yearly-insurance (* health-ins-diff 12)
        ;; An hourly W-2 employee owes the employee half of FICA on the
        ;; whole gross, and buys their own insurance.
        w2-take-home (- yearly-income
                        (employee-fica yearly-income)
                        yearly-insurance)
        ;; A contractor owes both halves, on the 92.35% base.
        contractor-take-home (- yearly-income
                                (self-employment-tax yearly-income)
                                yearly-insurance)]
    {:hourly-wage hourly-wage
     :weekly-income weekly-income
     :yearly-income yearly-income
     :if-w2 (equivalent-salary w2-take-home)
     :if-1099 (equivalent-salary contractor-take-home)}))

(def row-columns [:hourly-wage :weekly-income :yearly-income :if-w2 :if-1099])


;; react components

(defn row [row-input]
  (let [figures (row-figures row-input)]
    (sab/html
     [:tr
      (for [column row-columns]
        [:td {:key (name column)} (dollar-str (get figures column))])])))

(defn main-table [row-input]
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
     (row row-input)]]))

(defn input-row [{:keys [id val label update!]}]
  (sab/html
   [:div.input-row {:key id}
    [:label {:for id} label]
    ;; type="text", not type="number": React number inputs report
    ;; value === "" for intermediate states like "7." in several
    ;; browsers, and the empty string counts as 0, so a half-typed
    ;; decimal would silently zero the field. The display/data split
    ;; already does the validating.
    [:input {:id id
             :value val
             :type "text"
             :title val
             :on-change #(update! (.. % -target -value))}]]))

(defn input-section [display-vals]
  (sab/html
   [:div.input-section
    (for [{:keys [key label]} inputs]
      (input-row {:id (name key)
                  :val (get display-vals key)
                  :label label
                  :update! (partial update-app-state! key)}))]))

(defn header [show-instructions?]
  (sab/html
   [:div.header
    [:h1.main-title "Income conversion chart"]
    [:button.instructions-toggle {:type "button"
                                  :on-click toggle-show-instructions!}
     (str (if show-instructions? "hide" "show") " instructions")]
    [:div.instructions {:class (when show-instructions? "show")}
     [:h2.sub-hed "A glorified excel spreadsheet for comparing hourly gigs to salaried jobs with benefits"]
     [:h4.sub-hed "When you're looking at hourly gigs and you want to find out what the salaried equivalents are -- that is, the salary you'd have to make in order to have the same amount of money left over after paying for taxes and health insurance -- just type in the following:"]
     [:ol
      [:li "The hourly wage you're being offered"]
      [:li "The amount of hours you expect to work every week"]
      [:li "The total number of weeks you expect to take off each year"]
      [:li "The estimated health insurance subsidy: the difference between what you'd expect to pay for insurance on the open market, and what you'd expect to pay if you had an employer who was covering most of it"]]
     [:h4.sub-hed "The app will then take care of calculating Social Security and Medicare payroll taxes for those who are working as independent contractors and not W-2 employees."]
     [:h4.sub-hed "What the model covers: only the things that actually differ between the two scenarios. The weeks off you enter apply to both sides -- the question being answered is \"at the amount of time off I want, what salary matches this gig?\", so the salaried job is assumed to offer the same weeks, and if a real offer doesn't, that's a term to negotiate rather than something to model here. Income tax is left out on the same grounds: a contractor and a salaried employee both pay it, so it cancels out of the comparison."]
     [:h4.sub-hed [:em "Note: This is a work in progress. Among the many things not taken into account yet are Medicaid and Affordable Care Act subsidies below certain income thresholds, and overtime pay for hourly employees."]]]]))

(defn page [{:keys [show-instructions? data display]}]
  (sab/html
   [:div.page
    (header show-instructions?)
    (input-section display)
    [:div.table-section
     (main-table data)
     [:div.tax-year (str "Based on " tax-year " payroll tax rates.")]]]))


;; render

(defn show-fatal-error!
  "What onUncaughtError does instead of an error boundary. render
   redraws the whole tree from the root, so there is no surviving UI
   for a boundary to preserve -- React unmounts the root and leaves a
   blank white page. Say something in its place.

   This catches what React itself raises while rendering or committing;
   the sablono/findDOMNode crash was one. An error thrown while
   *building* the element tree happens in the add-watch callback,
   before .render is even called, so it never reaches React and the
   previous render stays on screen.

   Deferred a tick because React is still emptying the container when
   this fires."
  [error]
  (js/console.error "Uncaught render error:" error)
  (js/setTimeout
   (fn []
     (when-let [el (.getElementById js/document "app")]
       (set! (.-textContent el)
             "Something went wrong drawing this page. Reloading may help.")))
   0))

;; createRoot must be called exactly once per DOM node; a second call on a
;; hot reload makes React warn and drop the previous root. render is wired
;; to :after-load, so a reload re-renders through this one -- which also
;; means changes to these options need a page refresh in dev.
(defonce root
  (rdom/createRoot (.getElementById js/document "app")
                   #js {:onUncaughtError (fn [error _info]
                                           (show-fatal-error! error))}))

(defn render []
  (.render root (page @app-state)))

(render)

(add-watch app-state :rerender (fn [_ _ _ _] (render)))
