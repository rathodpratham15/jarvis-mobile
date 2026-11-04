#!/usr/bin/env python3
"""Draw the JARVIS logo (a sharp white ^ on black) and write the Android launcher icons.

The mark is simple geometry drawn here, so it stays sharp at every size.

    python scripts/build_logo.py
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "app", "android", "app", "src", "main", "res")
BG_TOP, BG_BOT = (0, 0, 0), (0, 0, 0)                 # pure black
S, SS = 1024, 2                                       # master size, supersampling


def foreground():
    """Transparent 1024 canvas = the 108dp adaptive-icon layer: a sharp white ^ (flat ends, mitred apex) in the centre safe zone."""
    k = SS
    T, B, X, W = 330, 700, 230, 100                      # apex y, foot y, half width at the feet, horizontal stroke width
    h = W * (B - T) / X                                  # keeps the inner edge parallel to the outer edge
    pts = [(512 - X, B), (512, T), (512 + X, B), (512 + X - W, B), (512, T + h), (512 - X + W, B)]
    img = Image.new("RGBA", (S * k, S * k), (0, 0, 0, 0))
    ImageDraw.Draw(img).polygon([(x * k, y * k) for x, y in pts], fill=(255, 255, 255, 255))
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


def main():
    fg = foreground()
    dens = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for name, f in dens.items():
        d = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(d, exist_ok=True)
        fg.resize((round(108 * f), round(108 * f)), Image.LANCZOS).save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        legacy(fg, "square").resize((round(48 * f), round(48 * f)), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"), optimize=True)
        legacy(fg, "round").resize((round(48 * f), round(48 * f)), Image.LANCZOS).save(os.path.join(d, "ic_launcher_round.png"), optimize=True)
    with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w") as fh:
        fh.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#000000</color>\n</resources>\n')
    legacy(fg, "square").save(os.path.join(os.path.dirname(__file__), "..", "docs", "logo.png"), optimize=True)
    print("launcher icons written")


if __name__ == "__main__":
    main()
