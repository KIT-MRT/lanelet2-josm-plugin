"""Answer the viewer's read-only `describe` op from the loaded Lanelet2 map.

The viewer's Selection tab asks JOSM for every tag of the selected objects
and the relations they belong to (the map stream carries only type and
subtype). With the scene server's own read-only map there is no JOSM, so the
scene server answers in the same shape, from the lanelet2 map it sent:

  {"id": "way/12", "tags": {...}, "nodes": 4,
   "memberships": [{"id": "relation/7", "role": "left", "tags": {...}}]}

Ids are the ones the map feed uses (`mapfeed.MapConverter`): line strings
are ways, points are nodes, lanelets (and regulatory elements, areas) are
relations. Memberships come from lanelet2's `findUsages`: the lanelets a line
bounds (role left / right), the areas it outlines (outer / inner) and the
regulatory elements that reference it, in the role their parameters give.

Everything is looked up defensively: the lanelet2 bindings differ a little
between versions in which layer offers which `findUsages`, and a missing
lookup must cost a membership, never the answer.
"""

from .mapfeed import _attrs

MAX_DESCRIBE = 500  # as Viewer3dConstants.MAX_DESCRIBE


def _get(layer, pid):
    try:
        exists = getattr(layer, "exists", None)
        if exists is not None and not exists(pid):
            return None
        return layer[pid]
    except (KeyError, IndexError, RuntimeError, TypeError, ValueError):
        return None


def _usages(layer, prim):
    try:
        return list(layer.findUsages(prim))
    except Exception:  # noqa: BLE001 -- an overload this binding lacks
        return []


def _id(obj):
    try:
        return int(obj.id)
    except (AttributeError, TypeError, ValueError):
        return None


def _sorted(tags):
    return {k: tags[k] for k in sorted(tags)}


def _relation_tags(obj, default_type):
    tags = dict(_attrs(obj))
    tags.setdefault("type", default_type)
    return _sorted(tags)


def _parameter_roles(regelem, pid):
    """The roles in which a regulatory element's parameters name `pid`."""
    roles = []
    try:
        params = regelem.parameters
        items = params.items() if hasattr(params, "items") else [(k, params[k]) for k in params]
    except Exception:  # noqa: BLE001
        return roles
    for role, values in items:
        try:
            if any(_id(v) == pid for v in values):
                roles.append(str(role))
        except TypeError:
            if _id(values) == pid:
                roles.append(str(role))
    return roles


def _line_memberships(lanelet_map, line, pid):
    out = []
    for ll in _usages(lanelet_map.laneletLayer, line):
        tags = _relation_tags(ll, "lanelet")
        for role, bound in (("left", "leftBound"), ("right", "rightBound")):
            if _id(getattr(ll, bound, None)) == pid:
                out.append(("relation/%d" % ll.id, role, tags))
    area_layer = getattr(lanelet_map, "areaLayer", None)
    for area in _usages(area_layer, line) if area_layer is not None else []:
        tags = _relation_tags(area, "multipolygon")
        outer = getattr(area, "outerBound", []) or []
        inner = [ls for ring in (getattr(area, "innerBounds", []) or []) for ls in ring]
        if any(_id(ls) == pid for ls in outer):
            out.append(("relation/%d" % area.id, "outer", tags))
        if any(_id(ls) == pid for ls in inner):
            out.append(("relation/%d" % area.id, "inner", tags))
    out += _regelem_memberships(lanelet_map, line, pid)
    return out


def _regelem_memberships(lanelet_map, prim, pid):
    out = []
    layer = getattr(lanelet_map, "regulatoryElementLayer", None)
    if layer is None:
        return out
    for re_ in _usages(layer, prim):
        tags = _relation_tags(re_, "regulatory_element")
        for role in _parameter_roles(re_, pid) or [""]:
            out.append(("relation/%d" % re_.id, role, tags))
    return out


def _find_relation(lanelet_map, pid):
    for name, default_type in (("laneletLayer", "lanelet"), ("regulatoryElementLayer", "regulatory_element"),
                               ("areaLayer", "multipolygon")):
        layer = getattr(lanelet_map, name, None)
        obj = _get(layer, pid) if layer is not None else None
        if obj is not None:
            return obj, default_type
    return None, None


def describe(lanelet_map, ids):
    """One entry per id (at most MAX_DESCRIBE, duplicates once), in order."""
    details = []
    for token in list(dict.fromkeys(str(i) for i in ids))[:MAX_DESCRIBE]:
        kind, _, num = token.partition("/")
        try:
            pid = int(num)
        except ValueError:
            details.append({"id": token, "missing": True, "tags": {}, "memberships": []})
            continue
        entry = {"id": token, "tags": {}, "memberships": []}
        memberships = []
        if kind == "way":
            prim = _get(lanelet_map.lineStringLayer, pid)
            if prim is not None:
                entry["tags"] = _sorted(_attrs(prim))
                try:
                    entry["nodes"] = len(prim)
                except TypeError:
                    pass
                memberships = _line_memberships(lanelet_map, prim, pid)
        elif kind == "node":
            prim = _get(lanelet_map.pointLayer, pid)
            if prim is not None:
                entry["tags"] = _sorted(_attrs(prim))
                memberships = _regelem_memberships(lanelet_map, prim, pid)
        elif kind == "relation":
            prim, default_type = _find_relation(lanelet_map, pid)
            if prim is not None:
                entry["tags"] = _relation_tags(prim, default_type)
                memberships = _regelem_memberships(lanelet_map, prim, pid)
        else:
            prim = None
        if prim is None:
            entry["missing"] = True
        memberships.sort(key=lambda m: (m[2].get("type", ""), m[0], m[1]))
        entry["memberships"] = [{"id": i, "role": r, "tags": t} for i, r, t in memberships]
        details.append(entry)
    return details


def make_command_handler(map_owner, log=print):
    """`on_command` for the bridge: answers describe-only commands.

    `map_owner()` returns the lanelet2 map when this server sent the viewer's
    map, else None. When another bridge (JOSM) streams the map, a describe is
    not ours to answer: returning False sends no reply at all, so JOSM's is
    the one the viewer gets. Anything else falls through to the bridge's
    default (accept camera/selection traffic, refuse edits).
    """
    def handle(command):
        ops = command.get("ops")
        if not isinstance(ops, list) or not ops:
            return None
        if not all(isinstance(op, dict) and op.get("op") == "describe" for op in ops):
            return None
        state = map_owner()
        if state is False:
            return False
        if state is None:
            return {"ok": False, "message": "The scene server has no map loaded"}
        ids = [i for op in ops for i in (op.get("ids") or []) if isinstance(i, (str, int))]
        details = describe(state, ids)
        return {"ok": True, "message": "Described %d object(s) from the scene server's map" % len(details),
                "details": details}
    return handle
