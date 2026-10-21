// Drive the real scene server against a real recorded scene and report what
// the browser actually got. Not part of the hermetic suite (e2e_scene.mjs is):
// this one needs the dataset on disk and the tools/scene_server venv.
//
//   node testdata/viewer3d/live_scene.mjs <scene dir> [--frame N] [--shots DIR]
//
// It is the end-to-end answer to "do the map and the LiDAR line up?", so it
// prints the height of the cloud under the car next to the map's own `ele`.
import { spawn } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { openViewer, sleep } from "./harness.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER_DIR = path.resolve(HERE, "../../tools/scene_server");

const sceneDir = process.argv[2];
if (!sceneDir || sceneDir.startsWith("--")) {
  console.error("usage: live_scene.mjs <scene dir> [--frame N] [--shots DIR]");
  process.exit(2);
}
const arg = (name, fallback) => {
  const i = process.argv.indexOf(name);
  return i > 0 ? process.argv[i + 1] : fallback;
};
const frame = Number(arg("--frame", 110));
const shotsDir = arg("--shots", null);

const v = await openViewer({ shotsDir });
let scene = null;
try {
  scene = spawn(path.join(SERVER_DIR, ".venv/bin/python"),
    ["-m", "ll2_scene", "--scene", sceneDir,
     "--viewer-port", String(v.ports.http), "--ingest-port", String(v.ports.ingest),
     "--http-port", "0"],
    { cwd: SERVER_DIR, stdio: ["ignore", "pipe", "pipe"] });
  scene.stdout.on("data", (d) => process.stdout.write(`[scene] ${d}`));
  scene.stderr.on("data", (d) => process.stderr.write(`[scene] ${d}`));

  const ready = await (async () => {
    for (let i = 0; i < 120; i++) {
      const s = await v.evaluate("window.__ll2scene ? window.__ll2scene.state() : null");
      if (s && s.frames) return s;
      await sleep(500);
    }
    return null;
  })();
  if (!ready) throw new Error("the scene server never announced a scene");
  console.log(`scene: ${ready.frames} frames, data from ${ready.base}`);
  console.log(`map features: ${await v.evaluate("window.__ll2test.stats().features")}`);

  await v.evaluate(`window.__ll2scene.show(${frame})`);
  for (let i = 0; i < 120 && (await v.evaluate("window.__ll2scene.state()")).loading; i++) await sleep(500);
  const pts = await v.evaluate("window.__ll2scene.points()");
  console.log(`frame ${frame}: ${pts.count} points, origin ${pts.origin.map((n) => n.toFixed(1))}`);

  // Where the cloud sits under the car, against the map nodes around it.
  const car = await v.evaluate(`window.__ll2scene.ego(${frame})`);
  const compare = await v.evaluate(`(() => {
    const car = ${JSON.stringify(car)};
    const g = window.__ll2sceneDebug.cloudZNear(car, 12);
    const m = window.__ll2sceneDebug.mapZNear(car, 12);
    return { ground: g, map: m };
  })()`);
  console.log(`cloud ground under the car: ${compare.ground.p10?.toFixed(2)} m `
    + `(${compare.ground.count} pts)   map ele there: ${compare.map.median?.toFixed(2)} m `
    + `(${compare.map.count} nodes)`);
  if (compare.ground.p10 != null && compare.map.median != null) {
    console.log(`=> map vs cloud: ${((compare.ground.p10 - compare.map.median) * 100).toFixed(1)} cm`);
  }

  await v.key("k");
  await sleep(600);
  await v.shot("live_car.png");

  // Camera lock + overlay: the alignment check this whole thing is for.
  await v.evaluate("document.getElementById('sceneLock').click()");
  await sleep(2500); // let the recorded JPEG arrive
  const rig = await v.evaluate("window.__ll2scene.rig()");
  console.log(`lock: ${rig.camera}, viewport ${rig.viewport.width}x${rig.viewport.height}, `
    + `image ${rig.imageSrc ? "loaded" : "none"}`);
  await v.shot("live_overlay.png");
  await v.evaluate("(() => { const el = document.getElementById('sceneOpacity');"
    + " el.value = '100'; el.dispatchEvent(new Event('input')); })()");
  await sleep(600);
  await v.shot("live_overlay_full.png");
  await v.evaluate("(() => { const el = document.getElementById('sceneOpacity');"
    + " el.value = '55'; el.dispatchEvent(new Event('input')); })()");
  // Map over the photo with the cloud out of the way: this is the view that
  // says whether the *map* is right, rather than whether the poses are.
  await v.key("p");
  await sleep(500);
  await v.shot("live_overlay_map.png");
  await v.key("p");
  await v.key("o");
  await sleep(400);
  await v.shot("live_overlay_off.png");
  await v.key("L", { modifiers: ["shift"] });
  await sleep(300);
  await v.key("b");
  await sleep(600);
  await v.shot("live_bev.png");
  console.log(`page errors: ${v.errors.length ? v.errors.join(" | ") : "none"}`);
} finally {
  if (scene) scene.kill("SIGINT");
  await v.close();
}
