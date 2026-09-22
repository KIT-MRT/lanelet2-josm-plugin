// Camera state and every way of moving it (orbit, look, walk, framing).
//
// The camera has no look-at target. It is a position plus a heading
// (`view.yaw`: rotation about world Z, 0 = looking north along +Y,
// counter-clockwise positive) and a pitch (0 = level, -90° = straight down).
// World Z is always screen-up, so there is no roll, and a straight-down BEV is
// an ordinary state with a defined north rather than a lookAt singularity.
import * as THREE from "three";
import { renderer } from "./scene.js";

export const CAM_NEAR_M = 0.02;
export const CAM_FAR_M = 500000;
const HALF_PI = Math.PI / 2;
const DEFAULT_BEV_HEIGHT_M = 100;
const _worldUp = new THREE.Vector3(0, 0, 1);
const _camX = new THREE.Vector3(1, 0, 0);
const _qYaw = new THREE.Quaternion();
const _qPitch = new THREE.Quaternion();
const _qOrbit = new THREE.Quaternion();
const _orbitAxis = new THREE.Vector3();
const _orbitOffset = new THREE.Vector3();
const _lookDir = new THREE.Vector3();

export const camera = new THREE.PerspectiveCamera(
  55, window.innerWidth / window.innerHeight, CAM_NEAR_M, CAM_FAR_M);
camera.up.copy(_worldUp); // Z is up (ENU)

export const view = { yaw: 0, pitch: 0 };

/**
 * Scale hints shared by navigation and framing: the distance of the last map
 * pick (sizes moves over empty sky) and the map height near the cursor (the
 * ground-plane fallback of picking).
 */
export const camRefs = { refDist: 20, groundZ: 0 };

export function clampPitch(p) {
  return Math.max(-HALF_PI, Math.min(HALF_PI, p));
}

// Rebuild the camera orientation from `view`: tip the default -Z look
// direction up to the horizon (+90° about X), then turn it about world Z.
function applyCameraRotation() {
  _qYaw.setFromAxisAngle(_worldUp, view.yaw);
  _qPitch.setFromAxisAngle(_camX, HALF_PI + view.pitch);
  camera.quaternion.multiplyQuaternions(_qYaw, _qPitch);
  camera.updateMatrixWorld();
}

export function setCameraView(position, yaw, pitch) {
  if (position) camera.position.copy(position);
  view.yaw = Math.atan2(Math.sin(yaw), Math.cos(yaw));
  view.pitch = clampPitch(pitch);
  applyCameraRotation();
}

/** Move the camera without turning it. */
export function translateCamera(dx, dy, dz) {
  camera.position.x += dx;
  camera.position.y += dy;
  camera.position.z += dz;
  camera.updateMatrixWorld();
}

// Aim at `target` from the current position. Straight up or down has no
// heading, so that keeps `yawIfVertical` (default: the current heading).
export function lookAtPoint(target, yawIfVertical) {
  _lookDir.subVectors(target, camera.position);
  const horiz = Math.hypot(_lookDir.x, _lookDir.y);
  const yaw = horiz > 1e-9
    ? Math.atan2(-_lookDir.x, _lookDir.y)
    : (yawIfVertical === undefined ? view.yaw : yawIfVertical);
  setCameraView(null, yaw, Math.atan2(_lookDir.z, horiz));
}

// Rotate the camera about `pivot`: heading about the vertical through the
// pivot, pitch about the camera's horizontal right axis through it. Position
// and orientation turn by the same rotation, so the pivot stays on the same
// pixel and nothing snaps to centre it.
export function orbitCamera(pivot, dYaw, dPitch) {
  const pitch = clampPitch(view.pitch + dPitch);
  _orbitOffset.subVectors(camera.position, pivot);
  _orbitAxis.set(Math.cos(view.yaw), Math.sin(view.yaw), 0); // camera right
  _qOrbit.setFromAxisAngle(_orbitAxis, pitch - view.pitch);
  _orbitOffset.applyQuaternion(_qOrbit);
  _qOrbit.setFromAxisAngle(_worldUp, dYaw);
  _orbitOffset.applyQuaternion(_qOrbit);
  setCameraView(_orbitOffset.add(pivot), view.yaw + dYaw, pitch);
}

// FPS look: turn in place. Positive yaw turns right, positive pitch looks up.
export function look(yawRad, pitchRad) {
  setCameraView(null, view.yaw - yawRad, view.pitch + pitchRad);
}

// Walk level along the heading, so forward is screen-up even in a straight-down
// BEV. dx = right, dy = forward, in units of `metres`.
export function walk(dx, dy, metres) {
  const s = Math.sin(view.yaw);
  const c = Math.cos(view.yaw);
  // right = (c, s), forward = (-s, c)
  translateCamera((c * dx - s * dy) * metres, (s * dx + c * dy) * metres, 0);
}

export function elevate(dz, metres) {
  translateCamera(0, 0, dz * metres);
}

/** Compass heading in degrees, clockwise from north. */
export function headingDeg() {
  return (((-view.yaw * 180) / Math.PI) % 360 + 360) % 360;
}

// Metres spanned by `px` screen pixels at `depth` metres in front of the camera.
export function pixelsToMetres(px, depth) {
  const h = renderer.domElement.clientHeight || window.innerHeight || 1;
  return (px * 2 * depth * Math.tan((camera.fov * Math.PI) / 360)) / h;
}

// --- framing -------------------------------------------------------------------
// Each returns the numbers the frame-debug panel shows.

/** Oblique view of `box` (F). */
export function frameBox(box) {
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const radius = Math.max(size.x, size.y, size.z, 5) * 0.5;
  const dist = (radius / Math.tan((camera.fov * Math.PI) / 360)) * 1.6;
  const camPos = new THREE.Vector3(center.x + dist * 0.4, center.y - dist * 0.8, center.z + dist * 0.6);
  camera.position.copy(camPos);
  lookAtPoint(center);
  camRefs.groundZ = center.z;
  camRefs.refDist = camPos.distanceTo(center);
  return { radius, dist, camPos, target: center };
}

/** Straight-down view of `box`, north up (B). */
export function bevBox(box) {
  const center = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const radius = Math.max(size.x, size.y, 5) * 0.5;
  const dist = (radius / Math.tan((camera.fov * Math.PI) / 360)) * 1.6;
  const camPos = new THREE.Vector3(center.x, center.y, center.z + dist);
  setCameraView(camPos, 0, -HALF_PI);
  camRefs.groundZ = center.z;
  camRefs.refDist = dist;
  return { radius, dist, camPos, target: center };
}

/** Fixed sanity view when framing fails or data is far from the origin. */
export function defaultBev() {
  setCameraView(new THREE.Vector3(0, 0, DEFAULT_BEV_HEIGHT_M), 0, -HALF_PI);
  camRefs.refDist = DEFAULT_BEV_HEIGHT_M;
  return DEFAULT_BEV_HEIGHT_M;
}

camera.position.set(40, -60, 50);
lookAtPoint(new THREE.Vector3(0, 0, 0));
