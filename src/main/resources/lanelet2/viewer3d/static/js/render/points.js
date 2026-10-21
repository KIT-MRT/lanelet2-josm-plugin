// LiDAR point clouds.
//
// One `THREE.Points` per loaded cloud, fed straight from the scene server's
// binary blob (see tools/scene_server/ll2_scene/points.py): float32 xyz
// relative to a float64 origin, uint8 intensity, uint8 sensor id. The origin
// becomes the object's position, so the attribute stays small and float32
// loses nothing even though ENU metres run into the kilometres.
//
// Colour is computed in the vertex shader from attributes that are already on
// the GPU, so switching between intensity / height / sensor costs no upload.
import * as THREE from "three";

export const COLOR_MODES = ["intensity", "height", "sensor"];
const MAGIC = "LL2PC1\0\0";
const HEADER_SIZE = 40;
const FLAG_INTENSITY = 1;
const FLAG_SENSOR = 2;

// lidar_top, corner left/right, left, right, front, rear — the dataset's own
// sensor_id order.
const SENSOR_COLORS = [
  0xffffff, 0xff6b6b, 0x4fc3f7, 0x9bd24f, 0xffd23f, 0xc792ea, 0xff9f43,
];

const VERTEX = /* glsl */`
attribute float aIntensity;
attribute float aSensor;
uniform float uSize;
uniform int uMode;
uniform float uGain;
uniform float uZMin;
uniform float uZRange;
uniform vec3 uSensorColors[7];
varying vec3 vColor;
#include <common>
#include <logdepthbuf_pars_vertex>

vec3 viridis(float t) {
  t = clamp(t, 0.0, 1.0);
  const vec3 c0 = vec3(0.2777273272234177, 0.005407344544966578, 0.3340998053353061);
  const vec3 c1 = vec3(0.1050930431085774, 1.404613529898575, 1.384590162594685);
  const vec3 c2 = vec3(-0.3308618287255563, 0.214847559468213, 0.09509516302823659);
  const vec3 c3 = vec3(-4.634230498983486, -5.799100973351585, -19.33244095627987);
  const vec3 c4 = vec3(6.228269936347081, 14.17993336680509, 56.69055260068105);
  const vec3 c5 = vec3(4.776384997670288, -13.74514537774601, -65.35303263337234);
  const vec3 c6 = vec3(-5.435455855934631, 4.645852612178535, 26.3124352495832);
  return c0 + t * (c1 + t * (c2 + t * (c3 + t * (c4 + t * (c5 + t * c6)))));
}

void main() {
  vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
  gl_Position = projectionMatrix * mvPosition;
  gl_PointSize = uSize;
  if (uMode == 1) {
    vColor = viridis((position.z + uZMin) / uZRange);
  } else if (uMode == 2) {
    vColor = uSensorColors[int(clamp(aSensor, 0.0, 6.0))];
  } else {
    vColor = viridis(clamp(aIntensity * uGain, 0.0, 1.0));
  }
  #include <logdepthbuf_vertex>
}
`;

const FRAGMENT = /* glsl */`
varying vec3 vColor;
#include <common>
#include <logdepthbuf_pars_fragment>
void main() {
  #include <logdepthbuf_fragment>
  gl_FragColor = vec4(vColor, 1.0);
}
`;

/** Parse a cloud blob: { count, origin, positions, intensity, sensor }. */
export function parseCloud(buffer) {
  const view = new DataView(buffer);
  let magic = "";
  for (let i = 0; i < 8; i++) magic += String.fromCharCode(view.getUint8(i));
  if (magic !== MAGIC) throw new Error(`not a point cloud (${JSON.stringify(magic)})`);
  const count = view.getUint32(8, true);
  const flags = view.getUint32(12, true);
  const origin = [view.getFloat64(16, true), view.getFloat64(24, true), view.getFloat64(32, true)];
  const positions = new Float32Array(buffer, HEADER_SIZE, count * 3);
  let offset = HEADER_SIZE + count * 12;
  let intensity = null;
  let sensor = null;
  if (flags & FLAG_INTENSITY) {
    intensity = new Uint8Array(buffer, offset, count);
    offset += count;
  }
  if (flags & FLAG_SENSOR) sensor = new Uint8Array(buffer, offset, count);
  return { count, origin, positions, intensity, sensor };
}

class PointLayer {
  constructor() {
    this.group = new THREE.Group();
    this.group.name = "pointClouds";
    this.cloud = null;
    this.visible = true;
    this.mode = "intensity";
    this.size = 2;
    this.gain = 3; // most returns sit well below the retro-reflector ceiling
    this.material = new THREE.ShaderMaterial({
      uniforms: {
        uSize: { value: this.size },
        uMode: { value: 0 },
        uGain: { value: this.gain },
        uZMin: { value: 0 },
        uZRange: { value: 10 },
        uSensorColors: { value: SENSOR_COLORS.map((c) => new THREE.Color(c)) },
      },
      vertexShader: VERTEX,
      fragmentShader: FRAGMENT,
    });
  }

  attach(parent) {
    parent.add(this.group);
  }

  /** Replace the shown cloud with one decoded blob. */
  setCloud(buffer) {
    const cloud = parseCloud(buffer);
    this.clear();
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute("position", new THREE.BufferAttribute(cloud.positions, 3));
    if (cloud.intensity) {
      geometry.setAttribute("aIntensity", new THREE.BufferAttribute(cloud.intensity, 1, true));
    } else {
      geometry.setAttribute("aIntensity", new THREE.BufferAttribute(new Uint8Array(cloud.count), 1, true));
    }
    const sensor = cloud.sensor || new Uint8Array(cloud.count);
    geometry.setAttribute("aSensor", new THREE.BufferAttribute(sensor, 1, false));
    geometry.computeBoundingSphere();
    const points = new THREE.Points(geometry, this.material);
    points.position.set(cloud.origin[0], cloud.origin[1], cloud.origin[2]);
    points.frustumCulled = true;
    points.renderOrder = 2;
    this.group.add(points);
    this.cloud = { ...cloud, object: points };
    // Height colouring spans this cloud: a fixed range would be meaningless
    // in a map whose absolute heights are in the hundreds of metres.
    let min = Infinity;
    let max = -Infinity;
    for (let i = 2; i < cloud.positions.length; i += 3) {
      const z = cloud.positions[i];
      if (z < min) min = z;
      if (z > max) max = z;
    }
    if (min > max) {
      min = 0;
      max = 1;
    }
    this.material.uniforms.uZMin.value = -min;
    this.material.uniforms.uZRange.value = Math.max(0.5, max - min);
    this.group.visible = this.visible;
    return this.cloud;
  }

  clear() {
    for (const child of [...this.group.children]) {
      child.geometry.dispose();
      this.group.remove(child);
    }
    this.cloud = null;
  }

  setVisible(on) {
    this.visible = !!on;
    this.group.visible = this.visible;
  }

  setMode(mode) {
    this.mode = COLOR_MODES.includes(mode) ? mode : "intensity";
    this.material.uniforms.uMode.value = COLOR_MODES.indexOf(this.mode);
  }

  setSize(px) {
    this.size = Math.max(1, Math.min(12, px));
    this.material.uniforms.uSize.value = this.size;
  }

  setGain(gain) {
    this.gain = Math.max(0.25, Math.min(16, gain));
    this.material.uniforms.uGain.value = this.gain;
  }

  stats() {
    const cloud = this.cloud;
    return {
      count: cloud ? cloud.count : 0,
      visible: this.group.visible,
      mode: this.mode,
      size: this.size,
      origin: cloud ? cloud.origin : null,
      // First point in world ENU, so a test can check where a cloud landed.
      first: cloud && cloud.count
        ? [cloud.origin[0] + cloud.positions[0],
           cloud.origin[1] + cloud.positions[1],
           cloud.origin[2] + cloud.positions[2]]
        : null,
    };
  }
}

export const pointLayer = new PointLayer();
