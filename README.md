# income-converter

A tool to help you compare jobs that pay hourly with no benefits, to full-time salaried positions.

Live at [richardharrington.github.io/income-converter](https://richardharrington.github.io/income-converter/).

## Overview

If you're thinking about taking any kind of gig where you get paid hourly with no benefits -- whether you'll be paid as an employee or given a 1099 -- you can use this tool to help you figure out what kind salary you'd have to be taking home in order to end up with the same amount of money, after accounting for the paid time off, the health insurance subsidy, and the payroll taxes (both Social Security and Medicare) that the hypothetical full-time job would be providing you.

## How it works

The user input consists of four state variables: the hourly wage you're being offered, the amount of hours you'll be working per week, the number of weeks you'll be taking off each year, and the health insurance subsidy -- the difference between what you'd expect to pay for insurance on the open market, and what you'd expect to pay if you had an employer who was covering most of it.

In addition to that, there are two more main things taken into account by the app in the background: Social Security tax (which is not collected after a certain salary cap is reached) and Medicare tax. Half of each of these is paid by your employer, whether you are an hourly employee or a salaried employee, but not if you are receiving a 1099 as a contractor.

The app models only what *differs* between the two scenarios and holds everything else constant. Income tax is left out because a contractor and a salaried employee both pay it, and the weeks off you enter apply to both sides -- the question being answered is "at the amount of time off I want, what salary matches this gig?"

All of this is put into a single-row table.

Here is the heart of the code. Note that both salary columns are *solved* rather
than approximated: the number being looked for is a lower salary, and that lower
salary carries less payroll tax of its own. That is a fixed point, but it does
not need a bisection -- the equation is piecewise linear, so there are two closed
forms and you pick whichever satisfies its own condition:

```clojure
;; Combined employer + employee payroll tax rates, halved at the point of
;; use to model the employer's share. Tax year 2026; the SSA resets the
;; Social Security wage base every January.
(def tax-year 2026)
(def soc-sec-rate 0.124)
(def medicare-rate 0.029)
(def soc-sec-salary-cutoff 184500)

;; Self-employment tax is levied on 92.35% of net self-employment earnings,
;; not on all of them. That statutory factor mirrors the fact that an
;; employer's half of FICA is not part of an employee's wages.
(def se-tax-base-factor 0.9235)

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
```

## Setup

ClojureScript built with [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html),
rendered with React 19 and [sablono](https://github.com/r0man/sablono). You need
Node (the version in `.nvmrc`) and a JDK 21.

    npm install

For an interactive development environment with hot reload:

    npm run dev

and open [localhost:3449](http://localhost:3449/). Both the ClojureScript and
`public/css/style.css` reload on save.

For a production build:

    npm run release

That writes an `:advanced`-optimized, content-hashed bundle to `public/js/`. Open
`public/index.html`, or serve `public/` -- everything is referenced by relative
path, so it works from any location.

To check the built page actually renders:

    npm test

This loads the build output in jsdom and asserts the table came out right. React
19 renders asynchronously, so the test waits a tick before asserting; without
that wait it reports zero rows and passes anyway.

To clear the build output:

    npm run clean

### REPL

`npm run dev` starts an nREPL server on port 8777 and writes
`.shadow-cljs/nrepl.port`. Connect your editor to it, then:

```clj
(shadow/repl :app)
```

to get a ClojureScript REPL attached to the running build and browser.

## Layout

```
public/
  index.template.html   source of truth for the page
  index.html            GENERATED on every build -- do not edit
  css/style.css
  fonts/                self-hosted Arimo and Cutive Mono
  js/                   build output (gitignored)
src/income_converter/core.cljs   the whole app
src/build_hooks.clj              generates index.html from the template
scripts/fetch-fonts.py           refreshes public/fonts
test/smoke.mjs
```

`index.html` is generated because release bundles get content-hashed file names
for cache busting, so the script tag has to be written to match whatever the
build emitted.

## Deployment

Pushing to `master` runs `.github/workflows/deploy.yml`, which builds, smoke
tests, and publishes `public/` to GitHub Pages. Pull requests run the same build
and test without deploying. Nothing is built by hand or committed as build
output.

## License

Copyright © 2016 Richard Harrington

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
