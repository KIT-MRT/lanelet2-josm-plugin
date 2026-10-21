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

// "solid" is not offered in the colour menu: it is what the single frame is
// painted when it is shown on top of the aggregate, so the two can be told
// apart at a glance.
export const COLOR_MODES = ["intensity", "height", "sensor", "solid", "class", "rgb"];
export const MENU_MODES = ["intensity", "height", "sensor"];
const FRAME_TINT = 0xff6b35; // warm orange: nothing in viridis comes near it
const MAGIC = "LL2PC1\0\0";
const HEADER_SIZE = 40;
const FLAG_INTENSITY = 1;
const FLAG_SENSOR = 2;
const FLAG_CLASS = 4;
const FLAG_RGB = 8;

// lidar_top, corner left/right, left, right, front, rear — the dataset's own
// sensor_id order.
const SENSOR_COLORS = [
  0xffffff, 0xff6b6b, 0x4fc3f7, 0x9bd24f, 0xffd23f, 0xc792ea, 0xff9f43,
];

const VERTEX = /* glsl */`
attribute float aIntensity;
attribute float aSensor;
attribute float aClass;
attribute vec3 aRGB;
uniform float uSize;
uniform int uMode;
uniform float uGain;
uniform float uZMin;
uniform float uZRange;
uniform vec3 uSensorColors[7];
uniform vec3 uSolid;
uniform sampler2D uPalette;
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
  } else if (uMode == 3) {
    vColor = uSolid;
  } else if (uMode == 4) {
    // aClass is a normalised byte, so * 255 is the Vistas class id; the
    // palette is one texel per id.
    vColor = texture2D(uPalette, vec2((aClass * 255.0 + 0.5) / 256.0, 0.5)).rgb;
  } else if (uMode == 5) {
    vColor = aRGB;
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
  if (flags & FLAG_SENSOR) {
    sensor = new Uint8Array(buffer, offset, count);
    offset += count;
  }
  let classes = null;
  if (flags & FLAG_CLASS) {
    classes = new Uint8Array(buffer, offset, count);
    offset += count;
  }
  let rgb = null;
  if (flags & FLAG_RGB) rgb = new Uint8Array(buffer, offset, count * 3);
  return { count, origin, positions, intensity, sensor, classes, rgb };
}

// One texel per class id, filled from the palette the scene server sends.
const paletteTexture = new THREE.DataTexture(
  new Uint8Array(256 * 4), 256, 1, THREE.RGBAFormat);
paletteTexture.needsUpdate = true;

/** Install the Vistas colours: `rows` is 256 [r, g, b] entries. */
export function setClassPalette(rows) {
  const data = paletteTexture.image.data;
  for (let i = 0; i < 256; i++) {
    const rgb = (rows && rows[i]) || [90, 90, 90];
    data[i * 4] = rgb[0];
    data[i * 4 + 1] = rgb[1];
    data[i * 4 + 2] = rgb[2];
    data[i * 4 + 3] = 255;
  }
  paletteTexture.needsUpdate = true;
}

function makeMaterial(size, gain) {
  return new THREE.ShaderMaterial({
    uniforms: {
      uSize: { value: size },
      uMode: { value: 0 },
      uGain: { value: gain },
      uZMin: { value: 0 },
      uZRange: { value: 10 },
      uSensorColors: { value: SENSOR_COLORS.map((c) => new THREE.Color(c)) },
      uSolid: { value: new THREE.Color(FRAME_TINT) },
      uPalette: { value: paletteTexture },
    },
    vertexShader: VERTEX,
    fragmentShader: FRAGMENT,
  });
}

class PointLayer {
  constructor() {
    this.group = new THREE.Group();
    this.group.name = "pointClouds";
    // The single frame and the whole-scene aggregate are separate groups so
    // either can be shown alone, and separate materials because the height
    // colouring is scaled to the cloud it belongs to.
    this.frameGroup = new THREE.Group();
    this.aggregateGroup = new THREE.Group();
    this.group.add(this.frameGroup);
    this.group.add(this.aggregateGroup);
    this.cloud = null;
    this.visible = true;
    this.aggregateVisible = true;
    this.aggregateBounds = null; // { min, max } z over every chunk loaded
    this.aggregateCount = 0;
    this.aggregateHasClasses = false;
    this.aggregateHasRgb = false;
    this.mode = "intensity";
    // Tell the live frame from the aggregate by colour. Only takes effect
    // while both are on screen: a lone frame painted one flat colour would
    // throw away the intensity that makes markings readable.
    this.tintFrame = true;
    this.size = 2;
    this.gain = 3; // most returns sit well below the retro-reflector ceiling
    this.material = makeMaterial(this.size, this.gain);
    this.aggregateMaterial = makeMaterial(this.size, this.gain);
    this.materials = [this.material, this.aggregateMaterial];
  }

  /** Add one chunk of the whole-scene aggregate. */
  addAggregateChunk(buffer) {
    const cloud = parseCloud(buffer);
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute("position", new THREE.BufferAttribute(cloud.positions, 3));
    geometry.setAttribute("aIntensity", new THREE.BufferAttribute(
      cloud.intensity || new Uint8Array(cloud.count), 1, true));
    geometry.setAttribute("aSensor", new THREE.BufferAttribute(new Uint8Array(cloud.count), 1, false));
    geometry.setAttribute("aClass", new THREE.BufferAttribute(
      cloud.classes || new Uint8Array(cloud.count).fill(255), 1, true));
    geometry.setAttribute("aRGB", new THREE.BufferAttribute(
      cloud.rgb || new Uint8Array(cloud.count * 3).fill(90), 3, true));
    geometry.computeBoundingSphere();
    const points = new THREE.Points(geometry, this.aggregateMaterial);
    points.position.set(cloud.origin[0], cloud.origin[1], cloud.origin[2]);
    points.renderOrder = 2;
    this.aggregateGroup.add(points);
    this.aggregateCount += cloud.count;
    if (cloud.classes) this.aggregateHasClasses = true;
    if (cloud.rgb) this.aggregateHasRgb = true;
    let min = Infinity;
    let max = -Infinity;
    for (let i = 2; i < cloud.positions.length; i += 3) {
      const z = cloud.origin[2] + cloud.positions[i];
      if (z < min) min = z;
      if (z > max) max = z;
    }
    const bounds = this.aggregateBounds || { min, max };
    bounds.min = Math.min(bounds.min, min);
    bounds.max = Math.max(bounds.max, max);
    this.aggregateBounds = bounds;
    // Heights are absolute metres here, so the colour ramp has to span what
    // was actually loaded or everything comes out one colour.
    this.aggregateMaterial.uniforms.uZMin.value = -bounds.min;
    this.aggregateMaterial.uniforms.uZRange.value = Math.max(0.5, bounds.max - bounds.min);
    return cloud.count;
  }

  clearAggregate() {
    for (const child of [...this.aggregateGroup.children]) {
      child.geometry.dispose();
      this.aggregateGroup.remove(child);
    }
    this.aggregateCount = 0;
    this.aggregateBounds = null;
    this.aggregateHasClasses = false;
    this.aggregateHasRgb = false;
  }

  setAggregateVisible(on) {
    this.aggregateVisible = !!on;
    this.aggregateGroup.visible = this.aggregateVisible;
    this._applyModes();
  }

  setTintFrame(on) {
    this.tintFrame = !!on;
    this._applyModes();
  }

  /** The aggregate always shows the chosen colouring; the frame may be tinted. */
  _applyModes() {
    const chosen = COLOR_MODES.indexOf(this.mode);
    this.aggregateMaterial.uniforms.uMode.value = chosen;
    const tinted = this.aggregateGroup.visible && this.tintFrame;
    this.material.uniforms.uMode.value = tinted ? COLOR_MODES.indexOf("solid") : chosen;
  }

  attach(parent) {
    parent.add(this.group);
  }

  /** Replace the shown single-frame cloud with one decoded blob. */
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
    geometry.setAttribute("aClass", new THREE.BufferAttribute(
      cloud.classes || new Uint8Array(cloud.count).fill(255), 1, true));
    geometry.setAttribute("aRGB", new THREE.BufferAttribute(
      cloud.rgb || new Uint8Array(cloud.count * 3).fill(90), 3, true));
    geometry.computeBoundingSphere();
    const points = new THREE.Points(geometry, this.material);
    points.position.set(cloud.origin[0], cloud.origin[1], cloud.origin[2]);
    points.frustumCulled = true;
    points.renderOrder = 2;
    this.frameGroup.add(points);
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
    this.frameGroup.visible = this.visible;
    return this.cloud;
  }

  clear() {
    for (const child of [...this.frameGroup.children]) {
      child.geometry.dispose();
      this.frameGroup.remove(child);
    }
    this.cloud = null;
  }

  /** The single-frame cloud only. The aggregate has its own switch. */
  setVisible(on) {
    this.visible = !!on;
    this.frameGroup.visible = this.visible;
  }

  setMode(mode) {
    this.mode = COLOR_MODES.includes(mode) && mode !== "solid" ? mode : "intensity";
    this._applyModes();
  }

  /** Does the loaded data carry Vistas classes at all? */
  hasClasses() {
    return Boolean((this.cloud && this.cloud.classes) || this.aggregateHasClasses);
  }

  /** Does it carry camera colour? */
  hasRgb() {
    return Boolean((this.cloud && this.cloud.rgb) || this.aggregateHasRgb);
  }

  setSize(px) {
    this.size = Math.max(1, Math.min(12, px));
    for (const m of this.materials) m.uniforms.uSize.value = this.size;
  }

  setGain(gain) {
    this.gain = Math.max(0.25, Math.min(16, gain));
    for (const m of this.materials) m.uniforms.uGain.value = this.gain;
  }

  /** {class id: points} over the loaded frame cloud, for the HUD and tests. */
  classHistogram() {
    const cloud = this.cloud;
    if (!cloud || !cloud.classes) return null;
    const counts = {};
    for (let i = 0; i < cloud.count; i++) {
      const id = cloud.classes[i];
      counts[id] = (counts[id] || 0) + 1;
    }
    return counts;
  }

  stats() {
    const cloud = this.cloud;
    return {
      count: cloud ? cloud.count : 0,
      // Effective visibility: a flag on a group whose parent is hidden says
      // nothing, which is exactly how hiding the frame cloud once took the
      // aggregate with it.
      visible: this.group.visible && this.frameGroup.visible,
      mode: this.mode,
      frameMode: COLOR_MODES[this.material.uniforms.uMode.value],
      tintFrame: this.tintFrame,
      size: this.size,
      origin: cloud ? cloud.origin : null,
      aggregate: this.aggregateCount,
      aggregateChunks: this.aggregateGroup.children.length,
      aggregateVisible: this.group.visible && this.aggregateGroup.visible,
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
