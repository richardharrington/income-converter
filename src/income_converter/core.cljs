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

(def wage-slider-keys #{:low-hourly-wage :high-hourly-wage})


;; user-alterable state

(def default-data {:hours-per-week 30
                   :weeks-off 4
                   :health-ins-diff 200

                   :low-hourly-wage 30
                   :high-hourly-wage 45})

(defn usable-number?
  [x]
  (and (number? x) (js/isFinite x)))

(defn stored-app-data
  "Persisted data, or nil when there is nothing usable stored.
   Storage written before input->number guarded against NaN and
   Infinity can hold values that no longer read back as usable
   numbers, so anything unreadable or non-numeric is discarded in
   favor of the defaults."
  []
  (when-let [stored-edn (. js/localStorage (getItem "app-data"))]
    (let [parsed (try
                   (reader/read-string stored-edn)
                   (catch :default _ nil))]
      (when (and (map? parsed)
                 (seq parsed)
                 (every? usable-number? (vals parsed)))
        parsed))))

(defn uncross-wage-range
  "A minimum above the maximum makes the wage range empty and the
   table silently vanish. The sliders push each other apart on write,
   but stored-app-data checks only that the values are numbers, so a
   pair written before that existed -- or hand-edited -- still loads
   crossed, and no handler ever runs on it."
  [{:keys [low-hourly-wage high-hourly-wage] :as data}]
  (if (and (number? low-hourly-wage) (number? high-hourly-wage))
    (assoc data :high-hourly-wage (max low-hourly-wage high-hourly-wage))
    data))

(defn initial-state
  "Recover from localStorage or use default, but in either case
   use the 'data' values for both 'data' and 'display' (no need
   to persist messiness and user errors through a refresh)"
  []
  (let [app-data (uncross-wage-range (or (stored-app-data) default-data))]
    {:data app-data
     :display app-data}))

(defn clear-app-data
  "a way to clear app data, for dev only"
  []
  (. js/localStorage (removeItem "app-data")))

#_(clear-app-data)

(defonce app-state (atom (assoc (initial-state) :show-instructions? false)))

(defn push-other-slider
  "Dragging one wage slider past the other pushes the other along, so
   the crossed state is unreachable rather than merely recovered from.
   Both :data and :display move, or the pushed slider would keep
   rendering at its old position."
  [state key n]
  (let [other (if (= key :low-hourly-wage) :high-hourly-wage :low-hourly-wage)
        crossed? (if (= key :low-hourly-wage)
                   (> n (get-in state [:data other]))
                   (< n (get-in state [:data other])))]
    (if crossed?
      (-> state
          (assoc-in [:data other] n)
          (assoc-in [:display other] n))
      state)))

(defn update-app-state!
  "uses what the person actually typed to update the
   display-state, but does some validation and
   transformation of what it stores as data-state"
  [key val]
  (swap! app-state assoc-in [:display key] val)
  (when-let [n (input->number val)]
    (swap! app-state
           (fn [state]
             (cond-> (assoc-in state [:data key] n)
               (wage-slider-keys key) (push-other-slider key n))))
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
     [:tr {:key (:hourly-wage figures)}
      (for [column row-columns]
        [:td {:key (name column)} (dollar-str (get figures column))])])))

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

(defn input-row [{:keys [id val label type update!]}]
  (let [range? (= type "range")
        label-text (str label (when range? (str ": " val)))]
    (sab/html
     [:div.input-row {:key id}
      [:label {:for id} label-text]
      ;; min/max/step only where they mean something. The text boxes stay
      ;; type="text" on purpose: React number inputs report value === ""
      ;; for intermediate states like "7." in several browsers, and the
      ;; empty string counts as 0, so a half-typed decimal would silently
      ;; zero the field. The display/data split already does the
      ;; validating.
      [:input (cond-> {:id id
                       :value val
                       :type type
                       :title val
                       :on-change #(update! (.. % -target -value))}
                range? (assoc :min 0
                              :max max-hourly-wage
                              :step hourly-wage-step))]])))

(defn input-section [display-vals]
  (sab/html
   [:div.input-section
    (for [{:keys [key type label]} inputs]
      (input-row {:id (name key)
                  :val (get display-vals key)
                  :label label
                  :type type
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
