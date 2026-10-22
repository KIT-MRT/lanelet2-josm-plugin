// End-to-end checks of the interface around the 3D view: the side panel and
// its tabs, remembered sections, the command palette, the keyboard sheet,
// settings, the compass, menus and popovers, tooltips, the status-bar hints,
// a read-only map, and a narrow window.
//   node testdata/viewer3d/e2e_ui.mjs [--shots DIR]
import { openViewer, Checks, dist2, dist3, sleep } from "./harness.mjs";

const shotsIdx = process.argv.indexOf("--shots");
const shotsDir = shotsIdx > 0 ? process.argv[shotsIdx + 1] : null;
const t = new Checks();
const Z = 100;

const features = [
  { id: "way/10", kind: "line", tags: { type: "line_thin" },
    pts: [0, 0, Z, 40, 0, Z, 80, 0, Z], nodes: [1001, 1002, 1003] },
  { id: "way/11", kind: "line", tags: { type: "line_thin" },
    pts: [0, 30, Z, 80, 30, Z], nodes: [1101, 1102] },
];

const v = await openViewer({ shotsDir });
const ui = () => v.evaluate("window.__ll2ui.state()");
const hidden = (id) => v.evaluate(`(() => { const e = document.getElementById(${JSON.stringify(id)});`
  + " return !e || e.hidden || !!e.closest('[hidden]') || getComputedStyle(e).display === 'none'; })()");
const frames = (n = 2) => v.evaluate(`new Promise((r) => { let k = ${n}; const f = () => (--k <= 0 ? r() : requestAnimationFrame(f)); requestAnimationFrame(f); })`);
const box = (sel) => v.evaluate(`(() => { const r = document.querySelector(${JSON.stringify(sel)}).getBoundingClientRect();`
  + " return { x: r.left, y: r.top, w: r.width, h: r.height, cx: r.left + r.width / 2, cy: r.top + r.height / 2 }; })()");
async function clickEl(sel) {
  const b = await box(sel);
  await v.click(b.cx, b.cy);
  await sleep(120);
}
async function waitFor(pred, ms = 4000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await pred()) return true;
    await sleep(50);
  }
  return false;
}
async function reload() {
  await v.cdp("Page.reload", { ignoreCache: true });
  await sleep(300);
  await waitFor(() => v.evaluate("typeof window.__ll2test === 'object' && typeof window.__ll2ui === 'object'")
    .catch(() => false), 30000);
  await v.waitForFeatures(2);
  await frames(3);
}

try {
  v.sendScene({ type: "snapshot", anchor: { lat: 49.0, lon: 8.4 }, features });
  t.check("map streamed", await v.waitForFeatures(2));
  await v.key("b");
  await frames();

  // ---- layout ------------------------------------------------------------------------
  let s = await ui();
  t.check("a wide window starts with the side panel open, on Layers", s.panelOpen && s.tab === "layers", JSON.stringify(s));
  t.check("no scene tab without sensor data, no debug tab by default",
    !s.available.scene && !s.available.debug, JSON.stringify(s.available));
  let canvas = await box("canvas");
  const panel = await box("#sidePanel");
  t.check("the 3D view ends where the panel starts", Math.abs(canvas.x + canvas.w - panel.x) <= 1,
    `canvas right ${canvas.x + canvas.w}, panel left ${panel.x}`);
  const topbar = await box("#topbar");
  const status = await box("#statusbar");
  t.check("and sits between the top bar and the status bar",
    Math.abs(canvas.y - (topbar.y + topbar.h)) <= 1 && Math.abs(canvas.y + canvas.h - status.y) <= 1,
    JSON.stringify({ canvas, topbar, status }));
  t.check("no camera pad, no help wall, no HUD box any more",
    !(await v.evaluate("!!document.getElementById('camPad') || !!document.getElementById('help') || !!document.getElementById('hud')")));
  t.check("every icon placeholder got its icon",
    await v.evaluate("[...document.querySelectorAll('[data-icon]')].every((e) => e.querySelector('svg'))"));
  await v.shot("ui_layout.png");

  // ---- the panel: toggle, tabs, remembered ------------------------------------------------
  const widthOpen = canvas.w;
  // A projected point must land where it is drawn after the resize, i.e. the
  // camera's aspect followed the new canvas.
  await v.key("\\", { code: "Backslash" });
  await frames(3);
  s = await ui();
  canvas = await box("canvas");
  t.check("\\ folds the panel away and the view takes the room", !s.panelOpen && canvas.w > widthOpen + 200,
    `${widthOpen} -> ${canvas.w}`);
  const aspect = await v.evaluate("(() => { const c = document.querySelector('canvas'); return [c.width / c.height, window.__ll2test.camera().fov]; })()");
  const camAspect = await v.evaluate("(() => { const p = window.__ll2test.project(80, 0, 100); const q = window.__ll2test.project(0, 0, 100); return [p, q]; })()");
  t.check("the drawing buffer follows the new size", Math.abs(aspect[0] - canvas.w / canvas.h) < 0.02, JSON.stringify(aspect));
  t.check("and projected points stay on the canvas", camAspect.every((p) => p[0] >= canvas.x - 1 && p[0] <= canvas.x + canvas.w + 1),
    JSON.stringify(camAspect));
  await clickEl('#sidePanel .tabs [data-tab="layers"]');
  s = await ui();
  t.check("a tab opens the panel again", s.panelOpen && s.tab === "layers");
  await clickEl('#sidePanel .tabs [data-tab="layers"]');
  t.check("the active tab folds it, as in most editors", !(await ui()).panelOpen);
  await clickEl("#panelBtn");
  t.check("the top-bar button opens it too", (await ui()).panelOpen);

  await clickEl('.sect[data-sect="map"] > .sectHead');
  t.check("a section header collapses its section",
    await v.evaluate("document.querySelector('.sect[data-sect=\"map\"]').classList.contains('closed')"));
  await clickEl("#panelBtn");
  await reload();
  s = await ui();
  t.check("after a reload the panel is still folded away", !s.panelOpen, JSON.stringify(s));
  t.check("and the section still collapsed",
    await v.evaluate("document.querySelector('.sect[data-sect=\"map\"]').classList.contains('closed')"));
  await clickEl("#panelBtn");
  await clickEl('.sect[data-sect="map"] > .sectHead');
  t.check("the panel and section open again", (await ui()).panelOpen && !(await hidden("lanesBtn")));

  // ---- dragging the panel's edge -----------------------------------------------------------
  const w0 = (await box("canvas")).w;
  const edge = await box("#panelResize");
  await v.drag("left", edge.cx, edge.cy + 100, -120, 0, { steps: 8 });
  await frames(3);
  const w1 = (await box("canvas")).w;
  t.check("dragging the panel's edge widens the panel", Math.abs(w0 - w1 - 120) <= 2, `${w0} -> ${w1}`);
  await reload();
  t.check("and the width is remembered", Math.abs((await box("canvas")).w - w1) <= 2);
  const edge2 = await box("#panelResize");
  await v.cdp("Input.dispatchMouseEvent", { type: "mousePressed", x: edge2.cx, y: edge2.cy + 100, button: "left", buttons: 1, clickCount: 1 });
  await v.cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: edge2.cx, y: edge2.cy + 100, button: "left", buttons: 0, clickCount: 1 });
  await v.cdp("Input.dispatchMouseEvent", { type: "mousePressed", x: edge2.cx, y: edge2.cy + 100, button: "left", buttons: 1, clickCount: 2 });
  await v.cdp("Input.dispatchMouseEvent", { type: "mouseReleased", x: edge2.cx, y: edge2.cy + 100, button: "left", buttons: 0, clickCount: 2 });
  await frames(3);
  t.check("a double click puts the default width back", Math.abs((await box("canvas")).w - w0) <= 2);

  // ---- layer rows ---------------------------------------------------------------------------
  const lanesVisible = () => v.evaluate("window.__ll2test.lanelets().visible");
  const before = await lanesVisible();
  await clickEl("#lanesBtn");
  t.check("a layer row toggles its layer and its switch",
    (await lanesVisible()) === !before && (await v.evaluate("document.getElementById('lanesBtn').classList.contains('on')")) === !before);
  await clickEl("#lanesBtn");
  // A slider dragged with the mouse must not keep the keys from the map.
  const slider = await box("#iconAlpha");
  await v.drag("left", slider.x + slider.w - 6, slider.cy, -30, 0, { steps: 4 });
  const camA = await v.camera();
  await v.hold("w", "KeyW", 300);
  t.check("after dragging a slider, WASD still walks", dist3(camA.pos, (await v.camera()).pos) > 0.01);
  await v.evaluate("(() => { const e = document.getElementById('iconAlpha'); e.value = '100'; e.dispatchEvent(new Event('input')); })()");

  // ---- menus and popovers -------------------------------------------------------------------
  await clickEl("#viewMenuBtn");
  t.check("the Camera menu opens", !(await hidden("viewMenu")));
  const c = await box("canvas");
  await v.click(c.x + 200, c.y + 200);
  t.check("a click on the map closes it", await hidden("viewMenu"));
  await clickEl("#viewMenuBtn");
  await v.key("Escape");
  t.check("Esc closes it too", await hidden("viewMenu"));
  await clickEl("#viewMenuBtn");
  let cam0 = await v.camera();
  await v.drag("left", c.x + 300, c.y + 300, 120, 40); // orbit away first
  await clickEl("#viewMenuBtn");
  await clickEl("#bevBtn");
  let cam1 = await v.camera();
  t.check("a menu item runs and closes the menu",
    (await hidden("viewMenu")) && Math.abs(cam1.pitch + Math.PI / 2) < 0.05, JSON.stringify(cam1));
  await clickEl("#connBtn");
  const pop = await v.evaluate("document.getElementById('connPop').textContent");
  t.check("the connection popover says where the map comes from",
    !(await hidden("connPop")) && pop.includes("features from JOSM") && pop.includes("possible"), pop);
  await v.key("Escape");
  t.check("and closes with Esc", await hidden("connPop"));

  // ---- tooltips ------------------------------------------------------------------------------
  const help = await box("#helpBtn");
  await v.hover(help.cx, help.cy);
  await sleep(700);
  const tip = await v.evaluate("(() => { const e = document.getElementById('tooltip'); return e.hidden ? null : e.textContent; })()");
  t.check("hovering a button shows its tooltip with the shortcut", tip && tip.includes("Keyboard") && tip.includes("?"), tip);
  await v.hover(c.x + 100, c.y + 100);
  await sleep(100);
  t.check("and moving away hides it", await hidden("tooltip"));

  // ---- command palette --------------------------------------------------------------------------
  await v.key("k", { modifiers: ["ctrl"] });
  s = await ui();
  t.check("Ctrl+K opens the command palette", s.modal === "palette", JSON.stringify(s));
  t.check("with the search field focused", await v.evaluate("document.activeElement && document.activeElement.id === 'paletteInput'"));
  cam0 = await v.camera();
  await v.cdp("Input.insertText", { text: "lanelet" });
  await sleep(100);
  const first = await v.evaluate("document.querySelector('#paletteList .palItem.active .ttl').textContent");
  t.check("typing finds the command", first === "Lanelet surfaces and arrows", first);
  await v.hold("w", "KeyW", 200);
  cam1 = await v.camera();
  t.check("keys typed into the palette do not move the camera", dist3(cam0.pos, cam1.pos) < 1e-6);
  const lanesBefore = await lanesVisible();
  await v.key("Enter");
  t.check("Enter runs it and closes the palette",
    (await lanesVisible()) === !lanesBefore && (await ui()).modal === null);
  await v.key("l");
  t.check("(L puts the lanelets back)", (await lanesVisible()) === lanesBefore);
  await v.key("k", { modifiers: ["ctrl"] });
  await v.cdp("Input.insertText", { text: "move tool" });
  await sleep(100);
  const disabled = await v.evaluate("[...document.querySelectorAll('#paletteList .palItem')].find((e) => e.textContent.includes('Move tool'))?.className");
  t.check("commands that do not apply now are shown dimmed", disabled && disabled.includes("disabled"), disabled);
  await v.key("Enter");
  t.check("and Enter does not run them", (await ui()).modal === "palette" && !(await v.evaluate("window.__ll2test.edit().on")));
  await v.key("Escape");
  t.check("Esc closes the palette", (await ui()).modal === null);
  await clickEl("#paletteBtn");
  t.check("the Search button opens it too", (await ui()).modal === "palette");
  await v.key("Escape");

  // ---- keyboard sheet ----------------------------------------------------------------------------
  await v.key("?", { code: "Slash", modifiers: ["shift"] });
  s = await ui();
  const sheet = await v.evaluate("document.getElementById('helpBody').textContent");
  t.check("? opens the keyboard and mouse sheet", s.modal === "helpSheet", JSON.stringify(s));
  t.check("which lists the mouse and the commands' keys",
    sheet.includes("Orbit the point under the cursor") && sheet.includes("Frame the whole map")
    && sheet.includes("Follow the car"), sheet.slice(0, 200));
  await v.shot("ui_help.png");
  cam0 = await v.camera();
  await v.hold("w", "KeyW", 200);
  t.check("while it is open the map gets no keys", dist3(cam0.pos, (await v.camera()).pos) < 1e-6);
  await v.key("Escape");
  t.check("Esc closes it", (await ui()).modal === null);

  // ---- settings -------------------------------------------------------------------------------------
  await clickEl("#settingsBtn");
  t.check("the settings dialog opens", (await ui()).modal === "settingsDlg");
  await clickEl("#setDebug");
  s = await ui();
  t.check("ticking Debug offers the debug tab", s.available.debug && s.prefs.debug, JSON.stringify(s));
  await v.evaluate("(() => { const e = document.getElementById('setWalkSpeed'); e.value = '2'; e.dispatchEvent(new Event('input')); })()");
  t.check("the walk speed reads 4×", (await v.hud("setWalkSpeedLabel")) === "4×", await v.hud("setWalkSpeedLabel"));
  await v.key("Escape");
  s = await ui();
  t.check("Esc closes settings; the debug panel is there", s.modal === null && s.tab === "debug", JSON.stringify(s));
  t.check("and it holds the camera readouts", !(await hidden("camPos")) && (await v.hud("camPos")) !== "-");
  // Walk at 4x, then at 1x: the same hold goes clearly further at 4x.
  const walkFor = async () => {
    const a = await v.camera();
    await v.hold("w", "KeyW", 400);
    const b = await v.camera();
    return dist3(a.pos, b.pos);
  };
  const fast = await walkFor();
  await clickEl("#settingsBtn");
  await v.evaluate("(() => { const e = document.getElementById('setWalkSpeed'); e.value = '0'; e.dispatchEvent(new Event('input')); })()");
  await v.key("Escape");
  const slow = await walkFor();
  t.check("the walk speed setting scales WASD", fast > slow * 1.8, `${fast.toFixed(2)} m at 4x, ${slow.toFixed(2)} m at 1x`);
  await clickEl("#settingsBtn");
  await clickEl("#setReset");
  await v.key("Escape");
  s = await ui();
  t.check("Reset puts the interface back (no debug tab)", !s.available.debug && s.tab === "layers" && s.panelOpen,
    JSON.stringify(s));

  // ---- compass ---------------------------------------------------------------------------------------
  const cc = await box("canvas");
  await v.drag("right", cc.cx, cc.cy, 260, 0); // turn the view
  cam0 = await v.camera();
  t.check("(the view is turned away from north)", Math.abs(cam0.yaw) > 0.3, String(cam0.yaw));
  const roseTurned = await v.evaluate("document.getElementById('compassRose').style.transform");
  t.check("the compass rose turns with the view", /rotate\(-?[1-9]/.test(roseTurned), roseTurned);
  await clickEl("#compassBtn");
  cam1 = await v.camera();
  t.check("clicking the compass faces north", Math.abs(Math.atan2(Math.sin(cam1.yaw), Math.cos(cam1.yaw))) < 1e-3,
    String(cam1.yaw));
  await clickEl("#orthoBtn");
  t.check("2D switches to the flat map view", (await v.evaluate("window.__ll2map.ortho().on"))
    && (await v.evaluate("document.getElementById('orthoBtn').classList.contains('on')")));
  const hints2d = (await ui()).hints;
  t.check("and the status bar says what the mouse does there", hints2d.includes("pan") && hints2d.includes("turn"), hints2d);
  await v.key("v");

  // ---- status hints follow the mode ---------------------------------------------------------------------
  t.check("view mode hints", (await ui()).hints.includes("orbit"), (await ui()).hints);
  await clickEl("#editBtn");
  t.check("Edit in the top bar turns edit mode on", await v.evaluate("window.__ll2test.edit().on"));
  t.check("edit mode hints", (await ui()).hints.includes("select"), (await ui()).hints);
  t.check("the tool rail and the selection card show", !(await hidden("editBar")) && !(await hidden("selCard")));
  await v.shot("ui_edit.png");
  await clickEl("#viewModeBtn");
  t.check("View turns it off again", !(await v.evaluate("window.__ll2test.edit().on")) && (await hidden("editBar")));

  // ---- the Selection tab: every tag, from JOSM -----------------------------------------------------------
  const TAGS = {
    "way/10": { type: "line_thin", subtype: "dashed", file_origin: "/maps/a.osm", lane_change: "yes" },
    "way/11": { type: "line_thin", subtype: "solid", file_origin: "/maps/a.osm" },
  };
  const describes = () => v.ops("describe");
  v.reply = (cmd) => {
    const d = (cmd.ops || []).find((o) => o.op === "describe");
    if (!d) return { ok: true, message: "ok" };
    return { ok: true, message: "described", details: d.ids.map((id) => ({
      id, tags: TAGS[id] || {}, nodes: 3,
      memberships: id === "way/10" ? [{ id: "relation/7", role: "left", tags: { type: "lanelet", subtype: "road" } }] : [],
    })) };
  };
  const insp = () => v.evaluate("window.__ll2inspect.state()");
  await clickEl('#sidePanel .tabs [data-tab="inspect"]');
  t.check("the Selection tab says how to select something",
    (await v.evaluate("document.getElementById('inspectBody').textContent")).includes("Edit mode"));
  v.commands.length = 0;
  await v.key("e");
  let at = await v.reveal([20, 0, Z]);
  await v.click(at[0], at[1]);
  t.check("selecting a way asks JOSM to describe it",
    await waitFor(async () => (await insp()).source === "josm"), JSON.stringify(await insp()));
  t.check("once, for that way", describes().length === 1 && describes()[0].ids.join() === "way/10",
    JSON.stringify(describes()));
  let body = await v.evaluate("document.getElementById('inspectBody').textContent");
  t.check("every tag shows, not just type and subtype",
    body.includes("file_origin") && body.includes("/maps/a.osm") && body.includes("lane_change"), body);
  t.check("with the relation it belongs to and its role", body.includes("left") && body.includes("lanelet road")
    && body.includes("relation/7"), body);
  t.check("and the way's length from the stream", body.includes("80.0 m"), body);
  await v.shot("ui_inspect.png");
  at = await v.reveal([40, 30, Z]);
  await v.click(at[0], at[1], { modifiers: ["shift"] });
  t.check("adding a second way describes both",
    await waitFor(async () => (await insp()).tokens.length === 2 && !(await insp()).loading));
  body = await v.evaluate("document.getElementById('inspectBody').textContent");
  t.check("a tag they disagree on reads as two values", body.includes("2 values"), body);
  t.check("a tag only one has says so", body.includes("on 1 of 2"), body);
  t.check("the summary counts them", (await v.hud("inspectSummary")) === "2 ways", await v.hud("inspectSummary"));
  t.check("Copy takes the tags they share", (await insp()).text === "type=line_thin\nfile_origin=/maps/a.osm",
    JSON.stringify((await insp()).text));
  await clickEl('#inspectBody .inspectItem[data-token="way/11"]');
  t.check("clicking one of them selects only that one",
    await waitFor(async () => (await insp()).tokens.join() === "way/11"));
  // Not showing: nothing is asked.
  await clickEl('#sidePanel .tabs [data-tab="layers"]');
  const asked = describes().length;
  await v.key("Escape");
  at = await v.reveal([20, 0, Z]);
  await v.click(at[0], at[1]);
  await sleep(500);
  t.check("while the tab is not showing, JOSM is not asked", describes().length === asked);
  await clickEl('#sidePanel .tabs [data-tab="inspect"]');
  t.check("opening it asks for what is selected now",
    await waitFor(async () => (await insp()).tokens.join() === "way/10" && (await insp()).source === "josm"));
  // Without JOSM's answer (the scene server refuses edits and describes):
  v.reply = (cmd) => ({ ok: false, message: "The 3D scene server is read-only" });
  await clickEl("#inspectRefresh");
  t.check("without JOSM the streamed tags show, and why",
    await waitFor(async () => (await insp()).source === "stream"), JSON.stringify(await insp()));
  const st = await insp();
  t.check("type and subtype from the stream", st.details["way/10"].tags.type === "line_thin" && st.note.includes("scene server"),
    JSON.stringify(st));
  v.reply = () => ({ ok: true, message: "ok" });
  await v.key("Escape");
  await v.key("e");
  await clickEl('#sidePanel .tabs [data-tab="layers"]');

  // ---- a read-only map -------------------------------------------------------------------------------------
  v.sendScene({ type: "snapshot", source: "scene_server", anchor: { lat: 49.0, lon: 8.4 }, features });
  await waitFor(() => v.evaluate("document.getElementById('editBtn').classList.contains('blocked')"));
  t.check("a map from the scene server marks Edit as unavailable",
    await v.evaluate("document.getElementById('editBtn').classList.contains('blocked')"));
  await v.key("e");
  const toast = await v.evaluate("window.__ll2test.toast()");
  t.check("E does not enter edit mode, and says why",
    !(await v.evaluate("window.__ll2test.edit().on")) && toast && toast.text.includes("read-only"), JSON.stringify(toast));
  await clickEl("#connBtn");
  t.check("the connection popover says so too",
    (await v.evaluate("document.getElementById('connPop').textContent")).includes("scene server (view only)"));
  await v.key("Escape");
  v.sendScene({ type: "snapshot", anchor: { lat: 49.0, lon: 8.4 }, features });
  await waitFor(() => v.evaluate("!document.getElementById('editBtn').classList.contains('blocked')"));
  await v.key("e");
  t.check("once JOSM sends the map, editing works again", await v.evaluate("window.__ll2test.edit().on"));
  await v.key("e");

  // ---- a narrow window (the viewer next to JOSM) ---------------------------------------------------------------
  await v.cdp("Emulation.setDeviceMetricsOverride", { width: 700, height: 700, deviceScaleFactor: 1, mobile: false });
  await frames(4);
  await v.evaluate("window.__ll2ui.setPanelOpen(true)");
  await frames(3);
  canvas = await box("canvas");
  const tabs = await box("#sidePanel .tabs");
  t.check("in a narrow window the open panel floats over the map instead of squeezing it",
    Math.abs(canvas.x + canvas.w - tabs.x) <= 2 && canvas.w > 600, JSON.stringify({ canvas, tabs }));
  t.check("and the top bar still fits", await v.evaluate("document.getElementById('topbar').scrollWidth <= window.innerWidth + 1"));
  await v.shot("ui_narrow.png");
  await v.cdp("Emulation.clearDeviceMetricsOverride");
  await frames(3);

  t.check("no page errors", v.errors.length === 0, v.errors.join(" | "));
} catch (err) {
  t.check("no exception", false, String(err && err.stack ? err.stack : err));
} finally {
  await v.close();
}

console.log(`\n${t.failures ? `${t.failures} of ${t.count} FAILED` : `all ${t.count} passed`}`);
process.exit(t.failures ? 1 : 0);
