#!/usr/bin/env python3
"""Build reviewable geography and GM commands for the Magna Germany–Poland pilot."""

import argparse
from collections import Counter, defaultdict
from datetime import date
from hashlib import sha256
import json
from math import ceil, pi
from pathlib import Path
import re
import subprocess
import unicodedata
from urllib.parse import quote

import numpy as np
from PIL import Image
from shapely.geometry import MultiPoint, Point, shape
from shapely.ops import transform
from river_reference import load_magna_rivers


DAY = date(1936, 1, 1)
MAGNA_COMMIT = "d7a57759c188a034c49a0e96aceb1702fa76fc74"
MAGNA = f"https://github.com/AzurCrystal/Magna-Europa/blob/{MAGNA_COMMIT}"
NATURAL_EARTH = (
    "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/v5.1.2/"
    "geojson/ne_10m_populated_places.geojson"
)
CSHAPES = "https://icr.ethz.ch/data/cshapes/CShapes-2.0.geojson"
SOURCE_HASHES = {
    NATURAL_EARTH: "9b8e3de09048ef00dfc70357dbb9fa324493f214b5e0ae4daf1aa79a8d10116b",
    CSHAPES: "384b1ea90b9419f30a858d7ec237c85a22c60d1b35b5f85f215a1204f9989d42",
}
TESSIN = (
    "https://www.bundesarchiv.de/assets/bundesarchiv/de/Downloads/"
    "PDFs_Milhist-Fachliteratur-Auszuege/Tessin_Formationsgeschichte_1933-39_-_Hoehere_Staebe.pdf"
)
KIELCE = (
    "https://bazhum.muzhp.pl/media/texts/studia-muzealno-historyczne/"
    "2012-tom-4/studia_muzealno_historyczne-r2012-t4-s95-107.pdf#page=2"
)
TESSIN_FULL = (
    "https://www.bundesarchiv.de/assets/bundesarchiv/de/Publikationen/BArch/"
    "Publikation_Schriften_Formationsgeschichte_Wehrmacht.pdf"
)
PILZYS = (
    "https://paperity.org/p/97967629/piechota-polska-w-dwudziestoleciu-miedzywojennym-1918-1939-wybrane-probleny-organizacji"
    "（表1）"
)
CAVALRY = "https://muzn.pl/wp-content/uploads/2024/12/dd0e1cbb-dzieje-kawalerii-t-i.pdf"
POMERELIA = (
    "https://static.zpe.gov.pl/portal/f/res/R1bZWIVmU6LrC/1617281984/"
    "15nPPFg0gUAHZtI3ZLsuVncIBPFcLfFe.pdf#page=16"
)
ISLANDS = "https://sbc.org.pl/Content/675776/PDF/iii5024-1938-00-0001.pdf（北海和波罗的海的德国岛屿表）"
BORDER_REVIEW = dict.fromkeys(("14142", "11866", "19879", "19647", "19645", "19646"), ISLANDS)
BORDER_REVIEW.update({
    "19934": "https://library.fes.de/danzig/pdf/1920/1920-287.pdf · https://www.territorial.de/dawp/elbing/kahlbg.htm",
    "13540": "https://eli.gov.pl/api/acts/DU/1937/19/text/O/D19370019.pdf（Czeremosz 河界，省级归属按原州史近似）",
})

# Map label, Natural Earth label, Natural Earth admin name. Validation points stay out of the fit.
CONTROLS = (
    ("Porto", "Porto", "Portugal"), ("Madrid", "Madrid", "Spain"), ("Sevilla", "Seville", "Spain"),
    ("Valencia", "Valencia", "Spain"), ("Bordeaux", "Bordeaux", "France"), ("Lyon", "Lyon", "France"),
    ("Marseille", "Marseille", "France"), ("Bruxelles", "Brussels", "Belgium"),
    ("Amsterdam", "Amsterdam", "Netherlands"), ("Rotterdam", "Rotterdam", "Netherlands"),
    ("Bern", "Bern", "Switzerland"), ("Zürich", "Zürich", "Switzerland"), ("Praha", "Prague", "Czechia"),
    ("Hamburg", "Hamburg", "Germany"), ("Dresden", "Dresden", "Germany"), ("Köln", "Cologne", "Germany"),
    ("München", "Munich", "Germany"), ("Bremen", "Bremen", "Germany"), ("Stuttgart", "Stuttgart", "Germany"),
    ("Königsberg", "Kaliningrad", "Russia"), ("Poznań", "Poznań", "Poland"), ("Kraków", "Kraków", "Poland"),
    ("Gdynia", "Gdynia", "Poland"), ("Wilno", "Vilnius", "Lithuania"), ("Kiev", "Kyiv", "Ukraine"),
    ("Minsk", "Minsk", "Belarus"), ("Kaunas", "Kaunas", "Lithuania"), ("Stockholm", "Stockholm", "Sweden"),
    ("Oslo", "Oslo", "Norway"), ("Edinburgh", "Edinburgh", "United Kingdom"),
    ("Manchester", "Manchester", "United Kingdom"), ("Zagreb", "Zagreb", "Croatia"),
    ("Beograd", "Belgrade", "Serbia"), ("Athína", "Athens", "Greece"),
    ("București", "Bucharest", "Romania"), ("Budapest", "Budapest", "Hungary"),
    ("Smolensk", "Smolensk", "Russia"), ("Kazan", "Kazan", "Russia"), ("Odessa", "Odessa", "Ukraine"),
    ("Tunis", "Tunis", "Tunisia"), ("Tripoli", "Tripoli", "Libya"), ("Casablanca", "Casablanca", "Morocco"),
)
VALIDATION = (
    ("Lisboa", "Lisbon", "Portugal"), ("Barcelona", "Barcelona", "Spain"), ("Paris", "Paris", "France"),
    ("London", "London", "United Kingdom"), ("Berlin", "Berlin", "Germany"), ("Wien", "Vienna", "Austria"),
    ("Würzburg", "Würzburg", "Germany"), ("Warszawa", "Warsaw", "Poland"), ("Danzig", "Gdańsk", "Poland"),
    ("Kielce", "Kielce", "Poland"), ("Lwów", "Lviv", "Ukraine"), ("Tallinn", "Tallinn", "Estonia"),
    ("Helsinki", "Helsinki", "Finland"), ("Moskva", "Moscow", "Russia"), ("Istanbul", "Istanbul", "Turkey"),
)


def normalized(text):
    return "".join(ch for ch in unicodedata.normalize("NFKD", text.casefold()) if not unicodedata.combining(ch))


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def verified_source(path, url):
    raw = path.read_bytes()
    if sha256(raw).hexdigest() != SOURCE_HASHES[url]:
        raise ValueError(f"史料文件 hash 与固定版本不一致: {path}")
    return json.loads(raw)["features"]


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def point(x, y):
    return {"x": float(x), "y": float(y)}


def load_provinces(world):
    pointer = read_json(world / "view/current.json")
    if pointer["node"] != "main-0":
        raise ValueError("先通过 world_view 生成 main-0 的可读视图")
    parts = world / pointer["directory"] / "map/province"
    provinces = [row for path in sorted(parts.glob("*.json")) for row in read_json(path)]
    return pointer, provinces


def city_refs(provinces, features):
    by_map_name = defaultdict(list)
    for province in provinces:
        by_map_name[province["name"]].append(province)
    by_natural_earth = defaultdict(list)
    for feature in features:
        props = feature["properties"]
        name = props.get("NAME")
        if name:
            by_natural_earth[normalized(name), props["ADM0NAME"]].append(feature)
    matches = []
    for role, pairs in (("fit", CONTROLS), ("validation", VALIDATION)):
        for map_name, earth_name, country in pairs:
            (province,) = by_map_name[map_name]
            (feature,) = by_natural_earth[normalized(earth_name), country]
            aliases = [map_name, earth_name]
            aliases.extend(value for key, value in feature["properties"].items()
                           if key.startswith("NAME_") and isinstance(value, str) and value.strip())
            lon, lat = feature["geometry"]["coordinates"]
            matches.append({
                "provinceId": province["id"], "names": list(dict.fromkeys(aliases)),
                "longitude": lon, "latitude": lat, "source": NATURAL_EARTH, "role": role,
                "earthName": feature["properties"]["NAME"], "country": country, "point": province["displayPoint"],
            })
    return matches


def miller(lon, lat):
    return np.radians(lon), 1.25 * np.log(np.tan(pi / 4 + .4 * np.radians(lat)))


def fit_map(matches, bitmap, definitions):
    fit = [row for row in matches if row["role"] == "fit"]
    x, y = miller([row["longitude"] for row in fit], [row["latitude"] for row in fit])
    design = np.column_stack((np.ones(len(fit)), x, y))
    target = [[row["point"]["x"], row["point"]["y"]] for row in fit]
    affine = np.linalg.lstsq(design, target, rcond=None)[0].T

    def project(lon, lat, z=None):
        x, y = miller(lon, lat)
        return (affine[0, 0] + affine[0, 1] * x + affine[0, 2] * y,
                affine[1, 0] + affine[1, 1] * x + affine[1, 2] * y)

    colors = {}
    for line in definitions.read_text(encoding="utf-8-sig").splitlines():
        fields = line.split(";")
        if len(fields) >= 4 and fields[0].isdigit():
            colors[tuple(map(int, fields[1:4]))] = fields[0]
    with Image.open(bitmap) as pixels:
        width, height = pixels.size
        for row in matches:
            px, py = map(float, project(row["longitude"], row["latitude"]))
            pixel = (int(px), int(py))
            row["projected"] = point(px, py)
            row["testedProvince"] = (colors.get(pixels.getpixel(pixel))
                                      if 0 <= px < width and 0 <= py < height else None)
            row["error"] = float(np.hypot(px - row["point"]["x"], py - row["point"]["y"]))
    validation = [row for row in matches if row["role"] == "validation"]
    check = {
        "controls": len(fit), "tested": len(validation),
        "matched": sum(row["testedProvince"] == row["provinceId"] for row in validation),
        "maxError": max(row["error"] for row in validation),
    }
    hull = MultiPoint([(row["longitude"], row["latitude"]) for row in matches]).convex_hull
    return project, {
        "width": width, "height": height, "affine": affine.ravel().tolist(), "check": check,
        "coverage": [point(lon, lat) for lon, lat in hull.exterior.coords],
        "errorRadius": ceil(check["maxError"]),
    }


def historical_boundaries(features, project):
    labels = {255: ("GER", "德国"), 290: ("POL", "波兰"), 291: ("DNZ", "但泽自由市")}
    geometries = {}
    metadata = {}
    for row in features:
        props = row["properties"]
        code = props["gwcode"]
        if code not in labels:
            continue
        start = date(props["gwsyear"], props["gwsmonth"], props["gwsday"])
        end = date(props["gweyear"], props["gwemonth"], props["gweday"])
        if start <= DAY <= end:
            tag, _ = labels[code]
            if tag in geometries:
                raise ValueError(f"{tag} 在 {DAY} 有多个历史边界")
            geometries[tag] = transform(project, shape(row["geometry"]))
            metadata[tag] = props
    if len(geometries) != len(labels):
        raise ValueError("未找到固定日期的全部历史边界")
    borders = []
    for tag, name in labels.values():
        geom = geometries[tag].simplify(.3, preserve_topology=True)
        polygons = [geom] if geom.geom_type == "Polygon" else geom.geoms
        paths = [[point(x, y) for x, y in ring.coords]
                 for polygon in polygons for ring in [polygon.exterior, *polygon.interiors]]
        borders.append({"name": name, "date": str(DAY), "source": CSHAPES, "paths": paths})
    return geometries, borders, metadata


def assignments(text):
    """Read Paradox assignment blocks, retaining duplicate keys and bare province IDs."""
    tokens = [token for token in re.findall(r'#[^\n]*|"(?:\\.|[^"\\])*"|[{}=]|[^\s{}=#]+', text)
              if not token.startswith("#")]

    def parse(start, nested):
        values = []
        i = start
        while i < len(tokens):
            key = tokens[i]
            i += 1
            if key == "}" and nested:
                return values, i
            if key in "{}=":
                raise ValueError("意外的州史语法")
            if i < len(tokens) and tokens[i] == "=":
                value = tokens[i + 1]
                i += 2
                if value == "{":
                    value, i = parse(i, True)
                values.append((key, value))
            else:
                values.append((None, key))
        if nested:
            raise ValueError("州史块未闭合")
        return values, i

    return parse(0, False)[0]


def state_owner(path):
    [(key, block)] = assignments(path.read_text(encoding="utf-8-sig"))
    if key != "state":
        raise ValueError(f"未知州史根节点 {path}")
    values = dict(block)
    history = values.get("history", [])
    owner = dict(history).get("owner")
    dated_owners = []
    for key, value in history:
        if key and re.fullmatch(r"\d+\.\d+\.\d+", key):
            when = date(*map(int, key.split(".")))
            dated_owner = dict(value).get("owner")
            if dated_owner and when <= DAY:
                dated_owners.append((when, dated_owner))
    if dated_owners:
        owner = sorted(dated_owners, key=lambda record: record[0])[-1][1]
    return values["id"], owner, {entry for _, entry in values["provinces"]}


def evidence(source, status="known"):
    return {"source": source, "status": status, "date": str(DAY)}


def territory_commands(root, provinces, geometries, radius):
    by_state = defaultdict(dict)
    for province in provinces:
        if state_id := province.get("stateId"):
            by_state[state_id][province["id"]] = province
    commands = []
    report = {"counts": {}, "review": [], "acceptedStates": []}
    for path in sorted((root / "history/states").glob("*.txt")):
        state_id, owner, province_ids = state_owner(path)
        if owner not in ("GER", "POL"):
            continue
        view = by_state[state_id]
        if province_ids != view.keys():
            report["review"].append({"state": state_id, "owner": owner, "reason": "state-membership-mismatch",
                                     "source": path.name, "provinceIds": sorted(province_ids)})
            continue
        accepted = []
        counts = report["counts"].setdefault(owner, Counter())
        geometry = geometries[owner]
        boundary = geometry.boundary
        for province_id in sorted(province_ids, key=int):
            province = view[province_id]
            if province["kind"] != "land":
                continue
            location = Point(province["displayPoint"]["x"], province["displayPoint"]["y"])
            counts["sourceLand"] += 1
            if boundary.distance(location) <= radius:
                reason = "border-uncertainty"
            elif not geometry.covers(location) or geometries["DNZ"].covers(location):
                reason = "boundary-conflict"
            else:
                accepted.append(province_id)
                counts["assigned"] += 1
                continue
            counts[reason] += 1
            report["review"].append({"state": state_id, "owner": owner, "provinceId": province_id,
                                     "reason": reason, "source": path.name})
        if accepted:
            source = f"{MAGNA}/history/states/{quote(path.name)} · {CSHAPES}（{DAY} 省内点交叉核对）"
            commands.append({"op": "setTerritory", "provinceIds": accepted, "field": "owner",
                             "assignment": {"countryId": owner, "evidence": evidence(source, "inferred")}})
            report["acceptedStates"].append({"state": state_id, "owner": owner, "source": path.name,
                                             "accepted": len(accepted)})
    return commands, report


def identities_and_units(provinces):
    names = {row["name"]: row["id"] for row in provinces if row["name"] in
             ("Berlin", "Warszawa", "Kielce", "Weimar", "Würzburg")}
    commands = [
        {"op": "putCountry", "country": {"id": tag, "name": name, "aliases": aliases,
                                        "capitalProvinceId": names[capital], "color": color,
                                        "evidence": evidence(CSHAPES)}}
        for tag, name, aliases, capital, color in (
            ("GER", "德国", ["Germany", "Deutschland"], "Berlin", "#79858c"),
            ("POL", "波兰", ["Poland", "Polska"], "Warszawa", "#bd7470"),
        )
    ]
    units = [
        (f"de-{number}-panzer-hq", f"第{number}装甲师师部", "armored", "GER", town, TESSIN + "#page=5",
         TESSIN + "#page=8", (
             ("panzer-brigade", f"第{number}装甲旅", "armored", "brigade"),
             ("schutzen-brigade", f"第{number}步兵旅", "infantry", "brigade"),
         ))
        for number, town in enumerate(("Weimar", "Würzburg", "Berlin"), 1)
    ]
    units.append(("pl-2-legion-infantry-hq", "第2军团步兵师师部", "infantry", "POL", "Kielce", KIELCE, KIELCE, (
        ("4-legion-infantry", "第4军团步兵团", "infantry", "regiment"),
        ("2-legion-light-artillery", "第2军团轻炮兵团", "artillery", "regiment"),
    )))
    deployments = []
    for unit_id, name, unit_type, country, town, source, organization, subunits in units:
        children = [
            {"nodeId": child_id, "name": child_name, "type": child_type, "echelon": echelon,
             "personnel": {"status": "unknown", "source": organization}}
            for child_id, child_name, child_type, echelon in subunits
        ]
        deployments.append({"unitId": unit_id, "provinceId": names[town], "town": town, "source": source,
                            "organizationSource": organization, "subunits": [child["name"] for child in children]})
        document_id = unit_id + "-actual"
        commands.append({"op": "putOrbatDocument", "document": {
            "format": "orbat-native-v1", "documentId": document_id, "role": "actual", "date": str(DAY),
            "root": {"nodeId": "root", "name": name, "type": unit_type, "echelon": "division",
                     "personnel": {"status": "unknown", "source": organization}, "children": children},
        }})
        commands.append({"op": "putUnit", "unit": {
            "id": unit_id, "name": name, "countryId": country, "type": unit_type, "provinceId": names[town],
            "actualRef": {"documentId": document_id, "nodeId": "root"},
            "evidence": evidence(source), "deploymentEvidence": evidence(source, "inferred"),
        }})
    return commands, deployments


def complete_territory(root, provinces, geometries, radius, current):
    by_state = defaultdict(dict)
    for province in provinces:
        by_state[province.get("stateId")][province["id"]] = province
    commands = [{"op": "putCountry", "country": {
        "id": "DNZ", "name": "但泽自由市", "aliases": ["Free City of Danzig", "Freie Stadt Danzig", "Wolne Miasto Gdańsk"],
        "capitalProvinceId": "10923", "color": "#a56ea4", "evidence": evidence(f"{CSHAPES} · {POMERELIA}"),
    }}]
    expected = {}
    updates = []
    for path in sorted((root / "history/states").glob("*.txt")):
        state_id, tag, province_ids = state_owner(path)
        if tag not in geometries:
            continue
        state = by_state[state_id]
        if province_ids != state.keys():
            raise ValueError(f"州 {state_id} 的存档和州史省成员不一致")
        geometry = geometries[tag]
        accepted = defaultdict(list)
        for province_id in sorted(province_ids, key=int):
            if state[province_id]["kind"] != "land":
                continue
            saved_owner = (current.get(province_id, {}).get("owner") or {}).get("countryId")
            if saved_owner and saved_owner != tag:
                raise ValueError(f"省 {province_id} 已有归属 {saved_owner}，现有州史给出 {tag}")
            expected[province_id] = tag
            if saved_owner:
                continue
            p = state[province_id]["displayPoint"]
            location = Point(p["x"], p["y"])
            distance = geometry.boundary.distance(location)
            extra = BORDER_REVIEW.get(province_id)
            if extra is None and not geometry.covers(location) and distance > radius:
                raise ValueError(f"省 {province_id} 与历史边界相距 {distance:.2f}，需逐省查证")
            if tag == "DNZ":
                extra = POMERELIA
            source = f"{MAGNA}/history/states/{quote(path.name)} · {CSHAPES}"
            if extra:
                source += " · " + extra
            accepted[source].append(province_id)
            updates.append({"provinceId": province_id, "owner": tag, "stateId": state_id,
                            "distanceToReference": round(distance, 3), "insideReference": geometry.covers(location),
                            "source": source})
        for source, ids in accepted.items():
            commands.append({"op": "setTerritory", "field": "owner", "provinceIds": ids,
                             "assignment": {"countryId": tag, "evidence": evidence(source, "inferred")}})
    return commands, expected, {"counts": Counter(expected.values()), "added": updates}


def formation_node(node_id, name, unit_type, echelon, source, children=()):
    return {"nodeId": node_id, "name": name, "type": unit_type, "echelon": echelon,
            "personnel": {"status": "unknown", "source": source}, "children": list(children)}


def opening_roster(provinces, expected):
    roster = read_json(Path(__file__).with_name("scenarios") / "1936-de-pl-roster.json")
    by_id = {province["id"]: province for province in provinces}
    commands = []
    deployments = []

    def add(unit_id, name, tag, unit_type, garrison, province_id, source, organization, children, map_name=None):
        province = by_id[province_id]
        if expected[province_id] != tag or (map_name and province["name"] != map_name):
            raise ValueError(f"{name} 驻地 {garrison} 与原生地图省 {province_id} 不符")
        document_id = unit_id + "-actual"
        deployments.append({"unitId": unit_id, "countryId": tag, "garrison": garrison, "provinceId": province_id,
                            "source": source, "organizationSource": organization, "directSubunits": len(children)})
        commands.extend((
            {"op": "putOrbatDocument", "document": {"format": "orbat-native-v1", "documentId": document_id,
                "role": "actual", "date": str(DAY),
                "root": formation_node("root", name, unit_type, "division", organization, children)}},
            {"op": "putUnit", "unit": {"id": unit_id, "name": name, "countryId": tag, "type": unit_type,
                "provinceId": province_id, "actualRef": {"documentId": document_id, "nodeId": "root"},
                "evidence": evidence(source, "inferred"), "deploymentEvidence": evidence(source, "inferred")}},
        ))

    for row in roster["germanInfantry"]:
        number, garrison = row["number"], row["garrison"]
        source = TESSIN + ("#page=4" if number <= 4 else "#page=5")
        children = [formation_node(f"ir-{n}", f"第{n}步兵团", "infantry", "regiment", f"{TESSIN_FULL}#page={page}")
                    for n, page in row["regiments"]]
        add(f"de-{number}-infantry-hq", f"德国第{number}步兵师师部", "GER", "infantry", garrison,
            row["provinceId"], source, f"{TESSIN_FULL}#page=118", children, garrison)

    for number, (garrison, province_id) in enumerate((("Weimar", "12392"), ("Würzburg", "12730"), ("Berlin", "6184")), 1):
        brigades = TESSIN + "#page=8"
        regiments = [formation_node(f"panzer-regiment-{n}", f"第{n}装甲团", "armored", "regiment", TESSIN_FULL + "#page=68")
                     for n in (2 * number - 1, 2 * number)]
        children = [formation_node("panzer-brigade", f"第{number}装甲旅", "armored", "brigade", brigades, regiments),
                    formation_node("schutzen-brigade", f"第{number}步兵旅", "infantry", "brigade", brigades)]
        add(f"de-{number}-panzer-hq", f"第{number}装甲师师部", "GER", "armored", garrison,
            province_id, TESSIN + "#page=5", brigades, children, garrison)

    for number, (garrison, province_id, brigades) in enumerate((("Potsdam", "20575", (1, 2)), ("Breslau", "9490", (4,))), 1):
        source = f"{TESSIN}#page=5 · {TESSIN}#page=6"
        children = [formation_node(f"reiter-brigade-{n}", f"第{n}骑兵旅", "cavalry", "brigade", TESSIN_FULL + "#page=154")
                    for n in brigades]
        add(f"de-{number}-cavalry-hq", f"德国第{number}骑兵师师部", "GER", "cavalry", garrison,
            province_id, source, TESSIN_FULL + "#page=154", children, garrison)

    for row in roster["polishInfantry"]:
        number, garrison = row["number"], row["garrison"]
        source = PILZYS + (" · " + row["locationSource"] if "locationSource" in row else "")
        branch = "军团步兵" if number <= 3 else "山地步兵" if number in (21, 22) else "步兵"
        unit_type = "mountaineer" if number in (21, 22) else "infantry"
        unit_id = f"pl-{number}-{'legion-' if number <= 3 else ''}infantry-hq"
        unit_name = f"第{number}{branch}师师部"
        children = []
        for regiment in row["regiments"]:
            name = f"第{regiment}{'波德哈莱步枪' if number in (21, 22) else branch}团"
            node_id = "4-legion-infantry" if number == 2 and regiment == 4 else f"infantry-{regiment}"
            reg_source = row.get("regimentSource", {}).get(str(regiment), PILZYS)
            children.append(formation_node(node_id, name, unit_type, "regiment", reg_source))
        artillery_id = "2-legion-light-artillery" if number == 2 else f"artillery-{number}"
        children.append(formation_node(artillery_id, f"第{number}{'军团' if number <= 3 else ''}轻炮兵团",
                                       "artillery", "regiment", KIELCE if number == 2 else PILZYS))
        if number == 2:
            source = f"{KIELCE} · {source}"
        add(unit_id, unit_name, "POL", unit_type, garrison, row["provinceId"], source, PILZYS, children,
            row.get("mapName", garrison if "locationSource" not in row else None))

    cavalry_source = CAVALRY + "#page=87"
    children = [formation_node(f"cavalry-brigade-{n}", f"第{n}骑兵旅", "cavalry", "brigade", CAVALRY + "#page=84")
                for n in (1, 12, 13)]
    add("pl-2-cavalry-hq", "波兰第2骑兵师师部", "POL", "cavalry", "Warszawa", "11800", cavalry_source,
        CAVALRY + "#page=84", children, "Warszawa")
    return commands, deployments


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", type=Path, required=True, help="拥有 main-0 可读视图的地图目录")
    parser.add_argument("--magna", type=Path, required=True, help="固定版本的 Magna 源目录")
    parser.add_argument("--places", type=Path, required=True, help="Natural Earth v5.1.2 GeoJSON")
    parser.add_argument("--boundaries", type=Path, required=True, help="CShapes 2.0 GeoJSON")
    parser.add_argument("--out", type=Path, required=True, help="生成待导入清单的目录")
    parser.add_argument("--continue-from", type=Path, help="用 de-pl-1936-1 的可读视图生成完整师级开局的修正")
    args = parser.parse_args()
    if subprocess.check_output(["git", "-C", args.magna, "rev-parse", "HEAD"], text=True).strip() != MAGNA_COMMIT:
        raise ValueError("Magna 源提交与试点不一致")
    subprocess.run(["git", "-C", args.magna, "diff", "--quiet", "HEAD", "--", "map", "history/states"], check=True)
    pointer, provinces = load_provinces(args.world)
    matches = city_refs(provinces, verified_source(args.places, NATURAL_EARTH))
    project, reference = fit_map(matches, args.magna / "map/provinces.bmp", args.magna / "map/definition.csv")
    geometries, borders, metadata = historical_boundaries(verified_source(args.boundaries, CSHAPES), project)
    names = [{key: row[key] for key in ("provinceId", "names", "longitude", "latitude", "source")} for row in matches]
    reference.update({"format": "worldsim-geo-v1", "sheet": "europe-main", "base": pointer["node"],
                      "baseHash": pointer["stateHash"], "source": NATURAL_EARTH, "places": names, "borders": borders})
    reference["rivers"] = load_magna_rivers(args.magna, (reference["width"], reference["height"]))
    searched = ("Berlin", "Warsaw", "Kielce", "Würzburg", "Gdańsk") if args.continue_from else (
        "Berlin", "Warsaw", "Kielce", "Würzburg")
    geo_checks = [{"name": row["earthName"], "provinceId": row["provinceId"]}
                  for row in matches if row["earthName"] in searched]
    if args.continue_from:
        saved = read_json(args.continue_from / "view/current.json")
        if saved["node"] != "de-pl-1936-1":
            raise ValueError("先通过 world_view 生成 de-pl-1936-1 的可读视图")
        countries = read_json(args.continue_from / saved["directory"] / "country/0000.json")[0]
        changes, expected, territory = complete_territory(args.magna, provinces, geometries, reference["errorRadius"],
                                                         countries["territories"])
        units, deployments = opening_roster(provinces, expected)
        reference["territory"] = {"date": str(DAY), "source": f"{MAGNA}/history/states · {CSHAPES}", "owners": expected}
        scenario = {"date": str(DAY), "base": saved["node"], "baseHash": saved["stateHash"],
                    "tick": 2, "geoChecks": geo_checks, "commands": changes + units}
    else:
        commands, territory = territory_commands(args.magna, provinces, geometries, reference["errorRadius"])
        entities, deployments = identities_and_units(provinces)
        scenario = {"date": str(DAY), "base": pointer["node"], "baseHash": pointer["stateHash"], "forkBranch": "de-pl-1936",
                    "tick": 1, "geoChecks": geo_checks, "commands": entities[:2] + commands + entities[2:]}
    args.out.mkdir(parents=True, exist_ok=True)
    write_json(args.out / "geo-reference.json", reference)
    write_json(args.out / "scenario.json", scenario)
    write_json(args.out / "research.json", {"date": str(DAY), "reference": reference["check"], "anchors": matches,
              "sources": SOURCE_HASHES, "boundaryPeriods": metadata, "territory": territory, "deployments": deployments})
    print(json.dumps({"fit": reference["check"], "radius": reference["errorRadius"], "territory": territory["counts"],
                      "mismatchedStates": sum(row["reason"] == "state-membership-mismatch" for row in territory.get("review", [])),
                      "deployments": deployments}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
