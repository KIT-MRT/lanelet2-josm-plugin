// Headless-Chrome harness for the 3D viewer: runs the real server.py, plays
// the JOSM bridge on the ingest socket, and drives the page over the Chrome
// DevTools Protocol. Node 22+ (built-in WebSocket), no npm dependencies.
import { spawn } from "node:child_process";
import net from "node:net";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
// LL2_VIEWER_DIR points the harness at another viewer tree (e.g. an older
// commit's, to compare performance).
export const VIEWER_DIR = process.env.LL2_VIEWER_DIR
  || path.resolve(HERE, "../../src/main/resources/lanelet2/viewer3d");
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const { port } = srv.address();
      srv.close(() => resolve(port));
    });
  });
}

function findChrome() {
  const candidates = [process.env.CHROME, "google-chrome", "chromium", "chromium-browser"].filter(Boolean);
  for (const c of candidates) {
    if (path.isAbsolute(c) && fs.existsSync(c)) return c;
    for (const dir of (process.env.PATH || "").split(path.delimiter)) {
      if (fs.existsSync(path.join(dir, c))) return path.join(dir, c);
    }
  }
  throw new Error("No Chrome found; set CHROME=/path/to/chrome");
}

/** Pass/fail bookkeeping with one line per check. */
export class Checks {
  constructor() { this.failures = 0; this.count = 0; }
  check(name, ok, detail) {
    this.count++;
    console.log(`${ok ? "PASS" : "FAIL"}  ${name}${detail !== undefined && detail !== "" ? "  — " + detail : ""}`);
    if (!ok) this.failures++;
  }
}

/**
 * Start server + fake bridge + Chrome, load the viewer with `?test=1`.
 * Returns a session with input helpers and `close()`.
 */
export async function openViewer({ width = 1280, height = 800, shotsDir = null, query = "" } = {}) {
  const http = await freePort();
  const ingest = await freePort();
  const cdpPort = await freePort();
  const server = spawn("python3", [path.join(VIEWER_DIR, "server.py"), "--host", "127.0.0.1",
    "--http-port", String(http), "--ingest-port", String(ingest), "--no-browser",
    "--icons-dir", path.join(VIEWER_DIR, "..", "style_images")], { stdio: ["ignore", "pipe", "pipe"] });
  let serverLog = "";
  server.stdout.on("data", (d) => { serverLog += d; });
  server.stderr.on("data", (d) => { serverLog += d; });
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(`http://127.0.0.1:${http}/healthz`)).ok) break; } catch (_) { /* not up yet */ }
    await sleep(100);
  }

  // Fake JOSM bridge: sends scene messages, records forwarded commands and
  // answers those with an "id" like the plugin does. `session.reply(cmd)`
  // decides the answer ({ ok, message }, or null for none); default: accept.
  const commands = [];
  let replyFn = () => ({ ok: true, message: "ok" });
  const bridge = net.connect(ingest, "127.0.0.1");
  await new Promise((r, j) => { bridge.once("connect", r); bridge.once("error", j); });
  let buf = "";
  bridge.on("data", (d) => {
    buf += d.toString();
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i);
      buf = buf.slice(i + 1);
      if (!line.trim()) continue;
      const cmd = JSON.parse(line);
      commands.push(cmd);
      const answer = cmd.id ? replyFn(cmd) : null;
      if (answer) {
        bridge.write(JSON.stringify({ type: "command_result", id: cmd.id, ok: answer.ok, message: answer.message }) + "\n");
      }
    }
  });
  const sendScene = (msg) => bridge.write(JSON.stringify(msg) + "\n");

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "ll2-viewer-e2e-"));
  const chrome = spawn(findChrome(), ["--headless=new", `--remote-debugging-port=${cdpPort}`,
    `--user-data-dir=${profile}`, `--window-size=${width},${height}`, "--no-first-run",
    "--no-default-browser-check", "--use-angle=swiftshader", "--enable-unsafe-swiftshader",
    "about:blank"], { stdio: "ignore" });
  let targets = [];
  for (let i = 0; i < 100 && !targets.some((t) => t.type === "page"); i++) {
    try { targets = await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json(); } catch (_) { /* starting */ }
    if (!targets.some((t) => t.type === "page")) await sleep(100);
  }
  const page = targets.find((t) => t.type === "page");
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((r) => ws.addEventListener("open", r, { once: true }));

  let msgId = 0;
  const pending = new Map();
  const errors = [];
  ws.addEventListener("message", (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
    if (m.method === "Runtime.exceptionThrown") {
      const d = m.params.exceptionDetails;
      errors.push((d.exception && d.exception.description) || d.text);
    }
    if (m.method === "Runtime.consoleAPICalled" && m.params.type === "error") {
      errors.push(m.params.args.map((a) => a.value ?? a.description).join(" "));
    }
  });
  const cdp = (method, params = {}) => {
    const id = ++msgId;
    ws.send(JSON.stringify({ id, method, params }));
    return new Promise((r) => pending.set(id, (m) => r(m.result)));
  };
  const evaluate = async (expr) => {
    const r = await cdp("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(`evaluate failed: ${expr}\n${JSON.stringify(r.exceptionDetails)}`);
    return r.result.value;
  };

  await cdp("Runtime.enable");
  await cdp("Page.enable");
  await cdp("Page.navigate", { url: `http://127.0.0.1:${http}/?test=1${query ? "&" + query : ""}` });
  for (let i = 0; i < 100; i++) {
    if (await evaluate("typeof window.__ll2test === 'object'").catch(() => false)) break;
    await sleep(100);
  }

  const BTN = { left: 1, right: 2, middle: 4, none: 0 };
  const MOD = { alt: 1, ctrl: 2, meta: 4, shift: 8 };
  const mods = (list = []) => list.reduce((a, m) => a | MOD[m], 0);
  const mouse = (type, x, y, button = "none", extra = {}) =>
    cdp("Input.dispatchMouseEvent", { type, x, y, button, buttons: BTN[button], ...extra });

  const s = {
    commands, errors, evaluate, cdp, sendScene,
    set reply(fn) { replyFn = fn; },
    /** Ops of every command received so far (flattened), optionally by op name. */
    ops: (name) => commands.flatMap((c) => c.ops || []).filter((o) => !name || o.op === name),
    get serverLog() { return serverLog; },
    /** Wait until the HUD feature count equals `n`. */
    async waitForFeatures(n, timeoutMs = 10000) {
      const t0 = Date.now();
      while (Date.now() - t0 < timeoutMs) {
        if ((await evaluate("document.getElementById('count').textContent")) === String(n)) return true;
        await sleep(50);
      }
      return false;
    },
    project: (p) => evaluate(`window.__ll2test.project(${p[0]}, ${p[1]}, ${p[2]})`),
    camera: () => evaluate("window.__ll2test.camera()"),
    pivot: () => evaluate("window.__ll2test.pivot()"),
    selected: () => evaluate("window.__ll2test.selected()"),
    hud: (id) => evaluate(`document.getElementById(${JSON.stringify(id)}).textContent`),
    async drag(button, x0, y0, dx, dy, { steps = 12, modifiers = [], midShot = null } = {}) {
      const m = mods(modifiers);
      await mouse("mouseMoved", x0, y0, "none", { modifiers: m });
      await mouse("mousePressed", x0, y0, button, { clickCount: 1, modifiers: m });
      for (let i = 1; i <= steps; i++) {
        await mouse("mouseMoved", x0 + (dx * i) / steps, y0 + (dy * i) / steps, button, { modifiers: m });
        if (midShot && i === Math.round(steps / 2)) await s.shot(midShot);
      }
      await mouse("mouseReleased", x0 + dx, y0 + dy, button, { clickCount: 1, modifiers: m });
      await sleep(50);
    },
    async click(x, y, { button = "left", modifiers = [] } = {}) {
      const m = mods(modifiers);
      await mouse("mouseMoved", x, y, "none", { modifiers: m });
      await mouse("mousePressed", x, y, button, { clickCount: 1, modifiers: m });
      await mouse("mouseReleased", x, y, button, { clickCount: 1, modifiers: m });
      await sleep(50);
    },
    async wheel(x, y, deltaY, n = 1) {
      for (let i = 0; i < n; i++) {
        await cdp("Input.dispatchMouseEvent", { type: "mouseWheel", x, y, deltaX: 0, deltaY });
        await sleep(20);
      }
      await sleep(50);
    },
    /** Press and release a key. `key` is the DOM key ("b", "Delete", " "). */
    async key(key, { code = null, modifiers = [], holdMs = 0 } = {}) {
      const c = code || (key.length === 1 && /[a-z]/i.test(key) ? "Key" + key.toUpperCase() : key);
      const m = mods(modifiers);
      await cdp("Input.dispatchKeyEvent", { type: "keyDown", key, code: c, modifiers: m, text: key.length === 1 ? key : undefined });
      if (holdMs) await sleep(holdMs);
      await cdp("Input.dispatchKeyEvent", { type: "keyUp", key, code: c, modifiers: m });
      await sleep(50);
    },
    async shot(name) {
      if (!shotsDir) return;
      fs.mkdirSync(shotsDir, { recursive: true });
      const r = await cdp("Page.captureScreenshot", { format: "png" });
      fs.writeFileSync(path.join(shotsDir, name), Buffer.from(r.data, "base64"));
    },
    async close() {
      try { ws.close(); } catch (_) { /* already closed */ }
      chrome.kill();
      bridge.destroy();
      server.kill();
      await sleep(200);
      fs.rmSync(profile, { recursive: true, force: true });
    },
  };
  return s;
}

export const dist2 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1]);
export const dist3 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);
