# income-converter

A tool to help you compare jobs that pay hourly with no benefits, to full-time salaried positions.

## Overview

If you're thinking about taking any kind of gig where you get paid hourly with no benefits -- whether you'll be paid as an employee or given a 1099 -- you can use this tool to help you figure out what kind salary you'd have to be taking home in order to end up with the same amount of money, after accounting for the paid time off, the health insurance subsidy, and the payroll taxes (both Social Security and Medicare) that the hypothetical full-time job would be providing you.

## How it works

The user input consists of three main state variables: the amount of hours you'll be working per week, the number of weeks you'll be taking off each year, and the health insurance subsidy -- the difference between what you'd expect to pay for insurance on the open market, and what you'd expect to pay if you had an employer who was covering most of it.

In addition to that, there are two more main things taken into account by the app in the background: Social Security tax (which is not collected after a certain salary cap is reached) and Medicare tax. Half of each of these is paid by your employer, whether you are an hourly employee or a salaried employee, but not if you are receiving a 1099 as a contractor.

All of this is put into a table, with sliders for the user to limit the hourly wage ranges.

Here is the heart of the code, containing the business logic (`dollar-str` formats a number as currency). It shows one column for what your full-time salary equivalent would be in the case where you're getting a W2 (in which case half of your payroll taxes are covered by your employer), and one in the case where you're getting a 1099:

```clojure
(def soc-sec-rate 0.123)
(def medicare-rate 0.030)
(def soc-sec-salary-cutoff 113700)

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

To get an interactive development environment run:

    lein figwheel

and open your browser at [localhost:3449](http://localhost:3449/).
This will auto compile and send all changes to the browser without the
need to reload. After the compilation process is complete, you will
get a Browser Connected REPL. An easy way to try it is:

    (js/alert "Am I connected?")

and you should see an alert in the browser window.

To clean all compiled files:

    lein clean

To create a production build run:

    lein do clean, cljsbuild once min

And open your browser in `resources/public/index.html`. You will not
get live reloading, nor a REPL.

## License

Copyright © 2016 Richard Harrington

Distributed under the Eclipse Public License either version 1.0 or (at your option) any later version.
