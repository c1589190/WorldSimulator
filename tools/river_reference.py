#!/usr/bin/env python3
"""Add centerlines from the pinned Magna river bitmap to its geography reference."""

import argparse
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path
import subprocess

from PIL import Image


COMMIT = "d7a57759c188a034c49a0e96aceb1702fa76fc74"
BITMAP = "map/rivers.bmp"
SOURCE = f"https://github.com/AzurCrystal/Magna-Europa/blob/{COMMIT}/{BITMAP}"
SOURCE_HASH = "sha256:fce85b4945e62d814fb649a4527f63a620ad91f4241c0dafcea32fbeee49a3c6"
BASE_HASH = "sha256:097b73086f835cd63df788a787c09cd32fd165be896b39b158c85c43139371ff"
SIZE = (4096, 3072)


def adjacent(pixel, width):
    yield pixel - width
    yield pixel + width
    if pixel % width:
        yield pixel - 1
    if (pixel + 1) % width:
        yield pixel + 1


def river_lines(pixels, width):
    neighbors = {pixel: [step for step in adjacent(pixel, width) if step in pixels] for pixel in pixels}

    def edge(a, b):
        return (min(a, b), max(a, b))

    def river_width(a, b):
        # Palette indices 0–2 are junction markers and inherit their neighbors' width.
        return max(3, pixels[a], pixels[b])

    ends = {a for a, steps in neighbors.items()
            if len(steps) != 2 or river_width(a, steps[0]) != river_width(a, steps[1])}
    unused = {(a, b) for a, steps in neighbors.items() for b in steps if a < b}

    def follow(start, next_pixel):
        width_class = river_width(start, next_pixel)
        vertices = [start]
        previous, current = start, next_pixel
        while True:
            unused.remove(edge(previous, current))
            if current in ends:
                break
            steps = neighbors[current]
            following = steps[1] if steps[0] == previous else steps[0]
            if edge(current, following) not in unused:
                break
            if current - previous != following - current:
                vertices.append(current)
            previous, current = current, following
        vertices.append(current)
        return {
            "width": width_class,
            "points": [{"x": p % width + .5, "y": p // width + .5} for p in vertices],
        }

    lines = []
    for starts in (ends, pixels):
        # After splitting normal chains, any remaining edges are closed loops.
        for a, steps in neighbors.items():
            if a in starts:
                for b in steps:
                    if edge(a, b) in unused:
                        lines.append(follow(a, b))
    return lines


def load_magna_rivers(magna, size):
    if size != SIZE:
        raise ValueError("reference must match the pinned Magna map sheet")
    data = subprocess.check_output(["git", "-C", str(magna), "show", f"{COMMIT}:{BITMAP}"])
    if f"sha256:{sha256(data).hexdigest()}" != SOURCE_HASH:
        raise ValueError("river bitmap hash differs from the pinned source")
    with Image.open(BytesIO(data)) as image:
        if image.size != SIZE or image.mode != "P":
            raise ValueError("expected the indexed river bitmap for the pinned map sheet")
        pixels = {index: value for index, value in enumerate(image.tobytes()) if value <= 11}
    return {"source": SOURCE, "sourceHash": SOURCE_HASH, "lines": river_lines(pixels, SIZE[0])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--magna", type=Path, required=True, help="pinned Magna Git checkout")
    parser.add_argument("--reference", type=Path, required=True, help="existing geo-reference.json")
    parser.add_argument("--out", type=Path, required=True, help="reference document with river centerlines")
    args = parser.parse_args()

    reference = json.loads(args.reference.read_text(encoding="utf-8"))
    if (reference.get("format"), reference.get("baseHash")) != ("worldsim-geo-v1", BASE_HASH):
        parser.error("reference must match the pinned Magna map sheet")
    reference["rivers"] = load_magna_rivers(args.magna, (reference.get("width"), reference.get("height")))
    args.out.write_text(json.dumps(reference, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"{len(reference['rivers']['lines']):,} river centerlines")


if __name__ == "__main__":
    main()
