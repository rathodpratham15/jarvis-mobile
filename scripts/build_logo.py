#!/usr/bin/env python3
"""Draw the JARVIS logo (a chevron mark over the green orb) and write the Android launcher icons.

The mark is simple geometry drawn here, so it stays sharp at every size.

    python scripts/build_logo.py [A|B]        # A: chevron over an orb glow (default), B: chevron inside a segmented ring
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

RES = os.path.join(os.path.dirname(__file__), "..", "app", "android", "app", "src", "main", "res")
BG_TOP, BG_BOT = (7, 14, 24), (13, 37, 64)          # --bg-start / --surface-base
EMERALD = (52, 211, 153)                              # the orb colour
S, SS = 1024, 2                                       # master size, supersampling


def chevron(d, k, color, cx=512, apex_y=372, spread=190, drop=250, width=84):
    pts = [(cx - spread, apex_y + drop), (cx, apex_y), (cx + spread, apex_y + drop)]
    pts = [(x * k, y * k) for x, y in pts]
    d.line(pts, fill=color, width=int(width * k), joint="curve")
    r = width * k / 2
    for x, y in (pts[0], pts[2]):
        d.ellipse((x - r, y - r, x + r, y + r), fill=color)


def glow(size, cx, cy, radius, color, strength):
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    dist = np.sqrt((x - cx) ** 2 + (y - cy) ** 2) / radius
    a = np.clip(1.0 - dist, 0, 1) ** 2.2 * strength
    arr = np.zeros((size, size, 4), np.float32)
    arr[..., 0], arr[..., 1], arr[..., 2], arr[..., 3] = color[0], color[1], color[2], a * 255
    return Image.fromarray(arr.astype(np.uint8), "RGBA")


def foreground(variant):
    """Transparent 1024 canvas = the 108dp adaptive-icon layer; everything stays inside the centre safe zone."""
    k = SS
    img = Image.new("RGBA", (S * k, S * k), (0, 0, 0, 0))
    if variant == "A":
        img.alpha_composite(glow(S * k, 512 * k, 560 * k, 330 * k, EMERALD, 0.85))
        d = ImageDraw.Draw(img)
        chevron(d, k, (255, 255, 255, 255))
        r = 34 * k                                                     # the small orb under the chevron
        d.ellipse((512 * k - r, 712 * k - r, 512 * k + r, 712 * k + r), fill=EMERALD + (255,))
    else:
        d = ImageDraw.Draw(img)
        cx = cy = 512 * k
        R, w = 290 * k, 30 * k
        for i in range(8):                                             # segmented ring
            a0 = i * 45 + 6
            d.arc((cx - R, cy - R, cx + R, cy + R), a0, a0 + 33, fill=EMERALD + (255,), width=int(w))
        img.alpha_composite(glow(S * k, cx, cy, 250 * k, EMERALD, 0.35))
        d = ImageDraw.Draw(img)
        chevron(d, k, (255, 255, 255, 255), apex_y=402, spread=130, drop=170, width=64)
    return img.resize((S, S), Image.LANCZOS)


def background():
    t = np.linspace(0, 1, S, dtype=np.float32)[:, None]
    a = np.zeros((S, S, 3), np.float32)
    for c in range(3):
        a[..., c] = BG_TOP[c] + (BG_BOT[c] - BG_TOP[c]) * t
    return Image.fromarray(a.astype(np.uint8), "RGB").convert("RGBA")


def legacy(fg, shape):
    """Full square/round icon for old launchers: background + foreground scaled to fill the visible 72dp window."""
    img = background()
    img.alpha_composite(fg.resize((round(S * 1.25), round(S * 1.25)), Image.LANCZOS).crop((128, 128, 128 + S, 128 + S)))
    mask = Image.new("L", (S, S), 0)
    md = ImageDraw.Draw(mask)
    if shape == "round":
        md.ellipse((0, 0, S - 1, S - 1), fill=255)
    else:
        md.rounded_rectangle((0, 0, S - 1, S - 1), radius=int(S * 0.22), fill=255)
    img.putalpha(mask)
    return img


def main(variant):
    fg = foreground(variant)
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, f in dens.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        fg.resize((round(108 * f), round(108 * f)), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        legacy(fg, "square").resize((round(48 * f), round(48 * f)), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"), optimize=True)
        legacy(fg, "round").resize((round(48 * f), round(48 * f)), Image.LANCZOS).save(os.path.join(d, "ic_launcher_round.png"), optimize=True)
    with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#0B1C2E</color>\n</resources>\n')
    legacy(fg, "square").save(os.path.join(os.path.dirname(__file__), "..", "docs", "logo.png"), optimize=True)
    print("launcher icons written for variant", variant)


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[2] == "preview":
        pass
    main(sys.argv[1] if len(sys.argv) > 1 else "A")
