"""Makes the Play store graphics (#78) from the design and the launcher icon.

    python scripts/store/graphics.py

writes into app/src/main/play/listings/en-US/graphics/:
- icon/icon.png, 512 x 512: the Clipra mark (design/logo/clipra-mark.svg) on the accent colour, full
  bleed, since Play rounds the corners itself;
- phone-screenshots/NN.png, 1080 x 1920, and large-tablet-screenshots/NN.png, 2560 x 1440: design
  boards (design/screens/) under a caption.

Other languages show these unless they have their own. Not made here: the feature graphics of both
languages and the Polish screenshots (pl-PL/graphics/), which are captures of the app on a phone and a
tablet (docs/release/publishing.md). Needs Pillow. Colours come from design/tokens.json and the fonts
from core/fonts, so the graphics match the app.
"""

import json
import re
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'app/src/main/play/listings/en-US/graphics'
TOKENS = json.loads((ROOT / 'design/tokens.json').read_text(encoding='utf-8'))['color']
DISPLAY = ROOT / 'core/fonts/src/main/res/font/bricolage_grotesque.ttf'
SCALE = 4  # Drawn larger, then scaled down, for smooth edges.
LOGO = ROOT / 'design/logo/clipra-mark.svg'
LOGO_BOX = 1254  # the logo's viewBox

# Boards without prototype placeholders (no price, no ad, no model sizes) and without features
# still to come (offline translation, #47), in store order. A board with a height shows only its
# top part.
PHONE = [
    ('phone/Editor.png', 'Anything on any layer, with keyframes and blend modes'),
    ('phone/Transcript.png', 'Edit the video by editing what was said'),
    # Without the bottom bar, which offers translation.
    ('states/CaptionsEdit--karaoke.png', 'Auto captions, on your phone', 1516),
    ('phone/Cutout.png', 'Remove the background behind people'),
    ('phone/Reframe.png', 'Reframe for every screen, following the subject'),
    ('phone/Audio.png', 'Even loudness and clean voices, clip by clip'),
    ('phone/Filters.png', '24 filters and 24 transitions'),
]
TABLET = [
    ('tablet/TabletEditor.png', 'A full editor on your tablet'),
    ('tablet/TabletEffects.png', 'Filters, effects and masks beside the picture'),
    ('tablet/TabletTranscript.png', 'Cut filler words and pauses in one tap'),
]


def rgb(name):
    return TOKENS[name]


def font(size, weight=800):
    f = ImageFont.truetype(str(DISPLAY), size)
    f.set_variation_by_axes([96, weight, 100])
    return f


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



def wrap(draw, text, face, width):
    words, lines = text.split(), ['']
    for word in words:
        line = (lines[-1] + ' ' + word).strip()
        if draw.textlength(line, font=face) <= width or not lines[-1]:
            lines[-1] = line
        else:
            lines.append(word)
    return lines


def screenshot(board, caption, width, height, caption_size, board_height, crop=None):
    """[board] under [caption], centred on the app's background; [crop] keeps its top part."""
    image = Image.new('RGB', (width, height), rgb('background'))
    draw = ImageDraw.Draw(image)
    face = font(caption_size)
    top = round(height * 0.05)
    for line in wrap(draw, caption, face, width * 0.86):
        draw.text((width / 2, top), line, font=face, fill=rgb('textPrimary'), anchor='mt')
        top += round(caption_size * 1.18)

    source = Image.open(ROOT / 'design/screens' / board).convert('RGB')
    scale = board_height / source.height
    if crop:
        source = source.crop((0, 0, source.width, crop))
    shown = source.resize((round(source.width * scale), round(source.height * scale)),
                          Image.LANCZOS)
    left = (width - shown.width) // 2
    below = height - board_height - round(height * 0.04)
    radius = round(shown.width * 0.05)
    mask = Image.new('L', shown.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, shown.width - 1, shown.height - 1],
                                           radius=radius, fill=255)
    border = 3
    draw.rounded_rectangle([left - border, below - border, left + shown.width + border - 1,
                            below + shown.height + border - 1], radius=radius + border,
                           fill=rgb('borderStrong'))
    image.paste(shown, (left, below), mask)
    return image


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    print(path.relative_to(ROOT), image.size)


def main():
    save(icon(), OUT / 'icon/icon.png')
    for number, (board, caption, *crop) in enumerate(PHONE, start=1):
        image = screenshot(board, caption, 1080, 1920, caption_size=64, board_height=1540,
                           crop=crop[0] if crop else None)
        save(image, OUT / f'phone-screenshots/{number:02}.png')
    for number, (board, caption) in enumerate(TABLET, start=1):
        image = screenshot(board, caption, 2560, 1440, caption_size=72, board_height=1150)
        save(image, OUT / f'large-tablet-screenshots/{number:02}.png')


if __name__ == '__main__':
    main()
