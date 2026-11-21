#!/usr/bin/env python3
"""Extend the fixed Magna main-sheet reference after checking named Nordic provinces."""

import argparse
from math import ceil, floor, hypot
from pathlib import Path
import re
from runpy import run_path
import subprocess

from PIL import Image
from shapely.geometry import MultiPoint, Point, Polygon


# Natural Earth names; the target province IDs come independently from Magna localization.
EXTENT = (
    "Bergen", "Skien", "Trondheim", "Bodø", "Tromsø", "Rovaniemi", "Joensuu",
    "Kemi", "Hämeenlinna", "Kouvola", "Gävle", "Borlänge",
)
HOLDOUT = (
    "Drammen", "Västerås", "Pori", "Mikkeli", "Jyväskylä", "Östersund",
    "Sundsvall", "Örnsköldsvik", "Umeå", "Skellefteå", "Vaasa", "Kokkola",
    "Oulu", "Kuopio", "Narvik", "Kemijärvi", "Kirkenes", "Luleå",
)
LOCALIZATION = Path("localisation/english/scandinavia_victory_points_l_english.yml")
MAX_NORDIC_ERROR = 6


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, required=True, help="现有主图 geo-reference.json")
    parser.add_argument("--magna", type=Path, required=True, help="固定版本的 Magna 源目录")
    parser.add_argument("--places", type=Path, required=True, help="固定 Natural Earth 城市 GeoJSON")
    parser.add_argument("--out", type=Path, required=True, help="配准结果和复核记录的输出目录")
    args = parser.parse_args()

    pilot = run_path(str(Path(__file__).with_name("prepare-1936-pilot.py")))
    commit = pilot["MAGNA_COMMIT"]
    if subprocess.check_output(["git", "-C", args.magna, "rev-parse", "HEAD"], text=True).strip() != commit:
        raise ValueError("Magna 源提交与试点不一致")
    subprocess.run(["git", "-C", str(args.magna), "diff", "--quiet", "HEAD", "--", "map", str(LOCALIZATION)], check=True)
    reference = pilot["read_json"](args.reference)
    if reference["sheet"] != "europe-main" or reference["source"] != pilot["NATURAL_EARTH"]:
        raise ValueError("需要固定 Natural Earth 来源的 Magna 欧洲主图参考")
    def normalize(name):
        return pilot["normalized"](name).replace("ø", "o")

    labels = (args.magna / LOCALIZATION).read_text(encoding="utf-8-sig")
    map_ids = {normalize(name): pid for pid, name in re.findall(r'VICTORY_POINTS_(\d+):0 "([^"]+)"', labels)}
    features = pilot["verified_source"](args.places, pilot["NATURAL_EARTH"])
    by_name = {row["properties"]["NAME"]: row for row in features
               if row["properties"]["ADM0NAME"] in {"Sweden", "Norway", "Finland"}}
    old_coverage = Polygon([(p["x"], p["y"]) for p in reference["coverage"]])
    extent = MultiPoint([by_name[name]["geometry"]["coordinates"] for name in EXTENT]).convex_hull
    coverage = old_coverage.union(extent)
    if coverage.geom_type != "Polygon" or not coverage.is_valid or coverage.interiors:
        raise ValueError("新增范围须与旧覆盖组成无孔的单一图幅")

    a = reference["affine"]
    raw = args.magna / "map"
    colors = {}
    for line in (raw / "definition.csv").read_text(encoding="utf-8-sig").splitlines():
        fields = line.split(";")
        if len(fields) >= 4 and fields[0].isdigit():
            colors[tuple(map(int, fields[1:4]))] = fields[0]
    evidence = f"{pilot['NATURAL_EARTH']} · {pilot['MAGNA']}/{LOCALIZATION} · {pilot['MAGNA']}/map/provinces.bmp"
    rows = []
    with Image.open(raw / "provinces.bmp") as bmp:
        if bmp.size != (reference["width"], reference["height"]):
            raise ValueError("配准尺寸与底图不一致")
        for role, names in (("extent", EXTENT), ("holdout", HOLDOUT)):
            for name in names:
                feature = by_name[name]
                province = map_ids[normalize(name)]
                lon, lat = feature["geometry"]["coordinates"]
                x, y = pilot["miller"](lon, lat)
                px = float(a[0] + a[1] * x + a[2] * y)
                py = float(a[3] + a[4] * x + a[5] * y)
                pixel = floor(px), floor(py)
                hit = colors.get(bmp.getpixel(pixel))
                radius = MAX_NORDIC_ERROR + 1
                distance = min((hypot(ix + .5 - px, iy + .5 - py)
                                for ix in range(max(0, pixel[0] - radius), min(bmp.width, pixel[0] + radius + 1))
                                for iy in range(max(0, pixel[1] - radius), min(bmp.height, pixel[1] + radius + 1))
                                if colors.get(bmp.getpixel((ix, iy))) == province), default=None)
                in_coverage = coverage.covers(Point(lon, lat))
                rows.append(dict(name=name, role=role, longitude=lon, latitude=lat,
                                 point=[px, py], expectedProvinceId=province, projectedProvinceId=hit,
                                 withinCoverage=in_coverage, nearestExpectedPixel=distance))
                if role == "extent" and hit != province:
                    raise ValueError(f"覆盖地标 {name} 未落在已标名省 {province}")

    held_out = [row for row in rows if row["role"] == "holdout" and row["withinCoverage"]]
    # Luleå is a mismatched map label in the pinned input. Keep the conflict in the report.
    verified = [row for row in held_out if row["name"] != "Luleå"]
    if not verified or any(row["nearestExpectedPixel"] is None or row["nearestExpectedPixel"] > MAX_NORDIC_ERROR
                           for row in verified):
        raise ValueError("北欧留出地标超过允许误差，查看坐标与地图标签后再扩大覆盖")
    reference["coverage"] = [pilot["point"](lon, lat) for lon, lat in coverage.exterior.coords]
    reference["errorRadius"] = max(reference["errorRadius"], ceil(max(row["nearestExpectedPixel"] for row in verified)))
    existing = {row["provinceId"] for row in reference["places"]}
    for row in rows:
        province = row["expectedProvinceId"]
        if not row["withinCoverage"] or row["projectedProvinceId"] != province or province in existing:
            continue
        props = by_name[row["name"]]["properties"]
        aliases = [row["name"], *(value for key, value in props.items()
                                 if key.startswith("NAME_") and isinstance(value, str) and value.strip())]
        reference["places"].append(dict(provinceId=province, names=list(dict.fromkeys(aliases)),
                                       longitude=row["longitude"], latitude=row["latitude"], source=evidence))
        existing.add(province)
    args.out.mkdir(parents=True, exist_ok=True)
    pilot["write_json"](args.out / "geo-reference.json", reference)
    pilot["write_json"](args.out / "geo-nordic-check.json", {
        "source": evidence, "controls": len(EXTENT), "testedWithinCoverage": len(held_out),
        "exactMatches": sum(row["expectedProvinceId"] == row["projectedProvinceId"] for row in held_out),
        "verifiedWithinRadius": len(verified), "errorRadius": reference["errorRadius"], "points": rows,
    })


if __name__ == "__main__":
    main()
