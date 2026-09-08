# hello_lanelet2.py -- Python 3 example for the GraalPy JOSM plugin.
#
# Requires both this plugin (lanelet2) and the GraalPy plugin loaded.
# Run via Tools → Run Python file… (not the Jython Scripting plugin).
#
# Prints how many lanelets are in the current selection (including those
# inferred from selected linestrings), their combined centerline length,
# and the plugin's default subtype.

from org.openstreetmap.josm.gui import MainApplication
from org.openstreetmap.josm.plugins.lanelet2.api import Lanelet2Extensions

layer = MainApplication.getLayerManager().getEditLayer()
if layer is None:
    print("No edit layer. Open a Lanelet2 .osm file first.")
else:
    lanelets = Lanelet2Extensions.lanelets().fromSelection(layer.data)
    total_m = sum(
        Lanelet2Extensions.geometry().centerlineLengthMeters(lanelet)
        for lanelet in lanelets
    )
    subtype = Lanelet2Extensions.settings().get("lanelet.default_subtype", "road")
    print(
        f"{len(lanelets)} lanelet(s) selected, {total_m:.1f} m of centerline. "
        f"Default subtype: {subtype}"
    )
