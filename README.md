# income-converter

A tool to help you compare jobs that pay hourly with no benefits, to full-time salaried positions.

Live at [richardharrington.github.io/income-converter](https://richardharrington.github.io/income-converter/).

## Overview

If you're thinking about taking any kind of gig where you get paid hourly with no benefits -- whether you'll be paid as an employee or given a 1099 -- you can use this tool to help you figure out what kind salary you'd have to be taking home in order to end up with the same amount of money, after accounting for the paid time off, the health insurance subsidy, and the payroll taxes (both Social Security and Medicare) that the hypothetical full-time job would be providing you.

## How it works

The user input consists of three main state variables: the amount of hours you'll be working per week, the number of weeks you'll be taking off each year, and the health insurance subsidy -- the difference between what you'd expect to pay for insurance on the open market, and what you'd expect to pay if you had an employer who was covering most of it.

In addition to that, there are two more main things taken into account by the app in the background: Social Security tax (which is not collected after a certain salary cap is reached) and Medicare tax. Half of each of these is paid by your employer, whether you are an hourly employee or a salaried employee, but not if you are receiving a 1099 as a contractor.

All of this is put into a table, with sliders for the user to limit the hourly wage ranges.

Here is the heart of the code, containing the business logic (`dollar-str` formats a number as currency). It shows one column for what your full-time salary equivalent would be in the case where you're getting a W2 (in which case half of your payroll taxes are covered by your employer), and one in the case where you're getting a 1099:

```clojure
;; Combined employer + employee rates, halved at the point of use to model
;; the employer's share. Tax year 2026; the SSA resets the Social Security
;; wage base every January, so the cutoff needs revisiting annually.
(def soc-sec-rate 0.124)
(def medicare-rate 0.029)
(def soc-sec-salary-cutoff 184500)

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
