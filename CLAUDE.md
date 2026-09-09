# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A single-page ClojureScript app that converts hourly wages into equivalent full-time salaries, accounting for unpaid time off, health-insurance subsidy, and payroll taxes (W-2 vs. 1099). Circa-2016 Leiningen + Figwheel + Sablono stack, deployed as a static page on GitHub Pages.

## Commands

`lein` is **not installed on this machine** — install Leiningen before any of these will run.

```sh
lein figwheel                  # dev server + hot reload at http://localhost:3449/index.html
lein clean                     # wipe resources/public/js/compiled and target
lein do clean, cljsbuild once min   # production :advanced build
```

nREPL-based workflow (helpers live in `dev/user.clj`, loaded automatically by `lein repl`):

```clj
(fig-start)   ; start figwheel server + auto-compiler
(cljs-repl)   ; attach a CLJS REPL to the running build (piggieback)
(fig-stop)
```

There is no test suite, test runner, or lint config in this repo — don't invent commands for them.

**Both builds write to the same file.** The `dev` and `min` cljsbuild ids share `:output-to resources/public/js/compiled/income_converter.js`, so a `min` build clobbers the dev artifact. Run `lein clean` when switching back to figwheel.

## Architecture

All application code is in one file: `src/income_converter/core.cljs` (~200 lines). `resources/public/index.html` mounts it into `#app`; `resources/public/css/style.css` is watched by figwheel via `:css-dirs`.

**Rendering is hand-rolled — there is no Reagent or Om.** `app-state` is a plain atom; `add-watch` on it calls `render`, which does a full `js/ReactDOM.render` of the whole page from the root on every state change. "Components" (`page`, `header`, `main-table`, `row`, `input-section`, `input-row`) are ordinary functions that take data and return React elements built by Sablono's `sab/html`. None of them hold local state or have lifecycle methods, so new UI state must go into `app-state`.

**Split `:display` / `:data` state.** `update-app-state!` writes the raw string the user typed to `[:display key]` unconditionally, but only writes to `[:data key]` after coercion through `input->int`. The input controls render from `:display` (so a half-typed or empty box stays as typed); the table renders from `:data`. Keep this split when adding inputs — rendering the table off `:display` will feed strings into the arithmetic.

**localStorage persistence relies on CLJS map printing.** `update-app-state!` passes the `:data` map straight to `setItem`, depending on its `toString` producing readable EDN, and `stored-app-data` reads it back with `cljs.reader/read-string`. Values stored under `:data` must therefore stay EDN-round-trippable. `stored-app-data` discards anything that fails to read or isn't a non-empty map of usable numbers, falling back to `default-data`; a stale but *valid* entry still silently shadows edits to `default-data`, so call `clear-app-data` (commented-out `#_` call at `core.cljs:104`) when a default change appears not to take effect.

**`input->int` returns `nil`, not `NaN`, for unparseable input** (`core.cljs:12`). This is load-bearing: `NaN` is truthy under `cljs.core/truth_` (`x != null && x !== false`), so returning it would pass the `when-let` guard in `update-app-state!` and propagate through every arithmetic column. Any new coercion helper feeding that guard must return `nil` on failure for the same reason.

**Business logic is entirely in `row`** (`core.cljs:125`), one pure calculation per table line. The tax constants above it (`soc-sec-rate` 0.124, `medicare-rate` 0.029, `soc-sec-salary-cutoff` 184500) are the *combined* employer + employee rates, halved at the point of use to model the employer's share. They are tax-year 2026 values; the SSA re-sets the Social Security wage base annually, so the cutoff needs revisiting each January. `main-table` generates rows over `(range low-hourly-wage (inc high-hourly-wage) hourly-wage-step)`, driven by the two range sliders.

## Deployment

The `gh-pages` branch holds a flat copy of `resources/public` — `index.html`, `css/style.css`, and the `:advanced`-compiled `js/compiled/income_converter.js` (which `.gitignore` excludes on `master`). Because `index.html` references the JS by relative path, the same file works both locally and when published at https://richardharrington.github.io/income-converter.
