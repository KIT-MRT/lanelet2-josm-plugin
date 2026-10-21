"""Mapillary Vistas v1.2 classes, as seamseg numbers and colours them.

The scene's `processed/seamseg_instances/` masks come from
https://github.com/mapillary/seamseg trained on Mapillary Vistas v1.2. Its
class ids are **trainIds** in the 65-class taxonomy ordered stuff first
(trainId 0-27) then things (28-64) -- confirmed against the example scene,
where the ids decode to a street full of Poles, Traffic Signs, Street Lights,
Cars, a Bus and a Person, which is what the pictures show.

Only *things* ever appear in the instance masks, but the whole table is kept:
the ids are trainIds, so a stuff id would be a silent mis-colouring rather
than an error, and this way it is simply named.

Colours are the official Vistas palette, generated from the upstream
`mvd_classes.py` category list rather than typed in.
"""

# (trainId, name, is_thing, rgb)
CLASSES = [
    ( 0, "Curb",                  False, (196, 196, 196)),  # construction--barrier--curb
    ( 1, "Fence",                 False, (190, 153, 153)),  # construction--barrier--fence
    ( 2, "Guard Rail",            False, (180, 165, 180)),  # construction--barrier--guard-rail
    ( 3, "Barrier",               False, ( 90, 120, 150)),  # construction--barrier--other-barrier
    ( 4, "Wall",                  False, (102, 102, 156)),  # construction--barrier--wall
    ( 5, "Bike Lane",             False, (128,  64, 255)),  # construction--flat--bike-lane
    ( 6, "Curb Cut",              False, (170, 170, 170)),  # construction--flat--curb-cut
    ( 7, "Parking",               False, (250, 170, 160)),  # construction--flat--parking
    ( 8, "Pedestrian Area",       False, ( 96,  96,  96)),  # construction--flat--pedestrian-area
    ( 9, "Rail Track",            False, (230, 150, 140)),  # construction--flat--rail-track
    (10, "Road",                  False, (128,  64, 128)),  # construction--flat--road
    (11, "Service Lane",          False, (110, 110, 110)),  # construction--flat--service-lane
    (12, "Sidewalk",              False, (244,  35, 232)),  # construction--flat--sidewalk
    (13, "Bridge",                False, (150, 100, 100)),  # construction--structure--bridge
    (14, "Building",              False, ( 70,  70,  70)),  # construction--structure--building
    (15, "Tunnel",                False, (150, 120,  90)),  # construction--structure--tunnel
    (16, "Lane Marking - General", False, (255, 255, 255)),  # marking--general
    (17, "Mountain",              False, ( 64, 170,  64)),  # nature--mountain
    (18, "Sand",                  False, (230, 160,  50)),  # nature--sand
    (19, "Sky",                   False, ( 70, 130, 180)),  # nature--sky
    (20, "Snow",                  False, (190, 255, 255)),  # nature--snow
    (21, "Terrain",               False, (152, 251, 152)),  # nature--terrain
    (22, "Vegetation",            False, (107, 142,  35)),  # nature--vegetation
    (23, "Water",                 False, (  0, 170,  30)),  # nature--water
    (24, "Pothole",               False, ( 70, 100, 150)),  # object--pothole
    (25, "On Rails",              False, (  0,  80, 100)),  # object--vehicle--on-rails
    (26, "Car Mount",             False, ( 32,  32,  32)),  # void--car-mount
    (27, "Ego Vehicle",           False, (120,  10,  10)),  # void--ego-vehicle
    (28, "Bird",                  True, (165,  42,  42)),  # animal--bird
    (29, "Ground Animal",         True, (  0, 192,   0)),  # animal--ground-animal
    (30, "Crosswalk - Plain",     True, (140, 140, 200)),  # construction--flat--crosswalk-plain
    (31, "Person",                True, (220,  20,  60)),  # human--person
    (32, "Bicyclist",             True, (255,   0,   0)),  # human--rider--bicyclist
    (33, "Motorcyclist",          True, (255,   0, 100)),  # human--rider--motorcyclist
    (34, "Other Rider",           True, (255,   0, 200)),  # human--rider--other-rider
    (35, "Lane Marking - Crosswalk", True, (200, 128, 128)),  # marking--crosswalk-zebra
    (36, "Banner",                True, (255, 255, 128)),  # object--banner
    (37, "Bench",                 True, (250,   0,  30)),  # object--bench
    (38, "Bike Rack",             True, (100, 140, 180)),  # object--bike-rack
    (39, "Billboard",             True, (220, 220, 220)),  # object--billboard
    (40, "Catch Basin",           True, (220, 128, 128)),  # object--catch-basin
    (41, "CCTV Camera",           True, (222,  40,  40)),  # object--cctv-camera
    (42, "Fire Hydrant",          True, (100, 170,  30)),  # object--fire-hydrant
    (43, "Junction Box",          True, ( 40,  40,  40)),  # object--junction-box
    (44, "Mailbox",               True, ( 33,  33,  33)),  # object--mailbox
    (45, "Manhole",               True, (100, 128, 160)),  # object--manhole
    (46, "Phone Booth",           True, (142,   0,   0)),  # object--phone-booth
    (47, "Street Light",          True, (210, 170, 100)),  # object--street-light
    (48, "Pole",                  True, (153, 153, 153)),  # object--support--pole
    (49, "Traffic Sign Frame",    True, (128, 128, 128)),  # object--support--traffic-sign-frame
    (50, "Utility Pole",          True, (  0,   0,  80)),  # object--support--utility-pole
    (51, "Traffic Light",         True, (250, 170,  30)),  # object--traffic-light
    (52, "Traffic Sign (Back)",   True, (192, 192, 192)),  # object--traffic-sign--back
    (53, "Traffic Sign (Front)",  True, (220, 220,   0)),  # object--traffic-sign--front
    (54, "Trash Can",             True, (140, 140,  20)),  # object--trash-can
    (55, "Bicycle",               True, (119,  11,  32)),  # object--vehicle--bicycle
    (56, "Boat",                  True, (150,   0, 255)),  # object--vehicle--boat
    (57, "Bus",                   True, (  0,  60, 100)),  # object--vehicle--bus
    (58, "Car",                   True, (  0,   0, 142)),  # object--vehicle--car
    (59, "Caravan",               True, (  0,   0,  90)),  # object--vehicle--caravan
    (60, "Motorcycle",            True, (  0,   0, 230)),  # object--vehicle--motorcycle
    (61, "Other Vehicle",         True, (128,  64,  64)),  # object--vehicle--other-vehicle
    (62, "Trailer",               True, (  0,   0, 110)),  # object--vehicle--trailer
    (63, "Truck",                 True, (  0,   0,  70)),  # object--vehicle--truck
    (64, "Wheeled Slow",          True, (  0,   0, 192)),  # object--vehicle--wheeled-slow
]

NUM_CLASSES = len(CLASSES)
UNLABELLED = 255          # what a point outside every mask gets
UNLABELLED_COLOR = (90, 90, 90)

NAMES = {train_id: name for train_id, name, _thing, _rgb in CLASSES}
COLORS = {train_id: rgb for train_id, _name, _thing, rgb in CLASSES}
THINGS = {train_id for train_id, _name, thing, _rgb in CLASSES if thing}


def palette():
    """256 RGB rows indexed by class id, for the browser's colour table."""
    rows = [list(UNLABELLED_COLOR) for _ in range(256)]
    for train_id, _name, _thing, rgb in CLASSES:
        rows[train_id] = list(rgb)
    return rows


def describe(train_id):
    return NAMES.get(train_id, "unlabelled" if train_id == UNLABELLED else "class %d" % train_id)
