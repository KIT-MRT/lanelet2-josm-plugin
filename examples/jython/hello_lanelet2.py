# hello_lanelet2.py -- example: scripting against the Lanelet2 JOSM plugin.
#
# Jython 2.7 only (not Python 3). Run once via:
#   Scripting -> Run Script  (engine: Jython 2.7)
#
# Adds "Hello Lanelet2 (example)" to the Lanelet2 Utils menu. Click that
# entry to report the current selection: how many lanelets (directly
# selected or inferred from selected linestrings), their combined
# centerline length, and the plugin's default subtype setting.

from javax.swing import JOptionPane
from org.openstreetmap.josm.gui import MainApplication
from org.openstreetmap.josm.plugins.lanelet2.api import Lanelet2Extensions


def report_selection():
    layer = MainApplication.getLayerManager().getEditLayer()
    parent = MainApplication.getMainFrame()
    if layer is None:
        JOptionPane.showMessageDialog(
            parent,
            "No edit layer. Open a Lanelet2 .osm file first.",
            "Hello Lanelet2",
            JOptionPane.INFORMATION_MESSAGE)
        return
    lanelets = Lanelet2Extensions.lanelets().fromSelection(layer.data)
    total_m = 0.0
    for lanelet in lanelets:
        total_m += Lanelet2Extensions.geometry().centerlineLengthMeters(lanelet)
    subtype = Lanelet2Extensions.settings().get("lanelet.default_subtype", "road")
    msg = (
        "%d lanelet(s) selected, %.1f m of centerline.\nDefault subtype: %s"
        % (len(lanelets), total_m, subtype)
    )
    JOptionPane.showMessageDialog(
        parent, msg, "Hello Lanelet2", JOptionPane.INFORMATION_MESSAGE)


Lanelet2Extensions.register(
    "example.hello_lanelet2",
    "Hello Lanelet2 (example)",
    report_selection,
    Lanelet2Extensions.after("lanelet_edit.check_lanelet_borders"))
