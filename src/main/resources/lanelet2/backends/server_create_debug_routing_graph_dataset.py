#!/usr/bin/env python

import lanelet2
import argparse
import os
import time
import subprocess
import signal
from urllib.parse import quote

import sys
# NOTE: `requests` is imported lazily inside loop_and_process() so that the
# JOSM `--once` code path (generate_debug_routing_graph only) does not require
# the requests package to be installed.

sys.stdout.reconfigure(line_buffering=True)


def _get_validate_output_path():
    """Path for lanelet2 validation log. Uses LL2_VALIDATE_OUTPUT, LL2_OUTPUT_DIR, or LL2_TOOLING_ROOT."""
    path = os.environ.get("LL2_VALIDATE_OUTPUT", "").strip()
    if path:
        return os.path.expanduser(path)
    out_root = os.environ.get("LL2_OUTPUT_DIR", "").strip()
    if out_root:
        return os.path.join(os.path.expanduser(out_root), "lanelet2_validate_output.txt")
    root = os.environ.get("LL2_TOOLING_ROOT", "").strip()
    if root:
        return os.path.join(os.path.expanduser(root), "lanelet2_validate_output.txt")
    return os.path.expanduser("~/lanelet2_validate_output.txt")


def _to_josm_path(path):
    """Convert path for JOSM (host). When LL2_JOSM_PATH_PREFIX is set, replace tooling root with host prefix."""
    if not path:
        return path
    path = os.path.normpath(os.path.expanduser(path))
    prefix = os.environ.get("LL2_JOSM_PATH_PREFIX", "").strip()
    root = os.environ.get("LL2_TOOLING_ROOT", "").strip()
    if prefix and root:
        root_norm = os.path.normpath(os.path.expanduser(root))
        if path == root_norm or path.startswith(root_norm + os.sep):
            suffix = path[len(root_norm):].lstrip(os.sep)
            return os.path.join(prefix.rstrip(os.sep), suffix) if suffix else prefix.rstrip(os.sep)
    return path

def loop_and_process(args):

    import requests  # lazy import: only needed for the JOSM remote-control loop
    timestamp = 0
    if not os.path.exists(args.input):
        print("Waiting for input at {}".format(args.input))
        with open(args.input, "w") as f:
            pass
        timestamp = os.path.getmtime(args.input)
    while True:
        new_timestamp = os.path.getmtime(args.input)
        if new_timestamp == timestamp:
            # sleep and try again later
            time.sleep(1)
            continue
        print(f"running lanelet2 validation on {args.input}")
        if args.validate:
            run_ll2_validation(args)
        write_errors = generate_debug_routing_graph(args)
        if write_errors == "wrong_file":
            break
        if os.path.exists(args.output):
            try:
                josm_path = _to_josm_path(args.output)
                res = requests.get("http://localhost:8111/open_file?filename={}".format(quote(josm_path, safe="")))
            except Exception as e:
                print("Failed to run JOSM. Did start it and configure it correctly? ({})".format(e))
        timestamp = new_timestamp


def run_ll2_validation(args, grep_filter_lines_with='"error|segmentation"', timeout=5):
    # runs ll2 validation and outputs only errors and segfaults, but keeps a log file for inspection
    # but first load ll2 map robust to print loading errors before segfaulting potentially
    proj = lanelet2.projection.UtmProjector(lanelet2.io.Origin(args.lat, args.lon))
    print(f"loading .osm file for routing: {args.input}")
    laneletmap, loading_errors = lanelet2.io.loadRobust(args.input, proj)
    if loading_errors:
        print(f"LOAD ERRORS, following errors while loading the ll2 map {args.input}:")
        print("\n".join(loading_errors))

    validate_log = _get_validate_output_path()
    cmd = (
        f"lanelet2_validate "
        f"{args.input} --lat {args.lat} --lon {args.lon}"
        f" 2>&1 | tee {validate_log} | grep -iE {grep_filter_lines_with}"
    )
    print ("Running standard Lanelet2 validation:")
    print(cmd)
    try:

        proc_result = subprocess.run(cmd, shell=True, check=False) #stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True
        if proc_result.returncode != 0: # shell return code when shell subprocess segfaults (128+11) 11 is segfault
            print(proc_result.stdout)
            print(proc_result.stderr)
            print("!"*140)
            print("ERROR: Lanelet2 validation crashed")
            print("!"*140)
            print(proc_result)
        else:
            print("Lanelet2 validation ran successfully.")
    except Exception as e:
        print(f"an error occured: {e}")

def generate_debug_routing_graph(args):
    rules_map = {"vehicle": lanelet2.traffic_rules.Participants.Vehicle,
                 "bicycle": lanelet2.traffic_rules.Participants.Bicycle,
                 "pedestrian": lanelet2.traffic_rules.Participants.Pedestrian,
                 "train": lanelet2.traffic_rules.Participants.Train}
    proj = lanelet2.projection.UtmProjector(lanelet2.io.Origin(args.lat, args.lon))
    print(f"loading .osm file for routing: {args.input}")
    laneletmap, loading_errors = lanelet2.io.loadRobust(args.input, proj)
    if loading_errors:
        print(f"LOAD WARNING, errors while loading the ll2 map {args.input}:")
        print("debug routing graph may be incomplete!")

    no_of_lanelets = len(laneletmap.laneletLayer)
    if no_of_lanelets <= 1:
        print("ERROR: not a map with lanelets! select a proper ll2 map .osm file as input")
        return "wrong_file"

    print("generate routing cost")
    routing_cost = lanelet2.routing.RoutingCostDistance(0.)  # zero cost for lane changes
    print("generate traffic rules")
    traffic_rules = lanelet2.traffic_rules.create(lanelet2.traffic_rules.Locations.Germany,
                                                  rules_map[args.participant])
    print("generate routing graph")
    graph = lanelet2.routing.RoutingGraph(laneletmap, traffic_rules, [routing_cost])
    print("generate routing debug map ")
    debug_map = graph.getDebugLaneletMap()
    print("add more info to debug map lines and nodes")
    # Create mapping from lanelet ID to lanelet point representation in debug map
    debug_lanelets = {pt.id: pt for pt in debug_map.pointLayer}

    for point in debug_map.pointLayer:
        point.attributes['type'] = "routing_info"
        point.attributes['subtype'] = "lanelet_center_point"

        # 2. Second pass: Add all linestrings with updated point references
    for line in debug_map.lineStringLayer:
        line.attributes['type'] = "routing_info"
        line.attributes['subtype'] = "routing_connection"
    print("add oneway info to nodes")
    # Add one_way attribute to check for routing directions
    for ll in laneletmap.laneletLayer:
        is_one_way = traffic_rules.isOneWay(ll)
        debug_ll_point = debug_lanelets.get(ll.id)
        lanelet_type = ll.attributes['subtype']
        if debug_ll_point is not None:
            debug_ll_point.attributes["one_way"] = "yes" if is_one_way else "no"
            debug_ll_point.attributes["lanelet_subtype"] = lanelet_type
            debug_ll_point.attributes["lanelet_id"] = str(ll.id)
            # check if location attribute is present and add it to the point
            if "location" in ll.attributes:
                debug_ll_point.attributes["location"] = ll.attributes["location"]
            else:
                debug_ll_point.attributes["location"] = "missing tag"
            

    # find connecting ways with the same lanelet_subtype in the node and add attribute to way
    point_id_to_subtype = {}
    point_id_to_location = {}
    for pt in debug_map.pointLayer:
        subtype = pt.attributes["lanelet_subtype"] if "lanelet_subtype" in pt.attributes else ""
        if subtype:
            point_id_to_subtype[pt.id] = subtype
        loc = pt.attributes["location"] if "location" in pt.attributes else ""
        if loc:
            point_id_to_location[pt.id] = loc
    for line in debug_map.lineStringLayer:
        if len(line) >= 2:
            id_a, id_b = line[0].id, line[1].id
            subtype_a = point_id_to_subtype.get(id_a, "")
            subtype_b = point_id_to_subtype.get(id_b, "")
            if subtype_a and subtype_b and subtype_a == subtype_b:
                line.attributes["same_subtype"] = "yes"
                line.attributes["lanelet_subtype"] = subtype_a
                if laneletmap.laneletLayer.exists(id_a):
                    ll_a = laneletmap.laneletLayer[id_a]
                    line.attributes["one_way_a"] = "yes" if traffic_rules.isOneWay(ll_a) else "no"
                if laneletmap.laneletLayer.exists(id_b):
                    ll_b = laneletmap.laneletLayer[id_b]
                    line.attributes["one_way_b"] = "yes" if traffic_rules.isOneWay(ll_b) else "no"
            else:
                line.attributes["same_subtype"] = "no"
            # location for MapCSS styling (dashed when nonurban)
            loc_a = point_id_to_location.get(id_a, "urban")
            loc_b = point_id_to_location.get(id_b, "urban")
            line.attributes["location"] = loc_a if loc_a == loc_b else "mixed"
            # oneway=yes for JOSM arrow rendering (routing connections are directed)
            line.attributes["oneway"] = "yes"

    print("write debug map to osm file")
    write_errors = lanelet2.io.writeRobust(args.output, debug_map, proj)
    if write_errors:
        print("WRITE WARNING, errors while writing the debug routing graph .osm :")
        print(write_errors)
        print("debug routing graph may be incomplete!")
    else:
        print(f"Wrote routing graph to file: {args.output}")

    return write_errors



if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input", help="Path to the input ll2 osm file")
    parser.add_argument("output", help="Path to resulting debug routing graph osm file")
    parser.add_argument(
        "--participant",
        help="traffic participant type (one of vehicle, bicycle, pedestrian, train",
        type=str,
        default="vehicle")
    parser.add_argument('--validate', help='Enable validation', type=bool, default=True)
    parser.add_argument("--lat", help="Lateral position of origin", type=float, default=49)
    parser.add_argument("--lon", help="Longitudinal position of origin", type=float, default=8)
    parser.add_argument(
        "--once",
        help="Run once and exit (no JOSM API, no file watch). For JOSM scripting plugin.",
        action="store_true",
        default=False)
    args = parser.parse_args()
    if args.once:
        print("Running routing graph generation once...")
        write_errors = generate_debug_routing_graph(args)
        if write_errors == "wrong_file":
            sys.exit(1)
        sys.exit(0)
    print("Starting Mapping Server Python Process...")
    loop_and_process(args)
