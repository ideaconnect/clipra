"""Makes the Play store icon (#78) from the design.

    python scripts/store/graphics.py

writes app/src/main/play/listings/en-US/graphics/icon/icon.png, 512 x 512: the Clipra mark
(design/logo/clipra-mark.svg) on the accent colour, full bleed, since Play rounds the corners
itself.

Not made here: the feature graphics of both languages, and the screenshots, which are captures
of the app on emulators composed by scripts/store/screenshots.py (#282,
docs/release/publishing.md). Needs Pillow. Colours come from design/tokens.json, so the icon
matches the app.
"""

import json
import re
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'app/src/main/play/listings/en-US/graphics'
TOKENS = json.loads((ROOT / 'design/tokens.json').read_text(encoding='utf-8'))['color']
SCALE = 4  # Drawn larger, then scaled down, for smooth edges.
LOGO = ROOT / 'design/logo/clipra-mark.svg'
LOGO_BOX = 1254  # the logo's viewBox


def rgb(name):
    return TOKENS[name]


def logo_subpaths():
    """The mark's outlines (design/logo/clipra-mark.svg) as polygons in its LOGO_BOX unit box.

    The path uses only absolute M, L, C and Z commands, so the cubic curves are flattened here
    and Pillow can fill them. The mark is filled even-odd: the holes are subpaths too.
    """
    d = re.search(r' d="([^"]+)"', LOGO.read_text(encoding='utf-8')).group(1)
    tokens = re.findall(r'[MLCZ]|-?\d+(?:\.\d+)?', d)
    polygons, points, i = [], [], 0
    while i < len(tokens):
        command = tokens[i]
        if command == 'M':
            if points:
                polygons.append(points)
            points = [(float(tokens[i + 1]), float(tokens[i + 2]))]
            i += 3
        elif command == 'L':
            points.append((float(tokens[i + 1]), float(tokens[i + 2])))
            i += 3
        elif command == 'C':
            (x0, y0) = points[-1]
            x1, y1, x2, y2, x3, y3 = (float(t) for t in tokens[i + 1:i + 7])
            for step in range(1, 17):
                t = step / 16
                u = 1 - t
                points.append((u ** 3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3,
                               u ** 3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3))
            i += 7
        elif command == 'Z':
            polygons.append(points)
            points = []
            i += 1
        else:
            raise ValueError(f'unexpected path command {command}')
    if points:
        polygons.append(points)
    return polygons


def draw_mark(image, left, top, size, colour):
    """Fills the mark into [image], scaled from the logo's box to [size] at (left, top)."""
    scale = size / LOGO_BOX
    mask = Image.new('1', image.size, 0)
    for polygon in logo_subpaths():
        layer = Image.new('1', image.size, 0)
        ImageDraw.Draw(layer).polygon([(left + x * scale, top + y * scale) for x, y in polygon],
                                      fill=1)
        mask = ImageChops.logical_xor(mask, layer)
    image.paste(colour, (0, 0), mask.convert('L'))


def icon():
    # A full-bleed square: Play rounds the corners itself. The mark keeps the logo's proportions.
    size = 512 * SCALE
    image = Image.new('RGB', (size, size), rgb('accent'))
    draw_mark(image, 0, 0, size, rgb('onAccent'))
    return image.resize((512, 512), Image.LANCZOS)


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    print(path.relative_to(ROOT), image.size)


def main():
    save(icon(), OUT / 'icon/icon.png')


if __name__ == '__main__':
    main()
