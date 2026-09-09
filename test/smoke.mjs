// Smoke test: load the built page in jsdom, let React commit, and assert the
// app actually rendered. Runs identically locally and in CI via `npm test`,
// against whatever is in public/ (`npm run release` writes it).
//
// React 19 renders asynchronously, so every assertion runs after an awaited
// tick. Asserting synchronously after constructing the JSDOM reports zero rows
// and zero errors -- a false pass, not a failure.
//
// The bundle is located by parsing the generated index.html and reading that
// file off disk, so a build hook that writes a stale or wrong script name
// fails here rather than only in a browser.
//
// Console output must be clean apart from KNOWN_ADVISORIES below. A release
// build emits nothing at all, because React's production build strips these;
// a dev build (shadow-cljs compile/watch) emits them, and they are pre-existing
// issues the toolchain modernization deliberately did not touch.

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

// React development-build advisories, tolerated because they predate this
// build setup and are out of its scope:
//   - missing :key on the seqs `row`, `main-table` and `input-section` return
//   - componentWillReceiveProps, used inside sablono's own wrapped-input
const KNOWN_ADVISORIES = [
  /unique "key" prop/,
  /componentWillReceiveProps has been renamed/,
];

const consoleMessages = [];
const virtualConsole = new VirtualConsole();
virtualConsole.on("jsdomError", (e) => consoleMessages.push(String(e)));
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

dom.window.eval(bundle);
await new Promise((resolve) => dom.window.setTimeout(resolve, 100));

const { document } = dom.window;
const rows = document.querySelectorAll("table.main-table tbody tr");
const firstRow = rows[0]
  ? [...rows[0].querySelectorAll("td")].map((td) => td.textContent)
  : [];

const failures = [];
const check = (label, actual, ok) => {
  const passed = ok(actual);
  console.log(`${passed ? "ok  " : "FAIL"} ${label}: ${JSON.stringify(actual)}`);
  if (!passed) failures.push(label);
};

check("#app has children",
      document.getElementById("app")?.children.length ?? 0, (n) => n > 0);
check("table body rows", rows.length, (n) => n > 0);
check("inputs",
      document.querySelectorAll(".input-section input").length, (n) => n === 5);
check("first row is dollar amounts", firstRow, (cells) =>
      cells.length === 5 && cells.every((c) => /^\$[\d,]+$/.test(c)));
const advisories = consoleMessages.filter(
  (m) => KNOWN_ADVISORIES.some((re) => re.test(m)));
const unexpected = consoleMessages.filter((m) => !advisories.includes(m));

check("unexpected console output", unexpected, (m) => m.length === 0);
if (advisories.length) {
  console.log(`note  ${advisories.length} known React dev-build advisor` +
              `${advisories.length === 1 ? "y" : "ies"} ignored ` +
              `(absent from release builds)`);
}

dom.window.close();

if (failures.length) {
  die(`\n${failures.length} check(s) failed: ${failures.join(", ")}`);
}
console.log("\nsmoke test passed");
