#!/usr/bin/env python

import lanelet2
import sys
import argparse


def make_positive(layer):
    for elem in layer:
        if elem.id < 0:
            elem.id = layer.uniqueId()


parser = argparse.ArgumentParser()
parser.add_argument("filename", help="Path to the input osm file")
group = parser.add_mutually_exclusive_group(required=True)
group.add_argument("output", help="Path to results", nargs='?')
group.add_argument("-i", "--inplace", action="store_true", help="Overwrite input file")
args = parser.parse_args()

if args.inplace:
    args.output = args.filename

proj = lanelet2.projection.MercatorProjector(lanelet2.io.Origin(49, 8))
map, loadErrors = lanelet2.io.loadRobust(args.filename, proj)

if loadErrors:
    print("[WARNING] LOADING ERRORS while making IDs positive:")
    print("Loading Errors will result in missing elements in the loaded ll2 map in memory (which can be wanted as a kind of dirty filtering)")
    print("------------------------------------------------------------------------------------------------")
    for loadError in loadErrors:
        print(loadError)
    print("------------------------------------------------------------------------------------------------")

make_positive(map.pointLayer)
make_positive(map.lineStringLayer)
make_positive(map.polygonLayer)
make_positive(map.laneletLayer)
make_positive(map.areaLayer)
make_positive(map.regulatoryElementLayer)

writeErrors = lanelet2.io.writeRobust(args.output, map, proj)
if writeErrors:
    print("[WARNING] WRITE ERRORS while making IDs positive:")
    print("write errors will result in missing elements in the final .osm map (which can be wanted as a kind of dirty filtering)")
    print("------------------------------------------------------------------------------------------------")
    for error in writeErrors:
        print(error)
    print("------------------------------------------------------------------------------------------------")