"""Makes the Play store screenshots (#282) from captures of the app on emulators.

    python scripts/store/screenshots.py CAPTURES [en-p pl-t ...]

Reads CAPTURES/<lang>-<device>-<name>.png (device p = phone, s = 7-inch tablet, t = 10-inch
tablet; captures of the app on emulators, made as docs/release/publishing.md describes) and
writes app/src/main/play/listings/<locale>/graphics/{phone,tablet,large-tablet}-screenshots/NN.png:
the app's screen without the system bars, in a rounded frame, under a headline that says what
it shows and a "No watermark" badge, on the app's background, at the size Play asks for. The
filters (language-device) limit what is written. Needs Pillow; colours come from
design/tokens.json and the fonts from core/fonts, so the graphics match the app.
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / 'build/store-capture/raw'
OUT = ROOT / 'app/src/main/play/listings'
TOKENS = json.loads((ROOT / 'design/tokens.json').read_text(encoding='utf-8'))['color']
DISPLAY = ROOT / 'core/fonts/src/main/res/font/bricolage_grotesque.ttf'
BODY = ROOT / 'core/fonts/src/main/res/font/geist.ttf'


def hex_rgb(value):
    value = value.lstrip('#')
    return tuple(int(value[i:i + 2], 16) for i in (0, 2, 4))


BG = hex_rgb(TOKENS['background'])
SURFACE = hex_rgb(TOKENS['surface'])
TEXT = hex_rgb(TOKENS['textPrimary'])
TEXT2 = hex_rgb(TOKENS['textSecondary'])
ACCENT = hex_rgb(TOKENS['accent'])
ON_ACCENT = hex_rgb(TOKENS['onAccent'])
BORDER = hex_rgb(TOKENS['borderStrong'])

# Per device: canvas size, the capture's system bars to cut (top, bottom, in capture pixels),
# the folder Play's layout uses, and the type scale.
DEVICES = {
    'p': dict(size=(1080, 1920), bars=(132, 63), folder='phone-screenshots', unit=1.0),
    's': dict(size=(1440, 2560), bars=(48, 64), folder='tablet-screenshots', unit=1.333),
    't': dict(size=(2560, 1440), bars=(48, 64), folder='large-tablet-screenshots', unit=1.25),
}

BADGE = {'en': 'No watermark', 'pl': 'Bez znaku wodnego'}

# Headline (the part in *stars* in the accent colour) and the line under it.
PHONE = [
    ('01-editor', {
        'en': ('Anything on *any layer*', 'Video, text, captions and music, stacked on one timeline'),
        'pl': ('Wszystko na *każdej warstwie*', 'Wideo, tekst, napisy i muzyka na jednej osi czasu')}),
    ('02-pip', {
        'en': ('Put a clip *over a clip*', 'Picture in picture: move, scale and turn it in the preview'),
        'pl': ('Klip *na klipie*', 'Obraz w obrazie: przesuwaj, skaluj i obracaj go w podglądzie')}),
    ('03-layers', {
        'en': ('Higher layers *cover lower ones*', 'Hide, mute or lock any layer. Every layer plays sound'),
        'pl': ('Wyższe warstwy *przykrywają niższe*', 'Ukryj, wycisz lub zablokuj warstwę. Każda gra dźwięk')}),
    ('03-cutout', {
        'en': ('Put people over *any background*', 'AI cutout on your phone, no green screen needed'),
        'pl': ('Postaw ludzi na *dowolnym tle*', 'Wycinanie AI na telefonie, bez zielonego ekranu')}),
    ('04-text', {
        'en': ('Titles and *text* over your video', 'Text effects, colours, cards and your own templates'),
        'pl': ('Tytuły i *teksty* na filmie', 'Efekty tekstu, kolory, plansze i własne szablony')}),
    ('05-captions', {
        'en': ('*Auto captions,* made on your phone', 'Whisper writes them offline, with a karaoke highlight'),
        'pl': ('*Napisy AI* tworzone na telefonie', 'Whisper pisze je offline, z podświetleniem jak w karaoke')}),
    ('06-transcript', {
        'en': ('Edit the video by *editing the words*', 'Cut filler words and long pauses in one tap'),
        'pl': ('Montuj film, *edytując tekst*', 'Wytnij wtrącenia i długie pauzy jednym dotknięciem')}),
    ('08-export', {
        'en': ('*No watermark.* Ever.', 'Export straight to your Gallery, up to 4K where your phone can'),
        'pl': ('*Bez znaku wodnego.* Nigdy.', 'Eksport prosto do Galerii, do 4K, jeśli telefon to obsługuje')}),
]

TABLET = [
    ('01-editor', {
        'en': ('A *full editor* on your tablet', 'Media, preview and inspector side by side, over a layered timeline'),
        'pl': ('*Pełny edytor* na tablecie', 'Multimedia, podgląd i inspektor obok siebie, nad osią czasu z warstwami')}),
    ('02-text', {
        'en': ('Titles and *text* over your video', 'Templates, text effects, colours and animations'),
        'pl': ('Tytuły i *teksty* na filmie', 'Szablony, efekty tekstu, kolory i animacje')}),
    ('03-captions', {
        'en': ('*Auto captions,* made on your tablet', 'Whisper writes them offline, in the language spoken'),
        'pl': ('*Napisy AI* tworzone na tablecie', 'Whisper pisze je offline, w języku nagrania')}),
    ('04-transcript', {
        'en': ('Edit the video by *editing the words*', 'Cuts show on the timeline and keep every layer in sync'),
        'pl': ('Montuj film, *edytując tekst*', 'Cięcia widać na osi czasu, a wszystkie warstwy zostają w synchronizacji')}),
    ('05-cutout', {
        'en': ('Put people over *any background*', 'AI cutout on your tablet, no green screen needed'),
        'pl': ('Postaw ludzi na *dowolnym tle*', 'Wycinanie AI na tablecie, bez zielonego ekranu')}),
    ('06-effects', {
        'en': ('*Filters and effects*, clip by clip', '24 filters, 24 transitions, masks and colour'),
        'pl': ('*Filtry i efekty*, klip po klipie', '24 filtry, 24 przejścia, maski i kolor')}),
    ('07-audio', {
        'en': ('*Clean voices*, even loudness', 'AI voice cleanup and loudness normalization on the device'),
        'pl': ('*Czyste głosy*, równa głośność', 'Czyszczenie głosu AI i normalizacja głośności na urządzeniu')}),
    ('08-export', {
        'en': ('*No watermark.* Ever.', 'Export from the original media, up to 4K where your tablet can'),
        'pl': ('*Bez znaku wodnego.* Nigdy.', 'Eksport z oryginałów, do 4K, jeśli tablet to obsługuje')}),
]

def on_tablet(shots):
    """The phone copy for the 7-inch tablet, which shows the phone layout upright."""
    words = [('on your phone', 'on your tablet'), ('your phone can', 'your tablet can'),
             ('na telefonie', 'na tablecie'), ('jeśli telefon', 'jeśli tablet')]

    def swap(text):
        for phone, tablet in words:
            text = text.replace(phone, tablet)
        return text

    return [(name, {lang: tuple(swap(part) for part in copy) for lang, copy in texts.items()})
            for name, texts in shots]


SETS = {'p': PHONE, 's': on_tablet(PHONE), 't': TABLET}


def face(path, size, weight):
    f = ImageFont.truetype(str(path), round(size))
    try:
        axes = f.get_variation_axes()
        values = []
        for axis in axes:
            name = axis.get('name', b'')
            name = name.decode() if isinstance(name, bytes) else str(name)
            if name.lower().startswith('weight'):
                values.append(weight)
            else:
                values.append(axis.get('default', axis['minimum']))
        f.set_variation_by_axes(values)
    except OSError:
        pass
    return f


def runs(headline):
    """[(text, accent)] from 'Plain *accented* plain'."""
    parts = headline.split('*')
    return [(p, i % 2 == 1) for i, p in enumerate(parts) if p]


def layout_words(draw, headline, font, width):
    """Words with their accent flag, wrapped into lines no wider than [width], balanced."""
    words = []
    previous = ''
    for text, accent in runs(headline):
        tokens = text.split()
        if tokens and words and not text[0].isspace() and not previous[-1:].isspace():
            # Punctuation right after an accented part ("*Clean voices*, even") stays with it.
            words[-1] = (words[-1][0] + tokens.pop(0), words[-1][1])
        for word in tokens:
            words.append((word, accent))
        previous = text

    def line_width(line):
        return draw.textlength(' '.join(w for w, _ in line), font=font)

    lines, line = [], []
    for word in words:
        if line and line_width(line + [word]) > width:
            lines.append(line)
            line = [word]
        else:
            line.append(word)
    lines.append(line)
    if len(lines) == 2:
        flat = lines[0] + lines[1]
        best = min(range(1, len(flat)), key=lambda i: max(line_width(flat[:i]), line_width(flat[i:])))
        lines = [flat[:best], flat[best:]]
    return lines


def wrap(draw, text, font, width):
    lines, line = [], ''
    for word in text.split():
        trial = (line + ' ' + word).strip()
        if line and draw.textlength(trial, font=font) > width:
            lines.append(line)
            line = word
        else:
            line = trial
    lines.append(line)
    return lines


def check(draw, x, y, size, colour, width):
    draw.line([(x, y + size * 0.52), (x + size * 0.38, y + size * 0.88), (x + size, y + size * 0.12)],
              fill=colour, width=width, joint='curve')


def badge(canvas, text, x, y, unit):
    draw = ImageDraw.Draw(canvas)
    font = face(BODY, 30 * unit, 700)
    h = round(58 * unit)
    pad = round(24 * unit)
    icon = round(24 * unit)
    gap = round(12 * unit)
    tw = draw.textlength(text, font=font)
    w = round(pad + icon + gap + tw + pad)
    left = x - w // 2
    draw.rounded_rectangle([left, y, left + w, y + h], radius=h // 2, fill=ACCENT)
    check(draw, left + pad, y + (h - icon) / 2, icon, ON_ACCENT, max(3, round(5 * unit)))
    draw.text((left + pad + icon + gap, y + h / 2), text, font=font, fill=ON_ACCENT, anchor='lm')
    return h


def background(size):
    w, h = size
    canvas = Image.new('RGB', size, BG)
    glow = Image.new('L', size, 0)
    ImageDraw.Draw(glow).ellipse([w * 0.1, -h * 0.25, w * 0.9, h * 0.35], fill=60)
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) * 0.12))
    tint = Image.new('RGB', size, (60, 50, 14))
    canvas.paste(tint, (0, 0), glow)
    return canvas


def draw_headline(draw, line, font, x, y):
    for i, (word, accent) in enumerate(line):
        piece = word + (' ' if i < len(line) - 1 else '')
        draw.text((x, y), piece, font=font, fill=ACCENT if accent else TEXT)
        x += draw.textlength(piece, font=font)


def line_width(draw, line, font):
    return draw.textlength(' '.join(word for word, _ in line), font=font)


def badge_width(draw, text, unit):
    font = face(BODY, 30 * unit, 700)
    return round(24 * unit + 24 * unit + 12 * unit + draw.textlength(text, font=font) + 24 * unit)


def compose(device, lang, name, headline, sub, with_badge=True):
    spec = DEVICES[device]
    w, h = spec['size']
    unit = spec['unit']
    landscape = w > h
    canvas = background((w, h))
    draw = ImageDraw.Draw(canvas)
    margin = round((56 if landscape else 60) * unit)
    top = round((40 if landscape else 56) * unit)
    badge_h = round(58 * unit)
    if landscape:
        # One row: the badge, then the headline, centred together; one line under them.
        gap = round(28 * unit)
        bw = badge_width(draw, BADGE[lang], unit) + gap if with_badge else 0
        size = 64 * unit
        while True:
            head = face(DISPLAY, size, 800)
            lines = layout_words(draw, headline, head, 10 ** 6)
            if line_width(draw, lines[0], head) + bw <= w - 2 * margin:
                break
            size *= 0.95
        row_h = round(64 * unit * 1.15)
        total = bw + line_width(draw, lines[0], head)
        x = (w - total) / 2
        if with_badge:
            badge(canvas, BADGE[lang], round(x + (bw - gap) / 2), top + (row_h - badge_h) // 2, unit)
        draw_headline(draw, lines[0], head, x + bw, top + round((row_h - size * 1.15) / 2))
        y = top + row_h + round(6 * unit)
        sub_size = 30 * unit
        small = face(BODY, sub_size, 500)
        while draw.textlength(sub, font=small) > w - 2 * margin:
            sub_size *= 0.96
            small = face(BODY, sub_size, 500)
        draw.text((w / 2, y), sub, font=small, fill=TEXT2, anchor='ma')
        y += round(30 * unit * 1.3) + round(30 * unit)
    else:
        head_lh = round(70 * unit * 1.12)
        sub_size = 33 * unit
        sub_lh = round(sub_size * 1.3)
        text_width = w - 2 * margin
        size = 70 * unit
        while True:
            head = face(DISPLAY, size, 800)
            lines = layout_words(draw, headline, head, text_width)
            if len(lines) <= 2:
                break
            size *= 0.95
        small = face(BODY, sub_size, 500)
        sub_lines = wrap(draw, sub, small, text_width)
        badge_row = badge_h + round(24 * unit)
        # Room for the badge, two headline lines and two lines under them, so every screenshot
        # of a set shows the app at the same size; the text is centred in that room.
        header = badge_row + 2 * head_lh + round(6 * unit) + 2 * sub_lh
        used = (badge_row if with_badge else 0) + len(lines) * head_lh + round(6 * unit) + \
            len(sub_lines) * sub_lh
        y = top + (header - used) // 2
        if with_badge:
            badge(canvas, BADGE[lang], w // 2, y, unit)
            y += badge_row
        for line in lines:
            draw_headline(draw, line, head, (w - line_width(draw, line, head)) / 2, y)
            y += head_lh
        y += round(6 * unit)
        for line in sub_lines:
            draw.text((w / 2, y), line, font=small, fill=TEXT2, anchor='ma')
            y += sub_lh
        y = top + header + round(36 * unit)

    shot = Image.open(RAW / f'{lang}-{device}-{name}.png').convert('RGB')
    cut_top, cut_bottom = spec['bars']
    shot = shot.crop((0, cut_top, shot.width, shot.height - cut_bottom))
    room_h = h - y - round((36 if landscape else 56) * unit)
    room_w = w - 2 * margin
    scale = min(room_h / shot.height, room_w / shot.width)
    shown = shot.resize((round(shot.width * scale), round(shot.height * scale)), Image.LANCZOS)
    left = (w - shown.width) // 2
    radius = round(min(shown.width, shown.height) * (0.035 if landscape else 0.06))
    shadow = Image.new('L', (w, h), 0)
    ImageDraw.Draw(shadow).rounded_rectangle(
        [left, y + round(16 * unit), left + shown.width, y + shown.height + round(16 * unit)],
        radius=radius, fill=150)
    shadow = shadow.filter(ImageFilter.GaussianBlur(round(28 * unit)))
    canvas.paste(Image.new('RGB', (w, h), (0, 0, 0)), (0, 0), shadow)
    border = max(3, round(4 * unit))
    ImageDraw.Draw(canvas).rounded_rectangle(
        [left - border, y - border, left + shown.width + border - 1, y + shown.height + border - 1],
        radius=radius + border, fill=BORDER)
    mask = Image.new('L', shown.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, shown.width - 1, shown.height - 1], radius=radius,
                                           fill=255)
    canvas.paste(shown, (left, y), mask)
    return canvas


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('captures', type=Path, help='folder with <lang>-<device>-<name>.png')
    parser.add_argument('sets', nargs='*', help='only these sets, e.g. en-p pl-t')
    parser.add_argument('--out', type=Path, default=OUT, help='listings folder to write')
    args = parser.parse_args()
    global RAW
    RAW = args.captures
    out = args.out
    only = args.sets
    for lang in ('en', 'pl'):
        locale = {'en': 'en-US', 'pl': 'pl-PL'}[lang]
        for device, shots in SETS.items():
            if only and f'{lang}-{device}' not in only:
                continue
            folder = out / locale / 'graphics' / DEVICES[device]['folder']
            folder.mkdir(parents=True, exist_ok=True)
            for old in folder.glob('*.png'):
                old.unlink()
            for number, (name, copy) in enumerate(shots, start=1):
                headline, sub = copy[lang]
                image = compose(device, lang, name, headline, sub,
                                with_badge=not name.startswith('08'))
                path = folder / f'{number:02}.png'
                image.save(path, optimize=True)
                print(path.relative_to(out), image.size, f'{path.stat().st_size // 1024} KB')


if __name__ == '__main__':
    main()
