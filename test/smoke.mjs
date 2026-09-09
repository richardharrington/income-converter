// Smoke test: load the built page in jsdom, let React commit, type into the
// inputs, and assert the app renders and keeps working. Runs identically
// locally and in CI via `npm test`, against whatever is in public/
// (`npm run release` writes it).
//
// Three things this gets right that a naive version does not.
//
// React 19 renders asynchronously, so every assertion runs after an awaited
// tick. Asserting synchronously reports zero rows and zero errors -- a pass,
// and a false one.
//
// It types. A render-only check passes against a build whose very first
// keystroke unmounts the whole app, which is exactly what React 19 removing
// ReactDOM.findDOMNode did to sablono's controlled-input wrapper. Initial
// render is not evidence that the app works.
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

// React development builds warn about missing :key on the seqs the component
// fns return. Pre-existing and out of scope; the production build strips it.
const KNOWN_ADVISORIES = [/unique "key" prop/];

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

// --- interaction ----------------------------------------------------------

// hours per week 30 -> 35 recomputes weekly income for the $30 row
await type(0, "35");
check("recalculates after typing", rows()[0]?.[1], (v) => v === "$1,050");

// unparseable input shows in the box but must not reach the arithmetic
const beforeGarbage = rows()[0];
await type(2, "abc");
check("garbage input is displayed", inputs()[2]?.value, (v) => v === "abc");
check("garbage input does not reach the table", rows()[0],
      (r) => JSON.stringify(r) === JSON.stringify(beforeGarbage));

// the max-wage slider changes how many rows there are
await type(4, "60");
check("slider changes row count", rows().length, (n) => n === 7);

check("app survived interaction", inputs().length, (n) => n === 5);
check("unexpected console output",
      consoleMessages.filter(
        (m) => !KNOWN_ADVISORIES.some((re) => re.test(m))),
      (m) => m.length === 0);

const advisories = consoleMessages.filter(
  (m) => KNOWN_ADVISORIES.some((re) => re.test(m)));
if (advisories.length) {
  console.log(`note  ${advisories.length} known React dev-build advisor` +
              `${advisories.length === 1 ? "y" : "ies"} ignored ` +
              `(absent from release builds)`);
}

window.close();

if (failures.length) {
  die(`\n${failures.length} check(s) failed: ${failures.join(", ")}`);
}
console.log("\nsmoke test passed");
