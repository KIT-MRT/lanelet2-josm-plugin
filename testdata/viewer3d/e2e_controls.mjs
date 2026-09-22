// End-to-end checks of the keyboard / pad controls: Space / C everywhere,
// "keys move the selection" (T), height-only walking, zoom target marker.
//   node testdata/viewer3d/e2e_controls.mjs [--shots DIR]
import { openViewer, Checks, dist2, dist3, sleep } from "./harness.mjs";

const shotsIdx = process.argv.indexOf("--shots");
const shotsDir = shotsIdx > 0 ? process.argv[shotsIdx + 1] : null;
const t = new Checks();
const Z = 100;

const features = [
  { id: "way/10", kind: "line", tags: { type: "line_thin" },
    pts: [0, 0, Z, 10, 0, Z, 20, 0, Z], nodes: [1001, 1002, 1003] },
  { id: "way/11", kind: "line", tags: { type: "line_thin" },
    pts: [0, 10, Z, 20, 10, Z], nodes: [1101, 1102] },
];

const v = await openViewer({ shotsDir });
const node = (id) => v.evaluate(`window.__ll2test.node(${id})`);
const nodes = (ids) => Promise.all(ids.map(node));
const moveCommands = () => v.commands.filter((c) => (c.ops || []).some((o) => o.op === "move_node"));
async function waitFor(pred, ms = 2000) {
  const t0 = Date.now();
  while (Date.now() - t0 < ms) {
    if (await pred()) return true;
    await sleep(40);
  }
  return false;
}

try {
  v.sendScene({ type: "snapshot", anchor: { lat: 49.0, lon: 8.4 }, features });
  t.check("scene streamed", await v.waitForFeatures(2));
  await v.key("b");

  // ---- Space / C move the camera in the normal (non-FPS) mode ---------------------------
  let c0 = await v.camera();
  await v.hold(" ", "Space", 300);
  let c1 = await v.camera();
  t.check("Space lifts the camera outside FPS mode", c1.pos[2] - c0.pos[2] > 0.5, `${(c1.pos[2] - c0.pos[2]).toFixed(2)} m`);
  await v.hold("c", "KeyC", 300);
  const c2 = await v.camera();
  t.check("C lowers it", c2.pos[2] < c1.pos[2] - 0.5);
  c0 = await v.camera();
  await v.hold("ArrowLeft", "ArrowLeft", 300, ["alt"]);
  c1 = await v.camera();
  t.check("alt+left turns the camera left", c1.yaw > c0.yaw + 0.05 && dist3(c1.pos, c0.pos) < 1e-9,
    `${((c1.yaw - c0.yaw) * 180 / Math.PI).toFixed(1)} deg`);
  await v.key("b");

  // ---- keys move the selection --------------------------------------------------------------
  await v.key("e");
  const p = await v.project([5, 0, Z]);
  await v.click(p[0], p[1]);
  await v.key("t");
  t.check("T makes the keys move the selection", (await v.evaluate("window.__ll2test.edit()")).keysMove === "selection");
  const ids = [1001, 1002, 1003];
  let before = await nodes(ids);
  c0 = await v.camera();
  v.commands.length = 0;
  await v.hold("d", "KeyD", 400);
  let after = await nodes(ids);
  c1 = await v.camera();
  const dx = after[0][0] - before[0][0];
  t.check("D moves the selection right (east in a north-up view), camera stays",
    dx > 0.05 && after.every((q, i) => Math.abs(q[0] - before[i][0] - dx) < 1e-9 && q[1] === before[i][1] && q[2] === before[i][2])
      && dist3(c0.pos, c1.pos) < 1e-9, `dx=${dx.toFixed(3)}`);
  t.check("the untouched way stays put", (await node(1101))[0] === 0);
  await waitFor(() => moveCommands().length > 0, 1500);
  t.check("one key hold is one command, level: x/y only",
    moveCommands().length === 1 && moveCommands()[0].ops.every((o) => o.x !== undefined && o.z === undefined),
    JSON.stringify(moveCommands().map((c) => c.ops.length)));

  before = await nodes(ids);
  v.commands.length = 0;
  await v.hold(" ", "Space", 300);
  after = await nodes(ids);
  t.check("Space lifts the selection", after.every((q, i) => q[2] > before[i][2] + 0.02 && q[0] === before[i][0]));
  await waitFor(() => moveCommands().length > 0, 1500);
  t.check("a vertical key move sends z only", moveCommands().length === 1
    && moveCommands()[0].ops.every((o) => o.z !== undefined && o.x === undefined));

  before = await nodes(ids);
  await v.hold("ArrowLeft", "ArrowLeft", 400, ["alt"]);
  after = await nodes(ids);
  const angle = (q) => Math.atan2(q[1] - after[1][1], q[0] - after[1][0]);
  const angle0 = (q) => Math.atan2(q[1] - before[1][1], q[0] - before[1][0]);
  const turned = angle(after[2]) - angle0(before[2]);
  t.check("alt+left turns the selection counter-clockwise about its centre",
    turned > 0.02 && dist2(after[1], before[1]) < 1e-6, `${(turned * 180 / Math.PI).toFixed(2)} deg`);

  // Pads follow the same target.
  before = await nodes(ids);
  const up = await v.evaluate(`(() => { const r = document.querySelector('#camElev [data-elev="1"]').getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; })()`);
  await v.click(up[0], up[1]);
  after = await nodes(ids);
  t.check("the height pad lifts the selection too", after.every((q, i) => q[2] > before[i][2]));

  // ---- height only: walking moves the camera, Space the selection -------------------------
  await v.key("h");
  before = await nodes(ids);
  c0 = await v.camera();
  await v.hold("w", "KeyW", 300);
  await v.hold(" ", "Space", 300);
  after = await nodes(ids);
  c1 = await v.camera();
  t.check("height only: W walks the camera", dist3(c0.pos, c1.pos) > 0.3);
  t.check("height only: Space still lifts the selection, x/y fixed",
    after.every((q, i) => q[2] > before[i][2] && q[0] === before[i][0] && q[1] === before[i][1]));
  await v.key("h");

  // ---- T back: keys move the camera again ------------------------------------------------
  await v.key("t");
  before = await nodes(ids);
  c0 = await v.camera();
  await v.hold("d", "KeyD", 300);
  t.check("T again: keys move the camera", dist3((await v.camera()).pos, c0.pos) > 0.3
    && (await nodes(ids)).every((q, i) => dist3(q, before[i]) < 1e-9));

  // ---- wheel shows where it zooms to --------------------------------------------------------
  await v.key("b");
  const w = await v.project([10, 10, Z]);
  await v.wheel(w[0], w[1], -100, 1);
  const marker = await v.evaluate("document.getElementById('zoomTarget').textContent");
  t.check("wheel marks its target in the HUD", marker.includes("(10.0, 10.0, 100.0)"), marker);
  await v.shot("zoom_marker.png");

  t.check("no page errors", v.errors.length === 0, v.errors.join(" | "));
} finally {
  await v.close();
}
console.log(t.failures ? `\n${t.failures} of ${t.count} FAILED` : `\nall ${t.count} passed`);
process.exit(t.failures ? 1 : 0);
