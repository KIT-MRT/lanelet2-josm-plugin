#!/usr/bin/env python3
"""Generate the golden `.notes` fixture for NotesFormatTest.

This is a line-faithful replica of `notes_core.serialize` from
`/ll2_tooling_root/JOSM_lanelet2_editing_scripts/core/notes/notes_core.py`.
The original imports JOSM and cannot run under python3 as a module, so the
writer functions (`_esc`, `_fmt_coord`, `_sorted_keys`, `serialize`) are
copied here verbatim. The fixture dataset is the same one NotesFormatTest
feeds the Kotlin port (positive ids, a `-7` unique id, a tagless node,
deleted/incomplete primitives that must be omitted, and XML-special text).

Regenerate only if the Jython writer changes:

    python3 testdata/notes/gen_notes_fixture.py

A rerun must reproduce `jython_serialize_fixture.notes` byte-for-byte
(UTF-8, Unix newlines, trailing newline). The `-7 -> 5` remap is the
Jython rule: `max(positive ids, id_map values, 0) + 1`.
"""

from __future__ import print_function

import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "jython_serialize_fixture.notes")


# --- notes_core.py: _esc / _fmt_coord / _sorted_keys / serialize -----------
def _esc(s):
    s = str(s)
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "&apos;").replace('"', "&quot;")
    s = s.replace("\r", "&#13;").replace("\n", "&#10;").replace("\t", "&#9;")
    return s


def _fmt_coord(v):
    return "%.9f" % float(v)


def _sorted_keys(prim):
    try:
        keys = prim.getKeys()
        return sorted([str(k) for k in keys.keySet()])
    except Exception:
        return []


def serialize(dataset, id_map):
    """Serialize the notes dataset to OSM XML with positive ids.

    id_map (uniqueId -> assigned positive id) persists across saves so ids stay
    stable within a session (no diff churn). Existing positive ids are kept.
    """
    nodes = [n for n in dataset.getNodes()
             if n and not n.isDeleted() and not n.isIncomplete()]
    ways = [w for w in dataset.getWays()
            if w and not w.isDeleted() and not w.isIncomplete()]

    max_id = 0
    for p in nodes + ways:
        uid = p.getUniqueId()
        if uid > max_id:
            max_id = uid
    for v in id_map.values():
        if v > max_id:
            max_id = v
    counter = [max_id]

    def pid(prim):
        uid = prim.getUniqueId()
        if uid in id_map:
            return id_map[uid]
        if uid > 0:
            id_map[uid] = uid
            return uid
        counter[0] += 1
        id_map[uid] = counter[0]
        return counter[0]

    node_pid = {}
    for n in nodes:
        node_pid[n.getUniqueId()] = pid(n)

    lines = [
        "<?xml version='1.0' encoding='UTF-8'?>",
        "<osm version='0.6' generator='lanelet2_notes'>",
    ]

    for n in sorted(nodes, key=lambda x: node_pid[x.getUniqueId()]):
        c = n.getCoor()
        head = "  <node id='%d' visible='true' version='1' lat='%s' lon='%s'" % (
            node_pid[n.getUniqueId()], _fmt_coord(c.lat()), _fmt_coord(c.lon()))
        keys = _sorted_keys(n)
        if not keys:
            lines.append(head + " />")
            continue
        lines.append(head + ">")
        for k in keys:
            lines.append("    <tag k='%s' v='%s' />" % (_esc(k), _esc(n.get(k))))
        lines.append("  </node>")

    for w in sorted(ways, key=lambda x: pid(x)):
        wid = pid(w)
        lines.append("  <way id='%d' visible='true' version='1'>" % wid)
        for nd in w.getNodes():
            lines.append("    <nd ref='%d' />" % node_pid[nd.getUniqueId()])
        for k in _sorted_keys(w):
            lines.append("    <tag k='%s' v='%s' />" % (_esc(k), _esc(w.get(k))))
        lines.append("  </way>")

    lines.append("</osm>")
    return "\n".join(lines) + "\n"


# --- stand-in primitives (JOSM Node/Way/DataSet surface used by serialize) -
class _Keys(object):
    def __init__(self, tags):
        self._tags = tags

    def keySet(self):
        return self._tags.keys()


class _Coor(object):
    def __init__(self, lat, lon):
        self._lat = lat
        self._lon = lon

    def lat(self):
        return self._lat

    def lon(self):
        return self._lon


class FakePrim(object):
    def __init__(self, uid, **kw):
        self._uid = uid
        self._deleted = kw.get("deleted", False)
        self._incomplete = kw.get("incomplete", False)
        self._tags = kw.get("tags", {})
        self._lat = kw.get("lat")
        self._lon = kw.get("lon")
        self._nodes = kw.get("nodes", [])

    def getUniqueId(self):
        return self._uid

    def isDeleted(self):
        return self._deleted

    def isIncomplete(self):
        return self._incomplete

    def getKeys(self):
        return _Keys(self._tags)

    def get(self, k):
        return self._tags.get(k)

    def getCoor(self):
        return _Coor(self._lat, self._lon)

    def getNodes(self):
        return self._nodes


class FakeDataset(object):
    def __init__(self, nodes, ways):
        self._nodes = nodes
        self._ways = ways

    def getNodes(self):
        return self._nodes

    def getWays(self):
        return self._ways


def fixture_dataset():
    """The corpus NotesFormatTest serializes. Do not change without updating
    both the committed `.notes` file and the Kotlin test records."""
    n1 = FakePrim(1, lat=49.012345678, lon=8.400000001, tags={
        "ll2_note": "yes",
        "note_anchor": "yes",
        "note_author": "tester",
        "note_created": "2024-01-02T03:04:05",
        "note_done": "no",
        "note_id": "abcd1234",
        "note_refs": "w123 n456",
        "note_severity": "major",
        "note_text": "Hello & <world>\nline2\t'quote' \"q\"",
        "note_type": "issue",
    })
    n2 = FakePrim(2, lat=49.0, lon=8.4)
    n3 = FakePrim(3, lat=49.000001, lon=8.400001)
    n_neg = FakePrim(-7, lat=48.5, lon=8.25, tags={
        "note_member": "yes",
        "note_id": "abcd1234",
        "note_type": "issue",
        "note_severity": "major",
        "note_done": "no",
    })
    n_del = FakePrim(99, lat=1, lon=2, deleted=True, tags={"ll2_note": "yes"})
    n_inc = FakePrim(100, lat=1, lon=2, incomplete=True, tags={"ll2_note": "yes"})
    w4 = FakePrim(4, nodes=[n2, n3], tags={
        "ll2_note": "yes",
        "note_id": "abcd1234",
        "note_type": "issue",
        "note_severity": "major",
        "note_done": "no",
    })
    return FakeDataset([n1, n2, n3, n_neg, n_del, n_inc], [w4])


def main():
    text = serialize(fixture_dataset(), {})
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("wrote %s (%d bytes)" % (OUT, len(text.encode("utf-8"))))


if __name__ == "__main__":
    main()
