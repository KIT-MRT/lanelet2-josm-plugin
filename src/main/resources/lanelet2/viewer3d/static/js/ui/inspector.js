// The Selection tab: every tag of the selected nodes and ways, and the
// relations they belong to (which lanelet uses this bound, as which side).
//
// The map stream carries only type/subtype, so the full tags are asked for
// on demand with a read-only `describe` command that JOSM answers. Asked
// only while the tab is showing; asked again when the selection changes,
// when a selected way changes in the stream, when the tab opens and when the
// window gets the focus back (tags edited in JOSM do not reach the stream).
// Without JOSM (the scene server's read-only map) the tab falls back to the
// streamed tags and says so.
import { TEST_HOOKS } from "../util.js";
import { store } from "../store.js";
import { selection } from "../selection.js";
import { edit, editEvents, inspectedTokens } from "../edit.js";
import { sendCommand } from "../net.js";
import { toast } from "../hud.js";
import { ui, currentTab, panelOpen, escapeHtml, setTabBadge } from "./shell.js";

const DEBOUNCE_MS = 150;
const REPLY_TIMEOUT_MS = 4000;
const LIST_MAX = 60;          // objects listed by id
const FIRST_KEYS = ["type", "subtype"];

const $ = (id) => document.getElementById(id);

const state = {
  tokens: [],        // what is described
  key: "",           // tokens joined, to tell a new selection from the same one
  details: new Map(),// token -> { tags, memberships, nodes, missing }
  source: null,      // "josm" | "stream"
  note: "",          // why the streamed tags are all there is
  loading: false,
  stale: true,       // needs asking once the tab shows
};
let timer = null;
let generation = 0;

const visible = () => panelOpen() && currentTab() === "inspect";

function schedule(force = false) {
  if (force) state.stale = true;
  const tokens = inspectedTokens();
  const key = tokens.join(",");
  if (key !== state.key) state.stale = true;
  if (!visible()) {
    // Nothing is asked while the tab is hidden; a dot says there is news.
    if (key !== state.key && tokens.length) setTabBadge("inspect", true);
    return;
  }
  clearTimeout(timer);
  timer = setTimeout(() => refresh(), DEBOUNCE_MS);
}

/** Ask JOSM about the current selection (or fall back to the stream). */
export function refresh() {
  clearTimeout(timer);
  const tokens = inspectedTokens();
  state.tokens = tokens;
  state.key = tokens.join(",");
  state.stale = false;
  const gen = ++generation;
  if (!tokens.length) {
    state.details = new Map();
    state.loading = false;
    render();
    return Promise.resolve(null);
  }
  state.loading = true;
  render();
  return sendCommand([{ op: "describe", ids: tokens }], { awaitResult: true, timeoutMs: REPLY_TIMEOUT_MS })
    .then((info) => {
      if (gen !== generation) return null;  // a newer selection won
      state.loading = false;
      const r = info && info.result;
      if (r && r.ok && Array.isArray(r.details)) {
        state.details = new Map(r.details.map((d) => [d.id, d]));
        state.source = "josm";
        state.note = "";
      } else {
        fromStream();
        state.note = !info || info.error ? "The viewer server did not answer."
          : !info.delivered ? "JOSM is not connected, so only type and subtype are known."
          : r && r.timeout ? "JOSM did not answer in time; showing type and subtype only."
          : "This map comes from the scene server, which sends type and subtype only.";
      }
      render();
      return state.details;
    });
}

/** What the stream knows: a way's type/subtype, a node's height. */
function fromStream() {
  state.source = "stream";
  state.details = new Map(state.tokens.map((t) => {
    const f = store.features.get(t);
    return [t, { id: t, tags: f && f.tags ? { ...f.tags } : {}, memberships: [],
                 nodes: f ? f.pos.length / 3 : undefined, missing: !f && t.startsWith("way/") }];
  }));
}

// ---- rendering -------------------------------------------------------------------
const kindOf = (token) => token.slice(0, token.indexOf("/"));
const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;
const sortKeys = (keys) => [...keys].sort((a, b) => {
  const ia = FIRST_KEYS.indexOf(a);
  const ib = FIRST_KEYS.indexOf(b);
  if (ia >= 0 || ib >= 0) return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
  return a < b ? -1 : a > b ? 1 : 0;
});

/** Length and height range of a way, from the streamed geometry. */
function wayGeometry(token) {
  const f = store.features.get(token);
  if (!f || f.kind !== "line") return null;
  let len = 0;
  let zMin = Infinity;
  let zMax = -Infinity;
  const p = f.pos;
  for (let i = 0; i < p.length; i += 3) {
    zMin = Math.min(zMin, p[i + 2]);
    zMax = Math.max(zMax, p[i + 2]);
    if (i) len += Math.hypot(p[i] - p[i - 3], p[i + 1] - p[i - 2], p[i + 2] - p[i - 1]);
  }
  return { len, zMin, zMax };
}

/** One table over every described object: a value, or "different". */
function mergedTags(infos) {
  const keys = new Map(); // key -> Map(value -> count)
  for (const info of infos) {
    for (const [k, v] of Object.entries(info.tags || {})) {
      if (!keys.has(k)) keys.set(k, new Map());
      const values = keys.get(k);
      values.set(v, (values.get(v) || 0) + 1);
    }
  }
  return sortKeys(keys.keys()).map((k) => {
    const values = keys.get(k);
    const count = [...values.values()].reduce((a, b) => a + b, 0);
    return { key: k, values, count, same: values.size === 1 && count === infos.length };
  });
}

/** Relations, merged over the objects: relation -> roles and how many use it. */
function mergedMemberships(infos) {
  const rel = new Map();
  for (const info of infos) {
    for (const m of info.memberships || []) {
      if (!rel.has(m.id)) rel.set(m.id, { id: m.id, tags: m.tags || {}, roles: new Map() });
      const r = rel.get(m.id);
      r.roles.set(m.role || "", (r.roles.get(m.role || "") || 0) + 1);
    }
  }
  return [...rel.values()].sort((a, b) => ((a.tags.type || "") + a.id).localeCompare((b.tags.type || "") + b.id));
}

function describeRelation(tags) {
  const t = tags.type || "relation";
  const st = tags.subtype ? ` ${tags.subtype}` : "";
  return `${t}${st}`;
}

function render() {
  const body = $("inspectBody");
  if (!body) return;
  const tokens = state.tokens;
  const ways = tokens.filter((t) => kindOf(t) === "way").length;
  const nodes = tokens.length - ways;
  $("inspectSummary").textContent = !tokens.length ? "Nothing selected"
    : [nodes ? plural(nodes, "node") : null, ways ? plural(ways, "way") : null].filter(Boolean).join(" · ");
  $("inspectCopy").disabled = !tokens.length || state.loading;
  $("inspectRefresh").disabled = !tokens.length || state.loading;
  if (!tokens.length) {
    body.innerHTML = `<div class="empty">Click a line or a node to see its tags (in View or Edit mode), or select something in JOSM.</div>`;
    return;
  }
  const infos = tokens.map((t) => state.details.get(t)).filter(Boolean);
  const parts = [];

  // Which objects: one header for a single object, a list for several.
  if (tokens.length === 1) {
    const t = tokens[0];
    const info = state.details.get(t);
    const geo = kindOf(t) === "way" ? wayGeometry(t) : null;
    const facts = [];
    if (info && info.nodes !== undefined) facts.push(plural(info.nodes, "node"));
    if (geo) {
      facts.push(`${geo.len.toFixed(1)} m`);
      facts.push(geo.zMax - geo.zMin < 0.005 ? `z ${geo.zMin.toFixed(2)} m` : `z ${geo.zMin.toFixed(2)} … ${geo.zMax.toFixed(2)} m`);
    } else if (kindOf(t) === "node") {
      const rec = store.node(Number(t.slice(5)));
      if (rec) facts.push(`z ${rec.z.toFixed(2)} m`);
    }
    parts.push(`<div class="inspectHead"><span class="inspectId">${escapeHtml(t)}</span>`
      + `<span class="muted">${escapeHtml(facts.join(" · "))}</span></div>`);
    if (info && info.missing) parts.push(`<div class="hint warnText">No longer in JOSM's data.</div>`);
  } else {
    const items = tokens.slice(0, LIST_MAX).map((t) => {
      const info = state.details.get(t);
      const tags = info && info.tags ? info.tags : {};
      const what = [tags.type, tags.subtype].filter(Boolean).join(" ");
      return `<button type="button" class="inspectItem" data-token="${escapeHtml(t)}" title="Select only this one">`
        + `<span class="inspectId">${escapeHtml(t)}</span><span class="muted">${escapeHtml(what)}</span></button>`;
    });
    if (tokens.length > LIST_MAX) items.push(`<div class="hint">and ${tokens.length - LIST_MAX} more</div>`);
    parts.push(`<div class="inspectList">${items.join("")}</div>`);
  }

  // Tags.
  const rows = mergedTags(infos);
  parts.push(`<div class="subHead">Tags${state.loading ? ' <span class="spinner"></span>' : ""}</div>`);
  if (!rows.length) {
    parts.push(`<div class="hint">${state.loading ? "Asking JOSM…" : "No tags."}</div>`);
  } else {
    parts.push('<table class="tagTable"><tbody>');
    for (const r of rows) {
      let value;
      if (r.same) value = escapeHtml([...r.values.keys()][0]);
      else {
        const list = [...r.values.entries()].map(([v, n]) => `${v} (${n})`).join("\n");
        const firstOnly = r.values.size === 1 ? escapeHtml([...r.values.keys()][0]) : `${r.values.size} values`;
        value = `<span class="different" title="${escapeHtml(list)}">${firstOnly}</span>`
          + (r.count < infos.length ? ` <span class="muted">on ${r.count} of ${infos.length}</span>` : "");
      }
      parts.push(`<tr><th>${escapeHtml(r.key)}</th><td>${value}</td></tr>`);
    }
    parts.push("</tbody></table>");
  }

  // Relations.
  const rels = mergedMemberships(infos);
  if (rels.length || state.source === "josm") {
    parts.push('<div class="subHead">Member of</div>');
    if (!rels.length) parts.push('<div class="hint">No relation.</div>');
    for (const r of rels) {
      const roles = [...r.roles.entries()].map(([role, n]) => (role || "(no role)")
        + (infos.length > 1 ? ` ×${n}` : "")).join(", ");
      const extra = Object.entries(r.tags).filter(([k]) => k !== "type" && k !== "subtype")
        .map(([k, v]) => `${k}=${v}`).join("\n");
      parts.push(`<div class="memberRow" title="${escapeHtml(extra)}"><span class="role">${escapeHtml(roles)}</span>`
        + `<span>${escapeHtml(describeRelation(r.tags))}</span><span class="muted inspectId">${escapeHtml(r.id)}</span></div>`);
    }
  }
  if (state.note) parts.push(`<div class="hint warnText">${escapeHtml(state.note)}</div>`);
  else if (state.source === "josm" && store.mapSource === "scene_server") {
    parts.push('<div class="hint">From the scene server\'s lanelet2 map (read-only).</div>');
  } else if (state.source === "josm") {
    parts.push('<div class="hint">From JOSM. Tags edited there show here once you come back to this window.</div>');
  }
  body.innerHTML = parts.join("");
}

/** The tags shared by every described object, as `key=value` lines. */
export function tagsAsText() {
  const infos = state.tokens.map((t) => state.details.get(t)).filter(Boolean);
  return mergedTags(infos).filter((r) => r.same).map((r) => `${r.key}=${[...r.values.keys()][0]}`).join("\n");
}

export function installInspector() {
  $("inspectRefresh")?.addEventListener("click", () => refresh());
  $("inspectCopy")?.addEventListener("click", () => {
    const text = tagsAsText();
    const done = () => toast(`Copied ${text ? text.split("\n").length : 0} tag(s)`, "info", 1500);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, () => toast("The browser refused the clipboard", "warn"));
    } else {
      toast("No clipboard in this browser", "warn");
    }
  });
  $("inspectBody")?.addEventListener("click", (e) => {
    const item = e.target.closest("[data-token]");
    if (!item) return;
    const t = item.dataset.token;
    const kind = kindOf(t);
    selection.set([{ type: kind, id: kind === "node" ? Number(t.slice(5)) : t }]);
  });
  selection.on("changed", () => schedule());
  editEvents.on("mode", () => schedule());
  // A selected way changed in the stream (geometry, type or subtype).
  store.on("upsert", (f) => {
    if (state.key && state.tokens.includes(f.id)) schedule(true);
  });
  window.addEventListener("focus", () => { if (state.tokens.length) schedule(true); });
  ui.on("tab", (name) => { if (name === "inspect" && (state.stale || inspectedTokens().join(",") !== state.key)) refresh(); });
  ui.on("panel", (open) => { if (open && currentTab() === "inspect" && state.stale) refresh(); });
  render();
}

if (TEST_HOOKS) {
  window.__ll2inspect = {
    state: () => ({ tokens: [...state.tokens], source: state.source, note: state.note, loading: state.loading,
                    details: Object.fromEntries(state.details), text: tagsAsText(),
                    editOn: edit.on }),
    refresh: () => refresh(),
  };
}
