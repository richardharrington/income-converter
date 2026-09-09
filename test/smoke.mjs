// Smoke test: load the built page in jsdom, let React commit, type into the
// inputs, and assert the app renders and keeps working. Runs identically
// locally and in CI via `npm test`, against whatever is in public/
// (`npm run release` writes it).
//
// Four things this gets right that a naive version does not.
//
// React 19 renders asynchronously, so every assertion runs after an awaited
// tick. Asserting synchronously reports zero rows and zero errors -- a pass,
// and a false one.
//
// It types. A render-only check passes against a build whose very first
// keystroke unmounts the whole app, which is exactly what React 19 removing
// ReactDOM.findDOMNode did to sablono's controlled-input wrapper. Initial
// render is not evidence that the app works. This half is not optional and
// must not become secondary to the figures below it -- it is the only thing
// that would have caught the one real bug this app has shipped.
//
// It asserts dollar figures, not just shapes. The bundle is :advanced-
// compiled, so the calculation cannot be called by name from here; the
// rendered numbers are what make the business logic tested.
//
// The bundle is found by parsing the generated index.html and reading that
// file off disk, rather than letting jsdom fetch it. That keeps the test
// offline and makes it fail if the build hook ever writes a script name that
// does not match what was emitted.

import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { JSDOM, VirtualConsole } from "jsdom";

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const publicDir = path.join(root, "public");
const indexPath = path.join(publicDir, "index.html");

const die = (msg) => {
  console.error(msg);
  process.exit(1);
};

const read = (file, what) => {
  try {
    return readFileSync(file, "utf8");
  } catch {
    die(`${what} not found at ${file} -- run \`npm run release\` first`);
  }
};

const html = read(indexPath, "build output");

const scriptSrc = html.match(/<script[^>]+src="([^"]+)"/)?.[1];
if (!scriptSrc) die(`no <script src> in ${indexPath}`);
const bundle = read(path.join(publicDir, scriptSrc), `bundle "${scriptSrc}"`);

const consoleMessages = [];
const virtualConsole = new VirtualConsole();
virtualConsole.on("jsdomError", (e) =>
  consoleMessages.push(`jsdomError: ${e.detail?.stack ?? e}`));
for (const level of ["error", "warn"]) {
  virtualConsole.on(level, (...args) =>
    consoleMessages.push(`${level}: ${args.join(" ")}`));
}

// A real origin (rather than file://) so the app's localStorage calls work.
// Scripts are run by hand below, from disk, instead of fetched over the network.
const dom = new JSDOM(html, {
  url: "https://richardharrington.github.io/income-converter/",
  runScripts: "outside-only",
  pretendToBeVisual: true,
  virtualConsole,
});

const { window } = dom;
const { document } = window;
const tick = () => new Promise((resolve) => window.setTimeout(resolve, 60));

window.eval(bundle);
await tick();

const inputs = () => document.querySelectorAll(".input-section input");
const rows = () =>
  [...document.querySelectorAll("table.main-table tbody tr")].map((tr) =>
    [...tr.querySelectorAll("td")].map((td) => td.textContent));

// how a user's keystroke reaches a React controlled input
const type = async (index, value) => {
  const el = inputs()[index];
  if (!el) die(`no input at index ${index} -- the app is not rendering`);
  Object.getOwnPropertyDescriptor(
    window.HTMLInputElement.prototype, "value").set.call(el, value);
  el.dispatchEvent(new window.Event("input", { bubbles: true }));
  await tick();
};

const failures = [];
const check = (label, actual, ok) => {
  const passed = ok(actual);
  console.log(`${passed ? "ok  " : "FAIL"} ${label}: ${JSON.stringify(actual)}`);
  if (!passed) failures.push(label);
};

// --- initial render -------------------------------------------------------

check("#app has children",
      document.getElementById("app")?.children.length ?? 0, (n) => n > 0);
check("table body rows", rows().length, (n) => n > 0);
check("inputs", inputs().length, (n) => n === 5);
check("first row is dollar amounts", rows()[0] ?? [], (cells) =>
      cells.length === 5 && cells.every((c) => /^\$[\d,]+$/.test(c)));

// --- the arithmetic -------------------------------------------------------

// The bundle is :advanced-compiled, so there is no way to call the
// calculation by name from here; asserting the rendered dollars is what
// makes the business logic tested. Figures derived by hand from the rates
// in core.cljs -- 30 hr/week, 4 weeks off, $200/month insurance.

const row = (n) => JSON.stringify(rows()[n] ?? []);
const is = (cells) => (r) => r === JSON.stringify(cells);

check("$30 row at defaults", row(0),
      is(["$30", "$900", "$43,200", "$40,601", "$37,570"]));
check("$45 row at defaults", row(3),
      is(["$45", "$1,350", "$64,800", "$62,201", "$57,655"]));

// --- interaction ----------------------------------------------------------

// hours per week 30 -> 35 recomputes the whole $30 row
await type(0, "35");
check("recalculates after typing", row(0),
      is(["$30", "$1,050", "$50,400", "$47,801", "$44,265"]));

// a fractional entry reaches the table rather than being truncated to 37
await type(0, "37.5");
check("decimal input is displayed", inputs()[0]?.value, (v) => v === "37.5");
check("decimal input reaches the table", row(0),
      is(["$30", "$1,125", "$54,000", "$51,401", "$47,612"]));

await type(0, "30");
check("back to the default hours", row(0),
      is(["$30", "$900", "$43,200", "$40,601", "$37,570"]));

// unparseable input shows in the box but must not reach the arithmetic
const beforeGarbage = row(0);
await type(2, "abc");
check("garbage input is displayed", inputs()[2]?.value, (v) => v === "abc");
check("garbage input does not reach the table", row(0),
      (r) => r === beforeGarbage);
await type(2, "200");

// --- the Social Security cap ----------------------------------------------

// Four separate boundaries sit between these two rows: where the employee
// half of FICA caps, where each solved-for salary crosses the cap, and
// where the 92.35% self-employment base caps. $125 is below all four and
// $145 above all four.

await type(4, "145");
await type(3, "125");
check("slider changes row count", rows().length, (n) => n === 5);
check("$125 row, below every cap", row(0),
      is(["$125", "$3,750", "$180,000", "$177,401", "$164,772"]));
check("$145 row, above every cap", row(4),
      is(["$145", "$4,350", "$208,800", "$206,365", "$192,155"]));

// --- crossed sliders ------------------------------------------------------

// A minimum above the maximum makes the wage range empty and the table
// silently vanish, so dragging one slider past the other pushes it along
// instead. Both directions.

await type(3, "150");
check("minimum pushes the maximum", inputs()[4]?.value, (v) => v === "150");
check("pushed range still has a row", rows().length, (n) => n === 1);

await type(4, "40");
check("maximum pushes the minimum", inputs()[3]?.value, (v) => v === "40");
check("pushed range still has a row", rows().length, (n) => n === 1);

check("app survived interaction", inputs().length, (n) => n === 5);
check("unexpected console output", consoleMessages, (m) => m.length === 0);

window.close();

if (failures.length) {
  die(`\n${failures.length} check(s) failed: ${failures.join(", ")}`);
}
console.log("\nsmoke test passed");
