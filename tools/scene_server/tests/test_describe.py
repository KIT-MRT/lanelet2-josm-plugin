"""The scene server's answer to the viewer's `describe` op.

A fake map with the shape of lanelet2's Python objects (layers with
`exists`, item access and `findUsages`; primitives with `id` and
`attributes`; lanelets with bounds; regulatory elements with parameters).
Checked against the real bindings by hand; this pins the logic.
"""

import unittest

from ll2_scene.bridge import ViewerBridge
from ll2_scene.describe import MAX_DESCRIBE, describe, make_command_handler


class Prim:
    def __init__(self, pid, attrs=None, points=()):
        self.id = pid
        self.attributes = dict(attrs or {})
        self._points = list(points)

    def __len__(self):
        return len(self._points)

    def __iter__(self):
        return iter(self._points)


class Lanelet(Prim):
    def __init__(self, pid, attrs, left, right):
        super().__init__(pid, attrs)
        self.leftBound = left
        self.rightBound = right


class RegElem(Prim):
    def __init__(self, pid, attrs, parameters):
        super().__init__(pid, attrs)
        self.parameters = parameters


class Layer:
    def __init__(self, items, usages=None):
        self._items = {p.id: p for p in items}
        self._usages = usages or (lambda prim: [])

    def exists(self, pid):
        return pid in self._items

    def __getitem__(self, pid):
        return self._items[pid]

    def findUsages(self, prim):
        return self._usages(prim)


def fake_map():
    p1 = Prim(101, {"ele": "110.5"})
    p2 = Prim(102)
    left = Prim(11, {"type": "line_thin", "subtype": "dashed", "file_origin": "/maps/a.osm"}, [p1, p2])
    right = Prim(12, {"type": "curbstone", "subtype": "high"}, [p2, p1])
    sign = Prim(13, {"type": "traffic_sign", "subtype": "de205"}, [p1, p2])
    ll7 = Lanelet(7, {"subtype": "road", "one_way": "yes"}, left, right)
    # Line 11 is the right bound of lanelet 8 as well: two memberships.
    ll8 = Lanelet(8, {"subtype": "road"}, Prim(14), left)
    re9 = RegElem(9, {"type": "regulatory_element", "subtype": "right_of_way"},
                  {"refers": [sign], "ref_line": [left]})
    re10 = RegElem(10, {"subtype": "traffic_sign"}, {"refers": [p1]})
    lanelets = [ll7, ll8]
    regelems = [re9, re10]

    def lanelet_usages(prim):
        return [ll for ll in lanelets if prim.id in (ll.leftBound.id, ll.rightBound.id)]

    def regelem_usages(prim):
        return [r for r in regelems if any(v.id == prim.id for vs in r.parameters.values() for v in vs)]

    class Map:
        pass

    m = Map()
    m.pointLayer = Layer([p1, p2])
    m.lineStringLayer = Layer([left, right, sign])
    m.laneletLayer = Layer(lanelets, lanelet_usages)
    m.regulatoryElementLayer = Layer(regelems, regelem_usages)
    return m


class DescribeTest(unittest.TestCase):
    def setUp(self):
        self.map = fake_map()

    def test_a_line_gets_every_attribute_and_its_relations(self):
        [d] = describe(self.map, ["way/11"])
        self.assertEqual({"file_origin": "/maps/a.osm", "subtype": "dashed", "type": "line_thin"}, d["tags"])
        self.assertEqual(["file_origin", "subtype", "type"], list(d["tags"]))
        self.assertEqual(2, d["nodes"])
        self.assertNotIn("missing", d)
        got = [(m["id"], m["role"], m["tags"].get("type")) for m in d["memberships"]]
        self.assertEqual([("relation/7", "left", "lanelet"), ("relation/8", "right", "lanelet"),
                          ("relation/9", "ref_line", "regulatory_element")], got)
        # A lanelet's own attributes, with the relation type the stream shows.
        self.assertEqual({"one_way": "yes", "subtype": "road", "type": "lanelet"}, d["memberships"][0]["tags"])

    def test_a_sign_line_is_referred_to_by_its_regulatory_element(self):
        [d] = describe(self.map, ["way/13"])
        self.assertEqual([("relation/9", "refers")], [(m["id"], m["role"]) for m in d["memberships"]])

    def test_a_point_gets_its_attributes_and_regulatory_elements(self):
        [d] = describe(self.map, ["node/101"])
        self.assertEqual({"ele": "110.5"}, d["tags"])
        self.assertNotIn("nodes", d)
        self.assertEqual([("relation/10", "refers", "regulatory_element")],
                         [(m["id"], m["role"], m["tags"]["type"]) for m in d["memberships"]])

    def test_a_relation_id_finds_the_lanelet(self):
        [d] = describe(self.map, ["relation/7"])
        self.assertEqual("lanelet", d["tags"]["type"])
        self.assertEqual("road", d["tags"]["subtype"])

    def test_missing_malformed_duplicate_and_capped(self):
        out = describe(self.map, ["way/999", "way/x", "bogus/1", "way/11", "way/11"])
        self.assertEqual(["way/999", "way/x", "bogus/1", "way/11"], [d["id"] for d in out])
        self.assertTrue(out[0]["missing"] and out[1]["missing"] and out[2]["missing"])
        self.assertEqual({}, out[0]["tags"])
        many = describe(self.map, ["node/%d" % i for i in range(MAX_DESCRIBE + 20)])
        self.assertEqual(MAX_DESCRIBE, len(many))

    def test_a_binding_without_an_overload_costs_the_membership_not_the_answer(self):
        def broken(prim):
            raise TypeError("No registered converter")
        self.map.regulatoryElementLayer._usages = broken
        [d] = describe(self.map, ["way/11"])
        self.assertEqual(["relation/7", "relation/8"], [m["id"] for m in d["memberships"]])


class HandlerTest(unittest.TestCase):
    def test_answers_describe_from_its_own_map(self):
        handle = make_command_handler(lambda: fake_map())
        reply = handle({"type": "command", "id": "c1", "ops": [{"op": "describe", "ids": ["way/12"]}]})
        self.assertTrue(reply["ok"])
        self.assertEqual("curbstone", reply["details"][0]["tags"]["type"])

    def test_stays_silent_when_josm_owns_the_map(self):
        handle = make_command_handler(lambda: False)
        self.assertIs(False, handle({"ops": [{"op": "describe", "ids": ["way/1"]}]}))

    def test_says_so_without_a_map(self):
        handle = make_command_handler(lambda: None)
        reply = handle({"ops": [{"op": "describe", "ids": ["way/1"]}]})
        self.assertFalse(reply["ok"])

    def test_anything_else_goes_to_the_default(self):
        handle = make_command_handler(lambda: fake_map())
        self.assertIsNone(handle({"ops": [{"op": "move_node", "id": "node/1", "z": 1}]}))
        self.assertIsNone(handle({"ops": [{"op": "describe", "ids": []}, {"op": "select", "ids": []}]}))

    def test_the_bridge_sends_the_answer_or_nothing(self):
        sent = []
        bridge = ViewerBridge(on_command=make_command_handler(lambda: fake_map()), log=lambda m: None)
        bridge.send = sent.append
        bridge._handle(b'{"type":"command","id":"c9","ops":[{"op":"describe","ids":["way/11"]}]}')
        self.assertEqual(1, len(sent))
        self.assertEqual(("command_result", "c9", True), (sent[0]["type"], sent[0]["id"], sent[0]["ok"]))
        self.assertEqual(3, len(sent[0]["details"][0]["memberships"]))
        silent = ViewerBridge(on_command=make_command_handler(lambda: False), log=lambda m: None)
        silent.send = sent.append
        silent._handle(b'{"type":"command","id":"c10","ops":[{"op":"describe","ids":["way/11"]}]}')
        self.assertEqual(1, len(sent), "JOSM owns the map: no reply from the scene server")


if __name__ == "__main__":
    unittest.main()
