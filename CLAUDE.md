# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

A single-page ClojureScript app that converts hourly wages into equivalent full-time salaries, accounting for unpaid time off, health-insurance subsidy, and payroll taxes (W-2 vs. 1099). Built with shadow-cljs, rendered with React 19 + Sablono, deployed to GitHub Pages by CI.

## Commands

Requires Node (version pinned in `.nvmrc`) and a JDK 21. Run `npm install` first.

```sh
npm run dev       # shadow-cljs watch: hot reload + dev server at http://localhost:3449/
npm run release   # :advanced build with content-hashed filenames, into public/js/
npm test          # jsdom smoke test against whatever is currently in public/
npm run clean     # wipe build output
```

There is no unit test suite or lint config — `npm test` is the only check, and it
is an end-to-end smoke test, not a unit test runner. Don't invent other commands.

`npm run dev` also starts an nREPL server on port 8777; connect and call
`(shadow/repl :app)` for a CLJS REPL attached to the running build.

## Architecture

Application code is one file: `src/income_converter/core.cljs` (~340 lines).

**Rendering is hand-rolled — there is no Reagent or Om.** `app-state` is a plain atom; `add-watch` on it calls `render`, which does a full `.render` on a `react-dom/client` root, redrawing the whole page from the root on every state change. "Components" (`page`, `header`, `main-table`, `row`, `input-section`, `input-row`) are ordinary functions that take data and return React elements built by Sablono's `sab/html`. None of them hold local state or have lifecycle methods, so new UI state must go into `app-state`.

**Sablono's controlled-input wrapper is disabled on purpose** (`core.cljs:27`). Sablono routes every `:input`, `:select` and `:textarea` carrying a `:value` through its own class component, which calls `ReactDOM.findDOMNode` — removed in React 19. The wrapper therefore mounts fine and throws on its first update, so a single keystroke unmounted the whole app. It only ever existed to work around an old IE bug (React #7027) that React itself handles now, and Sablono has had no release since 2019. The call site cannot opt out, so `core.cljs` overrides `sablono.interpreter/controlled-input?`. Don't remove that `set!` without running `npm test`, which types.

**The React root is a `defonce`.** `createRoot` must be called exactly once per DOM node; calling it again on a hot reload makes React warn and drop the previous root. `render` is wired to shadow-cljs's `:after-load`, so a reload re-renders through the existing root.

**Split `:display` / `:data` state.** `update-app-state!` writes the raw string the user typed to `[:display key]` unconditionally, but only writes to `[:data key]` after coercion through `input->number`. The input controls render from `:display` (so a half-typed or empty box stays as typed); the table renders from `:data`. Keep this split when adding inputs — rendering the table off `:display` will feed strings into the arithmetic.

**localStorage persistence relies on CLJS map printing.** `update-app-state!` passes the `:data` map straight to `setItem`, depending on its `toString` producing readable EDN, and `stored-app-data` reads it back with `cljs.reader/read-string`. Values stored under `:data` must therefore stay EDN-round-trippable. `stored-app-data` discards anything that fails to read or isn't a non-empty map of usable numbers, falling back to `default-data`; a stale but *valid* entry still silently shadows edits to `default-data`, so call `clear-app-data` (commented-out `#_` call at `core.cljs:150`) when a default change appears not to take effect.

**`input->number` returns `nil`, never `NaN`, for unparseable input** (`core.cljs:37`). This is load-bearing: `NaN` is truthy under `cljs.core/truth_` (`x != null && x !== false`), so returning it would pass the `when-let` guard in `update-app-state!` and propagate through every arithmetic column. Any new coercion helper feeding that guard must return `nil` on failure for the same reason.

It accepts decimals, and it **validates the string with a regex before parsing** rather than leaning on `parseFloat`. `parseFloat("Infinity")` is `Infinity`, not `NaN`; `Infinity` is a `number?`, CLJS prints it as `##Inf`, and `cljs.reader` reads `##Inf` back — so it would persist to localStorage and survive a reload, the exact failure `stored-app-data` exists to prevent. `usable-number?` screens with `js/isFinite` for the same reason. The empty string still counts as 0.

**Business logic lives in `row-figures`** (`core.cljs:205`), a pure function of the input map returning the figures for one line; `row` only renders what it returns. Keep it that way — the release bundle is `:advanced`-compiled, so `smoke.mjs` cannot call it by name and tests it through the rendered dollar amounts instead. The extraction buys legibility and one obvious home for the arithmetic; the asserted figures are what make the math tested. `main-table` generates rows over `(range low-hourly-wage (inc high-hourly-wage) hourly-wage-step)`, driven by the two range sliders.

**The design principle behind what is and isn't modelled.** The app models what *differs* between the two sides of the comparison and holds constant everything that doesn't. Income tax is ignored because a contractor and a salaried employee both pay it. Weeks off is a single input because the question is "at the amount of time off I want, what salary matches this gig?" — the salaried job is assumed to offer the same weeks. That principle is stated in the instructions panel, and it is why two plausible-sounding additions were considered and rejected: self-employment tax deductibility (worth more than everything the app does compute, but only to someone paying income tax, which this app models none of — valuing it needs a marginal rate, which needs a filing status the app has no input for, and which would disprove the "income tax cancels" premise it rests on), and a second weeks-off input for the salaried side (time off is a controlled variable, not a missing one). The Additional Medicare Tax is employee-only with no employer match, so it cancels too.

**The equivalence is solved, not approximated** (`equivalent-salary`, `core.cljs:187`). The number being solved for is a *lower* salary, which carries less payroll tax of its own; subtracting from the hourly gross short-circuits that fixed point. This is not a bisection — `employee-fica` is piecewise linear in the salary, so there are two closed forms, and the below-the-cap one is used when its own answer is in fact below the cap. Both columns need it, not just the 1099 one: the W-2 error is `insurance / 0.9235 - insurance`, constant across the wage range and scaling with the insurance input.

The tax constants (`soc-sec-rate` 0.124, `medicare-rate` 0.029, `soc-sec-salary-cutoff` 184500) are the *combined* employer + employee rates, halved at the point of use to model the employer's share; `self-employment-tax` uses them whole, on the statutory 92.35% base, with the Social Security cap applied to that reduced base rather than to gross. They are tax-year 2026 values and the SSA re-sets the wage base every January. `tax-year` (`core.cljs:73`) is rendered under the table on purpose: the cutoff sat at its 2013 value for years unnoticed, nothing in the build will catch it going stale, and a build-time check fires exactly when nobody is looking at the number. Putting the year in front of the person *reading* it is the mechanism most likely to work.

## Build

**`public/index.html` is generated — never edit it.** `public/index.template.html` is the source of truth. The `write-index` hook in `src/build_hooks.clj` runs at shadow-cljs's `:flush` stage on every build, dev and release alike, reads the emitted module name out of `public/js/manifest.edn`, and substitutes it for `{{main-js}}`. That indirection exists because release builds set `:module-hash-names`, so the bundle is `main.<hash>.js` for cache busting; dev leaves it `main.js` so hot reload has a stable URL.

`public/js/` and `public/index.html` are both gitignored. No build output is committed.

**The smoke test needs an awaited tick, and it must type.** React 19 renders asynchronously, so `test/smoke.mjs` waits before asserting; asserting synchronously reports zero rows *and* zero errors, which passes. It also drives the inputs, because initial render is not evidence the app works — see the Sablono note below, a bug where the page rendered perfectly and died on the first keystroke. **The typing half is not optional and must not become secondary to the asserted figures**; it is the only thing that would have caught the one real bug this app has shipped.

It also asserts exact dollar amounts, including rows either side of all four Social Security cap boundaries (where the employee half of FICA caps, where each solved-for salary crosses the cap, and where the 92.35% self-employment base caps — $125 is below all four and $145 above all four at the default hours). It tolerates one React development-build advisory (missing `:key` props on the seqs the component fns return) that the production build strips; anything else on the console fails the run.

Fonts are self-hosted in `public/fonts/` so the page makes no third-party requests; `scripts/fetch-fonts.py` regenerates them and prints matching `@font-face` rules.

## Deployment

`.github/workflows/deploy.yml` builds, smoke tests, and deploys `public/` to GitHub Pages on every push to `master`; pull requests get the same build and test without the deploy. The Pages source is set to "GitHub Actions", not a branch — there is no `gh-pages` branch to update by hand. `index.html` references everything by relative path, so the same output works locally and when published at https://richardharrington.github.io/income-converter.
