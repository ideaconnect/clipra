"""Makes the Play store graphics (#78) from the design and the launcher icon.

    python scripts/store/graphics.py

writes into app/src/main/play/listings/en-US/graphics/:
- icon/icon.png, 512 x 512: the launcher icon (app/src/main/res/drawable/ic_launcher_foreground.xml)
  on its background, drawn from the same shapes;
- feature-graphic/feature-graphic.png, 1024 x 500: the logo and the first-run promise;
- phone-screenshots/NN.png, 1080 x 1920, and large-tablet-screenshots/NN.png, 2560 x 1440: design
  boards (design/screens/) under a caption.

Other languages show these unless they have their own. Needs Pillow. Colours come from
design/tokens.json and the fonts from core/fonts, so the graphics match the app.
"""

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'app/src/main/play/listings/en-US/graphics'
TOKENS = json.loads((ROOT / 'design/tokens.json').read_text(encoding='utf-8'))['color']
DISPLAY = ROOT / 'core/fonts/src/main/res/font/bricolage_grotesque.ttf'
SCALE = 4  # Drawn larger, then scaled down, for smooth edges.

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


def logo_tile(draw, left, top, size, cut=True):
    """The launcher icon's tile: an accent square with rounded corners and a play cut-out.

    In the vector's 108 unit viewport the tile spans 30 to 78 with a radius of 12, and the
    play triangle is (48,42) (48,66) (67,54).
    """
    unit = size / 48
    draw.rounded_rectangle([left, top, left + size, top + size], radius=12 * unit,
                           fill=rgb('accent'))
    if cut:
        def point(x, y):
            return left + (x - 30) * unit, top + (y - 30) * unit
        draw.polygon([point(48, 42), point(48, 66), point(67, 54)], fill=rgb('background'))


def icon():
    # The store icon is the adaptive icon's visible 72 units (18 to 90): the tile is 48 of them.
    size = 512 * SCALE
    image = Image.new('RGB', (size, size), rgb('background'))
    tile = size * 48 / 72
    logo_tile(ImageDraw.Draw(image), (size - tile) / 2, (size - tile) / 2, tile)
    return image.resize((512, 512), Image.LANCZOS)


def feature_graphic():
    width, height = 1024 * SCALE, 500 * SCALE
    image = Image.new('RGB', (width, height), rgb('background'))
    draw = ImageDraw.Draw(image)
    margin = 72 * SCALE
    tile = 76 * SCALE
    logo_tile(draw, margin, 84 * SCALE, tile)
    draw.text((margin + tile + 24 * SCALE, 84 * SCALE + tile / 2), 'Clipra',
              font=font(64 * SCALE), fill=rgb('textPrimary'), anchor='lm')
    lines = [('No account.', 'textPrimary'), ('No upload.', 'textPrimary'),
             ('No watermark.', 'accent')]
    y = 214 * SCALE
    for text, colour in lines:
        draw.text((margin, y), text, font=font(62 * SCALE), fill=rgb(colour))
        y += 70 * SCALE
    return image.resize((1024, 500), Image.LANCZOS)


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
    save(feature_graphic(), OUT / 'feature-graphic/feature-graphic.png')
    for number, (board, caption, *crop) in enumerate(PHONE, start=1):
        image = screenshot(board, caption, 1080, 1920, caption_size=64, board_height=1540,
                           crop=crop[0] if crop else None)
        save(image, OUT / f'phone-screenshots/{number:02}.png')
    for number, (board, caption) in enumerate(TABLET, start=1):
        image = screenshot(board, caption, 2560, 1440, caption_size=72, board_height=1150)
        save(image, OUT / f'large-tablet-screenshots/{number:02}.png')


if __name__ == '__main__':
    main()
