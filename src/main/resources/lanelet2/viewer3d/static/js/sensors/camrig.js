// Camera lock: put the three.js camera exactly where a ring camera was.
//
// With the lock on, the view is no longer yaw/pitch over the map: the camera
// matrix comes from the vehicle's 6D pose times that camera's extrinsic, and
// the projection comes from its intrinsics. The recorded image can then be
// blended over the render and every pixel corresponds, which is the sharpest
// check there is that the map, the poses and the clouds agree.
//
// Two conventions meet here:
//   * the camera frame is OpenCV (x right, y down, z forward), three.js looks
//     down -z with +y up, so the basis is flipped in y and z;
//   * the intrinsics are a pinhole K with an off-centre principal point, which
//     no `fov` can express, so the projection matrix is built by hand.
// KITScenes ring images are already rectified (the calibration carries no
// distortion coefficients at all), so a pinhole is the whole truth here.
import * as THREE from "three";
import { camera, view, lookAtPoint, setCameraView, CAM_NEAR_M, CAM_FAR_M, ortho, setOrtho } from "../camera.js";
import { renderer, canvas } from "../scene.js";

export const rig = {
  on: false,
  camera: null,     // sensor name, e.g. "camera_ring_front"
  opacity: 0.45,
  overlay: true,
};

const DEFAULT_CAMERA = "camera_ring_front";
// OpenCV camera axes -> three.js camera axes.
const CV_TO_GL = new THREE.Matrix4().makeScale(1, -1, -1);

let info = null;        // the scene description
let imageUrlFor = null; // (camera, frame) -> url
let frameOf = () => 0;  // the scene's current frame
let els = {};
let saved = null;       // view state to restore when the lock goes off
const _m = new THREE.Matrix4();
const _ego = new THREE.Matrix4();
const _ext = new THREE.Matrix4();
const _pos = new THREE.Vector3();
const _quat = new THREE.Quaternion();
const _scale = new THREE.Vector3();

/**
 * Wire the lock controls. `urlFor(camera, frame)` builds an image URL and
 * `currentFrame()` says which frame the scene is showing -- the rig follows
 * the scene rather than keeping a second frame counter that can drift.
 */
export function installCamRig({ urlFor, currentFrame } = {}) {
  imageUrlFor = urlFor;
  if (currentFrame) frameOf = currentFrame;
  els = {
    bar: document.getElementById("sceneBar"),
    lock: document.getElementById("sceneLock"),
    select: document.getElementById("sceneCamera"),
    overlay: document.getElementById("sceneOverlay"),
    opacity: document.getElementById("sceneOpacity"),
    image: document.getElementById("sceneImage"),
  };
  if (!els.lock) return;
  els.lock.addEventListener("click", () => setLocked(!rig.on));
  els.select.addEventListener("change", () => setCamera(els.select.value));
  els.overlay.addEventListener("click", () => setOverlay(!rig.overlay));
  els.opacity.addEventListener("input", () => setOpacity(Number(els.opacity.value) / 100));
  setOpacity(rig.opacity);
}

/** A new scene: fill the camera list. */
export function setSceneInfo(sceneInfo) {
  info = sceneInfo;
  const names = Object.keys((info && info.cameras) || {});
  rig.camera = names.includes(DEFAULT_CAMERA) ? DEFAULT_CAMERA : (names[0] || null);
  if (els.select) {
    els.select.innerHTML = "";
    for (const name of names) {
      const option = document.createElement("option");
      option.value = name;
      option.textContent = name.replace("camera_ring_", "");
      els.select.appendChild(option);
    }
    els.select.value = rig.camera || "";
    els.select.disabled = names.length === 0;
    els.lock.disabled = names.length === 0;
  }
  if (rig.on) setLocked(false);
}

export const rigActive = () => rig.on;

export function setCamera(name) {
  if (!info || !info.cameras[name]) return;
  rig.camera = name;
  if (els.select) els.select.value = name;
  if (rig.on) refreshImage();
}

export function setLocked(on, frame) {
  const next = !!on && !!info && !!rig.camera;
  if (next === rig.on) {
    if (next) update(frame);
    return rig.on;
  }
  // The lock is the recorded camera's own perspective; a map view has none.
  if (next && ortho.on) setOrtho(false);
  rig.on = next;
  if (els.lock) els.lock.classList.toggle("on", rig.on);
  if (rig.on) {
    saved = { fov: camera.fov, aspect: camera.aspect };
    update(frame);
  } else {
    restore();
  }
  return rig.on;
}

export function setOverlay(on) {
  rig.overlay = !!on;
  if (els.overlay) els.overlay.classList.toggle("on", rig.overlay);
  refreshImage();
}

export function setOpacity(value) {
  rig.opacity = Math.max(0, Math.min(1, value));
  if (els.opacity) els.opacity.value = String(Math.round(rig.opacity * 100));
  if (els.image) els.image.style.opacity = String(rig.opacity);
}

/**
 * Point the camera at what sensor `rig.camera` saw in `frame`.
 * Called when the lock goes on, when the frame changes and every resize.
 */
export function update(frame) {
  if (!rig.on || !info || !info.ego) return false;
  const index = frame === undefined ? frameOf() : frame;
  shownFrame = index;
  const pos = info.ego.pos[index];
  const quat = info.ego.quat[index];
  const calib = info.cameras[rig.camera];
  if (!pos || !quat || !calib) return false;

  _ego.compose(new THREE.Vector3(pos[0], pos[1], pos[2]),
               new THREE.Quaternion(quat[0], quat[1], quat[2], quat[3]),
               new THREE.Vector3(1, 1, 1));
  // T_cam_to_ego is row-major in JSON; three.js `set` takes row-major too.
  const t = calib.T_cam_to_ego;
  _ext.set(t[0][0], t[0][1], t[0][2], t[0][3],
           t[1][0], t[1][1], t[1][2], t[1][3],
           t[2][0], t[2][1], t[2][2], t[2][3],
           t[3][0], t[3][1], t[3][2], t[3][3]);
  _m.multiplyMatrices(_ego, _ext).multiply(CV_TO_GL);
  _m.decompose(_pos, _quat, _scale);
  camera.position.copy(_pos);
  camera.quaternion.copy(_quat);
  camera.updateMatrixWorld(true);
  applyIntrinsics(calib);
  applyViewport(calib);
  refreshImage();
  return true;
}

let shownFrame = 0;

/** OpenCV K -> an OpenGL projection matrix, principal point and all. */
function applyIntrinsics(calib) {
  const { fx, fy, cx, cy, width: w, height: h } = calib;
  const n = CAM_NEAR_M;
  const f = CAM_FAR_M;
  camera.projectionMatrix.set(
    (2 * fx) / w, 0, (w - 2 * cx) / w, 0,
    0, (2 * fy) / h, -(h - 2 * cy) / h, 0,
    0, 0, -(f + n) / (f - n), (-2 * f * n) / (f - n),
    0, 0, -1, 0);
  camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert();
  // Keep fov/aspect consistent for anything that reads them.
  camera.fov = 2 * Math.atan(h / (2 * fy)) * (180 / Math.PI);
  camera.aspect = w / h;
}

/**
 * Render into the largest centred rectangle with the image's aspect, so a
 * rendered pixel is an image pixel. Returns that rectangle in CSS pixels.
 */
export function viewportRect(calib) {
  const cw = window.innerWidth;
  const ch = window.innerHeight;
  const aspect = calib.width / calib.height;
  let w = cw;
  let h = Math.round(cw / aspect);
  if (h > ch) {
    h = ch;
    w = Math.round(ch * aspect);
  }
  return { x: Math.round((cw - w) / 2), y: Math.round((ch - h) / 2), width: w, height: h };
}

function applyViewport(calib) {
  const r = viewportRect(calib);
  // Clear the whole canvas once, or the letterbox bars keep whatever the
  // unlocked view last drew there; a darker bar also shows where the image
  // frame ends.
  renderer.setScissorTest(false);
  renderer.setClearColor(0x05070a, 1);
  renderer.clear();
  // three.js viewport y counts from the bottom.
  renderer.setViewport(r.x, window.innerHeight - r.y - r.height, r.width, r.height);
  renderer.setScissor(r.x, window.innerHeight - r.y - r.height, r.width, r.height);
  renderer.setScissorTest(true);
  if (els.image) {
    Object.assign(els.image.style, {
      left: `${r.x}px`, top: `${r.y}px`, width: `${r.width}px`, height: `${r.height}px`,
    });
  }
}

function refreshImage() {
  if (!els.image) return;
  const show = rig.on && rig.overlay && imageUrlFor && rig.camera;
  els.image.hidden = !show;
  if (!show) {
    els.image.removeAttribute("src");
    return;
  }
  const url = imageUrlFor(rig.camera, shownFrame);
  if (els.image.getAttribute("src") !== url) els.image.setAttribute("src", url);
}

/**
 * Hand the view back to the free camera **where it is now**.
 *
 * Releasing the lock used to jump back to wherever the camera was before it,
 * which throws away the thing you locked on to look at. The position is kept
 * untouched and the heading/pitch are read off the camera's own direction;
 * only the roll is dropped, because the free camera is yaw/pitch about a
 * fixed world up and has nowhere to put it.
 */
function restore() {
  renderer.setScissorTest(false);
  renderer.setViewport(0, 0, window.innerWidth, window.innerHeight);
  if (els.image) {
    els.image.hidden = true;
    els.image.removeAttribute("src");
  }
  if (saved) {
    camera.fov = saved.fov;
    camera.aspect = window.innerWidth / window.innerHeight;
    saved = null;
  }
  camera.updateProjectionMatrix();
  const dir = new THREE.Vector3();
  camera.getWorldDirection(dir);
  const horiz = Math.hypot(dir.x, dir.y);
  // yaw 0 looks north along +y, counter-clockwise positive (see camera.js).
  const yaw = horiz > 1e-9 ? Math.atan2(-dir.x, dir.y) : view.yaw;
  setCameraView(null, yaw, Math.atan2(dir.z, horiz));
}

/** Keys: C locks/unlocks, [ and ] walk the ring. */
export function handleRigKey(event) {
  if (!info || !rig.camera) return false;
  if ((event.key === "l" || event.key === "L") && event.shiftKey) {
    setLocked(!rig.on);
    return true;
  }
  if (rig.on && (event.key === "[" || event.key === "]")) {
    const names = Object.keys(info.cameras);
    const at = names.indexOf(rig.camera);
    const next = (at + (event.key === "]" ? 1 : names.length - 1)) % names.length;
    setCamera(names[next]);
    update();
    return true;
  }
  if (rig.on && (event.key === "o" || event.key === "O")) {
    setOverlay(!rig.overlay);
    return true;
  }
  return false;
}

/**
 * Where a world point lands in the recorded image, in image pixels.
 * Goes through the actual render camera, so a test can hold it against the
 * plain pinhole formula and catch an axis flip or a principal-point sign.
 */
export function projectToImage(world) {
  if (!rig.on || !info) return null;
  const calib = info.cameras[rig.camera];
  if (!calib) return null;
  const ndc = new THREE.Vector3(world[0], world[1], world[2]).project(camera);
  const behind = new THREE.Vector3(world[0], world[1], world[2])
    .applyMatrix4(camera.matrixWorldInverse).z > 0; // three.js looks down -z
  return { u: ((ndc.x + 1) / 2) * calib.width, v: ((1 - ndc.y) / 2) * calib.height, behind };
}

export function rigState() {
  return {
    on: rig.on,
    camera: rig.camera,
    overlay: rig.overlay && rig.on,
    opacity: rig.opacity,
    frame: shownFrame,
    viewport: rig.on && info && info.cameras[rig.camera] ? viewportRect(info.cameras[rig.camera]) : null,
    imageSrc: els.image && !els.image.hidden ? els.image.getAttribute("src") : null,
  };
}
