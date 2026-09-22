// Server connection: the SSE scene stream in, commands to JOSM out.
import { now, PROFILE } from "./util.js";

/**
 * Subscribe to /events (EventSource reconnects on its own).
 * onMessage(msg, meta) gets each parsed message; meta carries parse timing
 * when profiling. onStatus(live, text) reflects the connection.
 */
export function connect(onMessage, onStatus) {
  onStatus(false, "connecting...");
  const es = new EventSource("/events");
  es.onopen = () => onStatus(true, "live");
  es.onmessage = (ev) => {
    let msg;
    const tp = PROFILE ? now() : 0;
    try { msg = JSON.parse(ev.data); } catch (_) { return; }
    const meta = PROFILE ? { parseMs: now() - tp, bytes: ev.data.length } : null;
    onMessage(msg, meta);
  };
  es.onerror = () => onStatus(false, "reconnecting...");
  return es;
}

/**
 * POST ops to /command; the server forwards them to every connected JOSM
 * bridge. Resolves to { ok, delivered } or { ok: false, error }.
 */
export function sendCommand(ops) {
  return fetch("/command", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ type: "command", ops }),
  }).then((r) => {
    if (!r.ok) throw new Error("HTTP " + r.status);
    return r.json();
  }).catch((err) => {
    console.warn("[viewer] command failed", err);
    return { ok: false, delivered: 0, error: String(err && err.message ? err.message : err) };
  });
}
