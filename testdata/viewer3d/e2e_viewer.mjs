// End-to-end checks of the 3D viewer in headless Chrome. See README.md.
//   node testdata/viewer3d/e2e_viewer.mjs [--shots DIR]
import { openViewer, Checks, dist2, dist3 } from "./harness.mjs";

const shotsIdx = process.argv.indexOf("--shots");
const shotsDir = shotsIdx > 0 ? process.argv[shotsIdx + 1] : null;
const DEG = 180 / Math.PI;
const t = new Checks();

// ---- scene: a 100 m lane, an elevated crossing line, two nodes 30 cm apart --
function line(id, nodeBase, pts) {
  return { id: `way/${id}`, kind: "line", tags: { type: "line_thin" }, points: pts,
    nodes: pts.map((_, i) => `node/${nodeBase + i}`) };
}
const ys = Array.from({ length: 11 }, (_, i) => i * 10);
const features = [
  line(1, 101, ys.map((y) => [-1.75, y, 0])),
  line(2, 201, ys.map((y) => [1.75, y, 0])),
  line(3, 301, [-20, -10, 0, 10, 20].map((x) => [x, 50, 6])),
  line(4, 401, [[5, 20, 0], [5.3, 20, 0]]),
];
const N1 = [5, 20, 0];
const N2 = [5.3, 20, 0];

const v = await openViewer({ shotsDir });
try {
  v.sendScene({ type: "snapshot", anchor: { lat: 49, lon: 8.4 }, features });
  t.check("scene streamed", await v.waitForFeatures(4));

  // ---- camera ---------------------------------------------------------------
  await v.key("b");
  let cam = await v.camera();
  t.check("B: straight down, north up", Math.abs(cam.yaw) < 1e-9 && Math.abs(cam.pitch * DEG + 90) < 1e-6,
    `yaw ${cam.yaw} pitch ${cam.pitch * DEG}`);
  t.check("HUD shows the new camera rows", (await v.hud("camAng")).startsWith("heading"));

  const P1 = [1.75, 35, 0];
  const s1 = await v.project(P1);
  await v.drag("left", s1[0], s1[1], 160, -90, { midShot: "orbit_mid.png" });
  let piv = await v.pivot();
  t.check("orbit: pivot is the picked line point", piv.kind === "line" && dist3(piv.point, P1) < 0.05,
    JSON.stringify(piv));
  let s1b = await v.project(piv.point);
  t.check("orbit: pivot stays on the press pixel", dist2(s1, s1b) < 0.5,
    `${s1.slice(0, 2).map((x) => x.toFixed(2))} -> ${s1b.slice(0, 2).map((x) => x.toFixed(2))}`);
  cam = await v.camera();
  t.check("orbit: view turned and tilted", Math.abs(cam.yaw) > 0.1 && cam.pitch * DEG > -80,
    `yaw ${(cam.yaw * DEG).toFixed(1)} pitch ${(cam.pitch * DEG).toFixed(1)}`);

  const P2 = [-20, 50, 6];
  const s2 = await v.project(P2);
  await v.drag("left", s2[0], s2[1], -80, 40);
  piv = await v.pivot();
  t.check("orbit #2: pivot on the elevated line", dist3(piv.point, P2) < 0.05, JSON.stringify(piv));
  t.check("orbit #2: pivot pixel fixed", dist2(s2, await v.project(piv.point)) < 0.5);

  let before = await v.camera();
  await v.drag("right", 640, 400, 200, 0);
  cam = await v.camera();
  t.check("look: position unchanged", dist3(cam.pos, before.pos) < 1e-9);
  t.check("look: drag right turns right by 200 px * 0.0025 rad",
    Math.abs((before.yaw - cam.yaw) - 0.5) < 1e-6, `${((before.yaw - cam.yaw) * DEG).toFixed(2)} deg`);

  for (let i = 0; i < 4 && (await v.camera()).pitch * DEG < 10; i++) await v.drag("right", 640, 600, 0, -400);
  cam = await v.camera();
  t.check("look up above the horizon", cam.pitch * DEG > 5, (cam.pitch * DEG).toFixed(1));
  before = cam;
  await v.drag("left", 640, 60, 100, 0);
  cam = await v.camera();
  piv = await v.pivot();
  t.check("left-drag on sky turns in place", dist3(cam.pos, before.pos) < 1e-9 && piv.kind === "none",
    JSON.stringify(piv));

  await v.key("b");
  const P3 = [-1.75, 45, 0];
  const s3 = await v.project(P3);
  await v.drag("middle", s3[0], s3[1], 120, -70);
  t.check("middle-drag pan keeps the grabbed point under the cursor",
    dist2(await v.project(P3), [s3[0] + 120, s3[1] - 70]) < 0.5);
  const s3b = await v.project(P3);
  await v.drag("left", s3b[0], s3b[1], -60, 30, { modifiers: ["shift"] });
  t.check("shift+left-drag pans", dist2(await v.project(P3), [s3b[0] - 60, s3b[1] + 30]) < 0.5);

  const sc = await v.project([0, 50, 0]);
  await v.drag("left", sc[0], sc[1], 0, -150);
  const V = [-1.75, 60, 0];
  const sv = await v.project(V);
  cam = await v.camera();
  const d0 = dist3(cam.pos, V);
  await v.wheel(sv[0], sv[1], -100, 3);
  cam = await v.camera();
  const d1 = dist3(cam.pos, V);
  t.check("wheel: moves toward the cursor point by 0.85^3", Math.abs(d1 / d0 - 0.85 ** 3) < 0.01,
    `${d0.toFixed(2)} -> ${d1.toFixed(2)} m`);
  t.check("wheel: point stays under the cursor", dist2(await v.project(V), sv) < 0.5);
  await v.wheel(sv[0], sv[1], 100, 3);
  cam = await v.camera();
  t.check("wheel out restores the distance", Math.abs(dist3(cam.pos, V) / d0 - 1) < 0.01);

  // ---- edit mode: picking and the gizmo -------------------------------------
  await v.key("b");
  const mid = [5.15, 20, 0];
  for (let i = 0; i < 15; i++) {
    const sm = await v.project(mid);
    if (dist3((await v.camera()).pos, mid) < 4) break;
    await v.wheel(sm[0], sm[1], -300, 1);
  }
  cam = await v.camera();
  t.check("zoomed close to the node pair", dist3(cam.pos, mid) < 6, `${dist3(cam.pos, mid).toFixed(2)} m`);
  await v.shot("close.png");
  await v.key("e");
  let p = await v.project(N2);
  await v.click(p[0], p[1]);
  t.check("click selects node/402", (await v.selected()) === "node/402", await v.selected());
  p = await v.project(N1);
  await v.click(p[0], p[1]);
  t.check("click on a dot under the gizmo arrow selects node/401", (await v.selected()) === "node/401",
    await v.selected());

  // Tilt so the gizmo is readable, re-select, then click on the X arrow, which
  // passes right over node/402 30 cm away: the selection must stay.
  p = await v.project(N1);
  await v.drag("left", p[0] + 200, p[1] + 150, 0, -120);
  piv = await v.pivot();
  t.check("orbit over empty ground uses a ground pivot", piv.kind === "ground", JSON.stringify(piv));
  p = await v.project(N1);
  await v.click(p[0], p[1]);
  t.check("re-select node/401", (await v.selected()) === "node/401");
  await v.shot("gizmo.png");
  {
    cam = await v.camera();
    const dN = dist3(cam.pos, N1);
    // Walk out along the X arrow (its picker reaches ~0.13 * distance) to a spot
    // 5-9 px past node/402's dot: the old 10 px node pick grabbed node/402 there.
    const dot402 = await v.project(N2);
    let onArrow = null;
    for (let dx = 0.3; dx < 0.13 * dN; dx += 0.002) {
      const q = await v.project([N1[0] + dx, N1[1], N1[2]]);
      const d = dist2(q, dot402);
      if (d > 5 && d < 9) { onArrow = q; break; }
    }
    t.check("found an arrow spot 5-9 px from node/402", onArrow !== null);
    if (onArrow) {
      await v.click(onArrow[0], onArrow[1]);
      t.check("click on a gizmo arrow near another node keeps the selection",
        (await v.selected()) === "node/401",
        `${await v.selected()} (click ${dist2(onArrow, dot402).toFixed(1)} px from node/402)`);
    }
  }

  before = await v.camera();
  v.commands.length = 0;
  p = await v.project(N1);
  await v.drag("left", p[0], p[1], 40, 0, { steps: 10 });
  await new Promise((r) => setTimeout(r, 300));
  cam = await v.camera();
  t.check("gizmo drag does not move the camera", dist3(cam.pos, before.pos) < 1e-9 && cam.yaw === before.yaw);
  const moves = v.commands.flatMap((c) => c.ops || []).filter((o) => o.op === "move_node");
  t.check("gizmo drag sends one move_node for node/401", moves.length === 1 && moves[0].id === "node/401",
    JSON.stringify(moves));
  await v.shot("after_gizmo.png");

  t.check("no page errors", v.errors.length === 0, v.errors.join(" | "));
} finally {
  await v.close();
}
console.log(t.failures ? `\n${t.failures} of ${t.count} FAILED` : `\nall ${t.count} passed`);
process.exit(t.failures ? 1 : 0);
