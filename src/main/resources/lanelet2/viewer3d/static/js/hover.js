// Hover in edit mode: marks what a left click at the pointer would select
// (the node, else the way; on a gizmo handle only a node dot), with the same
// rule as the click itself, so there is no guessing before clicking. Picks at
// most once a frame, and only when the pointer, the gizmo handle under it or
// the map changed. While the camera moves the mark is hidden (what is under
// the pointer changes every frame); it comes back once the camera rests. A
// slow pick (a whole city in view) spaces the next one out, so hovering never
// costs more than about a fifth of the frames.
import { now } from "./util.js";
import { canvas } from "./scene.js";
import { camera, view } from "./camera.js";
import { store } from "./store.js";
import { edit, transform, clickTarget } from "./edit.js";
import { setHover } from "./render/highlight.js";

const SLOW_PICK_MS = 2;
const pointer = { x: 0, y: 0, inside: false, buttons: 0 };
let mapVersion = 0;
let lastKey = "";
let lastCamKey = "";
let nextPickAt = 0;
let current = null;

const bump = () => { mapVersion++; };
for (const ev of ["applied", "nodes-moved", "clear"]) store.on(ev, bump);

canvas.addEventListener("pointermove", (e) => {
  pointer.x = e.clientX;
  pointer.y = e.clientY;
  pointer.buttons = e.buttons;
  pointer.inside = true;
});
canvas.addEventListener("pointerdown", (e) => { pointer.buttons = e.buttons; });
canvas.addEventListener("pointerup", (e) => { pointer.buttons = e.buttons; });
canvas.addEventListener("pointerleave", () => { pointer.inside = false; });

/** Per frame. `busy`: a drag, orbit or captured mouse owns the pointer. */
export function updateHover(busy) {
  if (!edit.on || !pointer.inside || pointer.buttons || busy) {
    lastKey = "";
    show(null);
    return;
  }
  const p = camera.position;
  const camKey = `${p.x},${p.y},${p.z},${view.yaw},${view.pitch}`;
  if (camKey !== lastCamKey) {
    lastCamKey = camKey;
    lastKey = "";
    show(null);
    return;
  }
  const key = `${pointer.x},${pointer.y},${mapVersion},${transform.axis}`;
  if (key === lastKey) return;
  const t0 = now();
  if (t0 < nextPickAt) return;
  lastKey = key;
  show(clickTarget(pointer.x, pointer.y, transform.axis !== null));
  const ms = now() - t0;
  nextPickAt = ms > SLOW_PICK_MS ? t0 + ms * 5 : 0;
}

function show(item) {
  current = item;
  setHover(item);
  const cursor = item ? "pointer" : "";
  if (canvas.style.cursor !== cursor) canvas.style.cursor = cursor;
}

/** The hovered item ({ type, id } or null); for tests. */
export const hoverItem = () => current;
