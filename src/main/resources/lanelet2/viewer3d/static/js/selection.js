// Selection: nodes (numeric JOSM unique ids) and ways (feature ids "way/<id>").
//
// A selected way stands for all its nodes when moving. Changes the user makes
// here are mirrored to JOSM (`select`); JOSM's own selection arrives as a
// `selection` message and is applied without echoing it back.
import { Emitter } from "./util.js";
import { store, nodeToken } from "./store.js";
import { sendCommand } from "./net.js";

const SYNC_DEBOUNCE_MS = 80;

class Selection extends Emitter {
  constructor() {
    super();
    this.nodes = new Set();
    this.ways = new Set();
    this._syncTimer = null;
    this.syncToJosm = true;
  }

  isEmpty() {
    return this.nodes.size === 0 && this.ways.size === 0;
  }

  size() {
    return this.nodes.size + this.ways.size;
  }

  has(item) {
    return item.type === "node" ? this.nodes.has(item.id) : this.ways.has(item.id);
  }

  /** The one selected item, or null when zero or several are selected. */
  single() {
    if (this.size() !== 1) return null;
    if (this.nodes.size) return { type: "node", id: this.nodes.values().next().value };
    return { type: "way", id: this.ways.values().next().value };
  }

  /** Replace the selection with `items` ([{type, id}]). */
  set(items, opts = {}) {
    this.nodes.clear();
    this.ways.clear();
    for (const it of items) this._add(it);
    this._changed(opts);
  }

  add(items, opts = {}) {
    for (const it of items) this._add(it);
    this._changed(opts);
  }

  toggle(item, opts = {}) {
    const set = item.type === "node" ? this.nodes : this.ways;
    if (set.has(item.id)) set.delete(item.id);
    else set.add(item.id);
    this._changed(opts);
  }

  clear(opts = {}) {
    if (this.isEmpty()) return;
    this.nodes.clear();
    this.ways.clear();
    this._changed(opts);
  }

  _add(it) {
    if (it.type === "node") this.nodes.add(it.id);
    else if (it.type === "way") this.ways.add(it.id);
  }

  /** JOSM selection tokens ("node/1", "way/2"). */
  tokens() {
    const out = [];
    for (const id of this.nodes) out.push(nodeToken(id));
    for (const id of this.ways) out.push(id);
    return out;
  }

  /**
   * Node ids a move acts on: the selected nodes plus every node of the
   * selected ways that the viewer has (culled ways are simply absent).
   */
  effectiveNodeIds() {
    const out = new Set(this.nodes);
    for (const wid of this.ways) {
      const f = store.features.get(wid);
      if (f) for (const n of f.nodes) out.add(n);
    }
    return out;
  }

  /** Apply JOSM's selection message without sending it back. */
  applyFromJosm(msg) {
    // JOSM's selection is newer than any change still waiting to be sent;
    // sending that now would echo JOSM's own selection back (or undo it).
    if (this._syncTimer !== null) {
      clearTimeout(this._syncTimer);
      this._syncTimer = null;
    }
    const items = [];
    for (const n of msg.nodes || []) items.push({ type: "node", id: Number(n) });
    for (const w of msg.ways || []) items.push({ type: "way", id: String(w) });
    const same = items.length === this.size() && items.every((it) => this.has(it));
    if (same) return;
    this.set(items, { fromJosm: true });
  }

  _changed(opts) {
    this.emit("changed", { fromJosm: !!opts.fromJosm });
    if (!opts.fromJosm && this.syncToJosm) this._scheduleSync();
  }

  _scheduleSync() {
    if (this._syncTimer !== null) clearTimeout(this._syncTimer);
    this._syncTimer = setTimeout(() => {
      this._syncTimer = null;
      sendCommand([{ op: "select", ids: this.tokens() }]);
    }, SYNC_DEBOUNCE_MS);
  }
}

export const selection = new Selection();
