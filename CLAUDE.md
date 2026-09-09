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

Application code is one file: `src/income_converter/core.cljs` (~200 lines).

**Rendering is hand-rolled — there is no Reagent or Om.** `app-state` is a plain atom; `add-watch` on it calls `render`, which does a full `.render` on a `react-dom/client` root, redrawing the whole page from the root on every state change. "Components" (`page`, `header`, `main-table`, `row`, `input-section`, `input-row`) are ordinary functions that take data and return React elements built by Sablono's `sab/html`. None of them hold local state or have lifecycle methods, so new UI state must go into `app-state`.

**Sablono's controlled-input wrapper is disabled on purpose** (`core.cljs:27`). Sablono routes every `:input`, `:select` and `:textarea` carrying a `:value` through its own class component, which calls `ReactDOM.findDOMNode` — removed in React 19. The wrapper therefore mounts fine and throws on its first update, so a single keystroke unmounted the whole app. It only ever existed to work around an old IE bug (React #7027) that React itself handles now, and Sablono has had no release since 2019. The call site cannot opt out, so `core.cljs` overrides `sablono.interpreter/controlled-input?`. Don't remove that `set!` without running `npm test`, which types.

**The React root is a `defonce`.** `createRoot` must be called exactly once per DOM node; calling it again on a hot reload makes React warn and drop the previous root. `render` is wired to shadow-cljs's `:after-load`, so a reload re-renders through the existing root.

**Split `:display` / `:data` state.** `update-app-state!` writes the raw string the user typed to `[:display key]` unconditionally, but only writes to `[:data key]` after coercion through `input->int`. The input controls render from `:display` (so a half-typed or empty box stays as typed); the table renders from `:data`. Keep this split when adding inputs — rendering the table off `:display` will feed strings into the arithmetic.

**localStorage persistence relies on CLJS map printing.** `update-app-state!` passes the `:data` map straight to `setItem`, depending on its `toString` producing readable EDN, and `stored-app-data` reads it back with `cljs.reader/read-string`. Values stored under `:data` must therefore stay EDN-round-trippable. `stored-app-data` discards anything that fails to read or isn't a non-empty map of usable numbers, falling back to `default-data`; a stale but *valid* entry still silently shadows edits to `default-data`, so call `clear-app-data` (commented-out `#_` call at `core.cljs:127`) when a default change appears not to take effect.

**`input->int` returns `nil`, not `NaN`, for unparseable input** (`core.cljs:31`). This is load-bearing: `NaN` is truthy under `cljs.core/truth_` (`x != null && x !== false`), so returning it would pass the `when-let` guard in `update-app-state!` and propagate through every arithmetic column. Any new coercion helper feeding that guard must return `nil` on failure for the same reason.

**Business logic is entirely in `row`** (`core.cljs:148`), one pure calculation per table line. The tax constants above it (`soc-sec-rate` 0.124, `medicare-rate` 0.029, `soc-sec-salary-cutoff` 184500) are the *combined* employer + employee rates, halved at the point of use to model the employer's share. They are tax-year 2026 values; the SSA re-sets the Social Security wage base annually, so the cutoff needs revisiting each January. `main-table` generates rows over `(range low-hourly-wage (inc high-hourly-wage) hourly-wage-step)`, driven by the two range sliders.

## Build

**`public/index.html` is generated — never edit it.** `public/index.template.html` is the source of truth. The `write-index` hook in `src/build_hooks.clj` runs at shadow-cljs's `:flush` stage on every build, dev and release alike, reads the emitted module name out of `public/js/manifest.edn`, and substitutes it for `{{main-js}}`. That indirection exists because release builds set `:module-hash-names`, so the bundle is `main.<hash>.js` for cache busting; dev leaves it `main.js` so hot reload has a stable URL.

`public/js/` and `public/index.html` are both gitignored. No build output is committed.

**The smoke test needs an awaited tick, and it must type.** React 19 renders asynchronously, so `test/smoke.mjs` waits before asserting; asserting synchronously reports zero rows *and* zero errors, which passes. It also drives the inputs, because initial render is not evidence the app works — see the Sablono note below, a bug where the page rendered perfectly and died on the first keystroke. It tolerates one React development-build advisory (missing `:key` props on the seqs the component fns return) that the production build strips; anything else on the console fails the run.

Fonts are self-hosted in `public/fonts/` so the page makes no third-party requests; `scripts/fetch-fonts.py` regenerates them and prints matching `@font-face` rules.

## Deployment

`.github/workflows/deploy.yml` builds, smoke tests, and deploys `public/` to GitHub Pages on every push to `master`; pull requests get the same build and test without the deploy. The Pages source is set to "GitHub Actions", not a branch — there is no `gh-pages` branch to update by hand. `index.html` references everything by relative path, so the same output works locally and when published at https://richardharrington.github.io/income-converter.
