// Keyboard and on-screen camera controls, plus FPS (captured-mouse) look.
//
// Hold-to-move keys are only recorded here; applyHeldCamera() moves the camera
// in the render loop, so mouse look under pointer lock cannot starve WASD of
// key-repeat events.
import { canvas } from "./scene.js";
import { walk, elevate, look } from "./camera.js";
import { LOOK_RAD_PER_PX, endNav } from "./nav.js";
import { edit, setEditMode, editEvents, handleEditKey } from "./edit.js";
import { frameAll, bevNorth, defaultBevView } from "./framing.js";
import { requestJosmRecenter } from "./josmview.js";
import { setLookHud } from "./hud.js";

const CAM_PAN_STEP_M = 1;    // metres per pan / height tick (walk)
const CAM_FAST_STEP_M = 10;  // metres per tick with Shift (WASD / arrows / pad)
const CAM_ROT_STEP = 0.05;   // radians per step (~3°)
const CAM_HOLD_MS = 50;

const moveKeys = new Set();
let altHeld = false;
let ctrlHeld = false;
let shiftHeld = false;
let fpsLookEnabled = false;

const moveStepM = () => (shiftHeld ? CAM_FAST_STEP_M : CAM_PAN_STEP_M);

export const isPointerLocked = () => document.pointerLockElement === canvas;

/** Per frame: apply held movement keys, scaled to frame time. */
export function applyHeldCamera(dt) {
  if (!moveKeys.size || dt <= 0) return;
  let dx = 0, dy = 0, dz = 0;
  if (moveKeys.has("KeyW")) dy += 1;
  if (moveKeys.has("KeyS")) dy -= 1;
  if (moveKeys.has("KeyA")) dx -= 1;
  if (moveKeys.has("KeyD")) dx += 1;
  if (moveKeys.has("ArrowUp")) {
    if (altHeld) dz += 1;
    else if (!ctrlHeld) dy += 1;
  }
  if (moveKeys.has("ArrowDown")) {
    if (altHeld) dz -= 1;
    else if (!ctrlHeld) dy -= 1;
  }
  if (moveKeys.has("ArrowLeft") && !ctrlHeld && !altHeld) dx -= 1;
  if (moveKeys.has("ArrowRight") && !ctrlHeld && !altHeld) dx += 1;
  if (fpsLookEnabled) {
    if (moveKeys.has("Space")) dz += 1;
    if (moveKeys.has("KeyC")) dz -= 1;
  }
  const dist = moveStepM() * (dt / (CAM_HOLD_MS / 1000));
  if (dx || dy) {
    const len = Math.hypot(dx, dy) || 1;
    walk(dx / len, dy / len, dist);
  }
  if (dz) elevate(dz > 0 ? 1 : -1, dist);
}

function refreshLookHud() {
  if (isPointerLocked()) setLookHud("FPS (mouse captured)");
  else if (fpsLookEnabled) setLookHud("FPS (click view)");
  else setLookHud("orbit at cursor");
}

function setFpsLook(on) {
  fpsLookEnabled = !!on;
  const btn = document.getElementById("fpsBtn");
  if (btn) btn.classList.toggle("on", fpsLookEnabled);
  if (!fpsLookEnabled && isPointerLocked()) document.exitPointerLock();
  if (fpsLookEnabled && edit.on) setEditMode(false);
  refreshLookHud();
}

function requestFpsLock() {
  if (!fpsLookEnabled || edit.on || isPointerLocked()) return;
  canvas.requestPointerLock();
}

function bindHoldButton(btn, fn) {
  let timer = null;
  const stop = () => {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  };
  btn.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    btn.setPointerCapture(e.pointerId);
    fn();
    timer = setInterval(fn, CAM_HOLD_MS);
  });
  btn.addEventListener("pointerup", stop);
  btn.addEventListener("pointercancel", stop);
  btn.addEventListener("lostpointercapture", stop);
}

function parseVecAttr(el, name) {
  const parts = (el.getAttribute(name) || "0,0").split(",");
  return [Number(parts[0]) || 0, Number(parts[1]) || 0];
}

function isTypingTarget(el) {
  if (!el || el === document.body || el === document.documentElement) return false;
  const tag = el.tagName;
  return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || el.isContentEditable;
}

export function installKeys() {
  document.querySelectorAll("#camPan [data-pan]").forEach((btn) => {
    const [dx, dy] = parseVecAttr(btn, "data-pan");
    bindHoldButton(btn, () => walk(dx, dy, moveStepM()));
  });
  document.querySelectorAll("#camRot [data-rot]").forEach((btn) => {
    const [dYaw, dPitch] = parseVecAttr(btn, "data-rot");
    bindHoldButton(btn, () => look(dYaw * CAM_ROT_STEP, dPitch * CAM_ROT_STEP));
  });
  document.querySelectorAll("#camElev [data-elev]").forEach((btn) => {
    const dz = Number(btn.getAttribute("data-elev")) || 0;
    bindHoldButton(btn, () => elevate(dz, moveStepM()));
  });

  editEvents.on("mode", (on) => { if (on && fpsLookEnabled) setFpsLook(false); });

  document.addEventListener("pointerlockchange", () => {
    if (isPointerLocked()) endNav(); // captured mouse look replaces any drag
    refreshLookHud();
  });
  canvas.addEventListener("mousemove", (e) => {
    if (!isPointerLocked()) return;
    look(e.movementX * LOOK_RAD_PER_PX, -e.movementY * LOOK_RAD_PER_PX);
  });
  canvas.addEventListener("click", () => {
    if (fpsLookEnabled && !edit.on && !isPointerLocked()) requestFpsLock();
  });

  const on = (id, fn) => {
    const b = document.getElementById(id);
    if (b) b.addEventListener("click", fn);
  };
  on("fpsBtn", () => {
    const next = !fpsLookEnabled;
    setFpsLook(next);
    if (next) requestFpsLock();
  });
  on("recenterJosmBtn", () => requestJosmRecenter("button", true));
  on("bevBtn", () => bevNorth());
  on("defaultBevBtn", () => defaultBevView());
  refreshLookHud();

  document.addEventListener("keydown", (e) => {
    if (e.code === "AltLeft" || e.code === "AltRight") altHeld = true;
    shiftHeld = e.shiftKey;
    if (e.code === "ControlLeft" || e.code === "ControlRight"
        || e.code === "MetaLeft" || e.code === "MetaRight") ctrlHeld = true;
    if (isTypingTarget(e.target)) return;
    if (handleEditKey(e)) return;

    if ((e.key === "f" || e.key === "F") && !e.repeat) {
      frameAll();
      return;
    }
    if ((e.key === "b" || e.key === "B") && !e.repeat) {
      bevNorth();
      return;
    }
    if ((e.key === "e" || e.key === "E") && !e.repeat && !e.ctrlKey && !e.metaKey) {
      setEditMode(!edit.on);
      return;
    }
    if ((e.code === "KeyW" || e.code === "KeyA" || e.code === "KeyS" || e.code === "KeyD")
        && !e.ctrlKey && !e.metaKey && !e.altKey) {
      e.preventDefault();
      moveKeys.add(e.code);
      return;
    }
    if (fpsLookEnabled && (e.code === "Space" || e.code === "KeyC")
        && !e.ctrlKey && !e.metaKey && !e.altKey) {
      e.preventDefault();
      moveKeys.add(e.code);
      return;
    }
    if (e.key === "ArrowLeft" || e.key === "ArrowRight" || e.key === "ArrowUp" || e.key === "ArrowDown") {
      e.preventDefault();
      if (e.ctrlKey || e.metaKey) {
        const dx = e.key === "ArrowLeft" ? -1 : e.key === "ArrowRight" ? 1 : 0;
        const dy = e.key === "ArrowUp" ? 1 : e.key === "ArrowDown" ? -1 : 0;
        look(dx * CAM_ROT_STEP, dy * CAM_ROT_STEP);
      } else {
        moveKeys.add(e.code);
      }
    }
  }, true);

  document.addEventListener("keyup", (e) => {
    if (e.code === "AltLeft" || e.code === "AltRight") altHeld = false;
    shiftHeld = e.shiftKey;
    if (e.code === "ControlLeft" || e.code === "ControlRight"
        || e.code === "MetaLeft" || e.code === "MetaRight") ctrlHeld = false;
    moveKeys.delete(e.code);
  }, true);

  window.addEventListener("blur", () => {
    moveKeys.clear();
    altHeld = false;
    ctrlHeld = false;
    shiftHeld = false;
  });
}
