// Every `way[type=...]` rule in lanelet2/lines.mapcss must have a 3D line
// style (render/linestyles.js), so the two cannot drift apart silently.
// Plain Node, no browser:   node testdata/viewer3d/check_line_styles.mjs
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RES = path.resolve(HERE, "../../src/main/resources/lanelet2");
const { LINE_STYLES, lineStyleKey, lineColor, PATTERN, RAISE } =
  await import(pathToFileURL(path.join(RES, "viewer3d/static/js/render/linestyles.js")).href);

// Rules on tags the viewer is not streamed (they need `describe`), and the
// KITScenes split tracks (z13 debug overlays, not map lines): known, not gaps.
const NOT_STREAMED = new Set(["artificial", "source", "drivable_space_border", "split", "quality", "gt_leak",
  "released"]);

let failures = 0;
const fail = (msg) => { failures++; console.log(`FAIL  ${msg}`); };
const css = fs.readFileSync(path.join(RES, "lines.mapcss"), "utf8");
const selectors = [];
for (const m of css.matchAll(/^way\|z\d+-((?:\[[^\]]*\])+)(::\w+)?/gm)) {
  const conds = [...m[1].matchAll(/\[([^\]]*)\]/g)].map((c) => c[1]);
  selectors.push({ text: m[0], conds, layer: m[2] || null });
}
let checked = 0;
for (const sel of selectors) {
  const tags = {};
  let skip = false;
  for (const c of sel.conds) {
    const eq = c.match(/^([\w:-]+)=(.*)$/);
    if (eq) {
      if (NOT_STREAMED.has(eq[1])) skip = true;
      tags[eq[1]] = eq[2];
    } else if (/^!?[\w:-]+$/.test(c)) {
      // [color] or [!subtype]: presence tests.
      if (!c.startsWith("!")) tags[c] = c === "color" ? "yellow" : "yes";
    }
  }
  if (skip || sel.layer || !tags.type) continue;
  if (tags.type === "scenario_track") continue;
  checked++;
  const key = lineStyleKey(tags);
  if (key === "default") fail(`${sel.text} has no 3D style`);
}
// The table itself: every entry well formed.
for (const [key, s] of Object.entries(LINE_STYLES)) {
  if (!/^#[0-9a-f]{6}$/i.test(s.color)) fail(`${key}: colour ${s.color}`);
  if (!(s.width > 0)) fail(`${key}: width ${s.width}`);
  if (s.pattern && PATTERN[s.pattern] === undefined) fail(`${key}: pattern ${s.pattern}`);
  if ((s.pattern === "dashed" || s.pattern === "dotted" || s.pattern === "solid_dashed" || s.pattern === "dashed_solid")
      && !(s.dash && s.dash[0] > 0 && s.dash[1] > 0)) fail(`${key}: dashed without a dash length`);
  if (["double", "solid_dashed", "dashed_solid"].includes(s.pattern) && !(s.stripe > 0 && 2 * s.stripe < s.width)) {
    fail(`${key}: two stripes must fit with a gap`);
  }
  if (s.raise && (RAISE[s.raise.kind] === undefined || !(s.raise.top > s.raise.bottom))) fail(`${key}: raise`);
  if (!s.label) fail(`${key}: no label for the legend`);
}
// A few resolutions spelled out.
const expect = (tags, key) => {
  const got = lineStyleKey(tags);
  if (got !== key) fail(`${JSON.stringify(tags)} -> ${got}, expected ${key}`);
};
expect({ type: "line_thin" }, "line_thin");
expect({ type: "line_thin", subtype: "solid" }, "line_thin");
expect({ type: "line_thick", subtype: "solid_dashed" }, "line_thick/solid_dashed");
expect({ type: "curbstone", subtype: "high" }, "curbstone");
expect({ type: "curbstone", subtype: "low", sidewalk_entry: "true" }, "curbstone/low/entry");
expect({ type: "something_new" }, "default");
if (lineColor({ type: "line_thin", color: "yellow" }) === lineColor({ type: "line_thin" })) fail("color=yellow ignored");
if (lineColor({ type: "curbstone", color: "yellow" }) !== LINE_STYLES.curbstone.color) fail("color tag only recolours markings");

console.log(failures ? `\n${failures} FAILED (${checked} MapCSS way rules checked)`
  : `\nall passed: ${checked} MapCSS way rules have a 3D style, ${Object.keys(LINE_STYLES).length} styles well formed`);
process.exit(failures ? 1 : 0);
