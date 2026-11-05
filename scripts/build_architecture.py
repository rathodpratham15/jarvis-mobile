#!/usr/bin/env python3
"""Draw docs/architecture.svg and docs/architecture.png (needs rsvg-convert for the PNG)."""
import os, subprocess
from xml.sax.saxutils import escape

W, H = 1800, 1120
out = []
def add(s): out.append(s)

def text(x, y, s, size=22, fill="#FFFFFF", weight="400", anchor="middle"):
    add(f'<text x="{x}" y="{y}" font-family="Helvetica, Arial, sans-serif" font-size="{size}" font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{escape(s)}</text>')

def box(x, y, w, h, title, lines=(), dash=False):
    d = ' stroke-dasharray="8 7"' if dash else ""
    add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="14" fill="#111111" stroke="#FFFFFF" stroke-width="2"{d}/>')
    text(x + w / 2, y + 38, title, 25, weight="700")
    for i, l in enumerate(lines):
        text(x + w / 2, y + 70 + i * 26, l, 18, "#A3A3A3")

def arrow(x1, y1, x2, y2, dash=False, both=False):
    d = ' stroke-dasharray="7 6"' if dash else ""
    ms = ' marker-start="url(#a)"' if both else ""
    add(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="#D4D4D4" stroke-width="2.5"{d} marker-end="url(#a)"{ms}/>')

add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">')
add('<defs><marker id="a" markerWidth="12" markerHeight="12" refX="10" refY="6" orient="auto-start-reverse"><path d="M1,1 L11,6 L1,11 z" fill="#D4D4D4"/></marker></defs>')
add(f'<rect width="{W}" height="{H}" fill="#000000"/>')
text(60, 62, "JARVIS Mobile", 38, weight="700", anchor="start")
text(335, 62, "architecture", 38, "#737373", anchor="start")

labels = []
def band(y, h, label, bottom=False):
    add(f'<rect x="30" y="{y}" width="{W-60}" height="{h}" rx="20" fill="#0A0A0A" stroke="#2A2A2A" stroke-width="2"/>')
    labels.append((y + h - 62 if bottom else y, label, bottom))

# --- phone ---
band(100, 330, "ON THE PHONE  ·  Android app")
box(60, 170, 280, 150, "“Hey Jarvis”", ["Vosk wake word", "runs on the phone", "opens the panel"])
box(370, 170, 280, 150, "Voice panel", ["mic · answer cards", "Stop button", "mission checklist"])
box(680, 170, 280, 150, "Accessibility service", ["reads the screen,", "taps, types, scrolls", "safety gate first"])
box(990, 170, 280, 150, "Live scan + guide", ["CameraX, tap to focus", "guide pops up from the", "item, lip-synced"])
box(1300, 170, 280, 150, "On the phone only", ["memory, contacts,", "screen context (90 s)", "no contact leaves it"])
arrow(340, 245, 370, 245)
text(1300, 372, "Sends and payments ask the user first.", 17, "#A3A3A3", anchor="start")
text(1300, 396, "The agent takes ONE action per step.", 17, "#A3A3A3", anchor="start")

# --- backend ---
band(470, 250, "BACKEND  ·  Flask on Render")
box(60, 530, 240, 150, "/api/stt", ["speech to text"])
box(330, 530, 310, 150, "/api/assistant", ["router + tools", "instant paths skip", "the model"])
box(670, 530, 300, 150, "/api/phone/step", ["screenshot + numbered", "accessibility tree,", "icon buttons via SAM"])
box(1000, 530, 300, 150, "/api/vision · /locate", ["describe the view,", "find the item in it"])
box(1560, 530, 190, 150, "/api/tts", ["voice for the", "panel + guide"])

# --- models ---
band(760, 300, "MODELS  ·  Meta Model API + ElevenLabs", bottom=True)
box(60, 830, 230, 150, "Muse Voice", ["Transcribe", "language bias +", "keywords"])
box(320, 830, 760, 150, "Muse Spark 1.3", ["reasoning and vision: routes each request, reads screenshots,", "chooses the next tap, describes camera frames"])
box(1110, 830, 220, 150, "SAM 3.1", ["pixel boxes:", "icon buttons,", "guide placement"])
box(1360, 830, 170, 150, "Muse Image", ["guide's mouth", "shapes + blink", "(build time)"], dash=True)
box(1560, 830, 190, 150, "ElevenLabs", ["voice, with the", "phone's voice", "as fallback"])

# phone -> backend
arrow(510, 320, 175, 530)                # panel -> stt
arrow(510, 320, 485, 530)                # panel -> assistant
arrow(820, 320, 820, 530, both=True)     # accessibility <-> phone/step
arrow(1130, 320, 1130, 530)              # live scan -> vision/locate
# backend -> models
arrow(175, 680, 175, 830)
arrow(485, 680, 485, 830)
arrow(820, 680, 820, 830)
arrow(1030, 680, 1030, 830)
arrow(1230, 680, 1230, 830)
arrow(1655, 680, 1655, 830)
text(900, 1100, "Open source (MIT) · github.com/rathodpratham15/jarvis-mobile", 18, "#737373")
for y, label, bottom in labels:
    text(58 if bottom else W - 58, y + 34, label, 18, "#737373", "700", "start" if bottom else "end")
add('</svg>')

here = os.path.dirname(__file__)
svg = os.path.join(here, "..", "docs", "architecture.svg")
open(svg, "w").write("\n".join(out))
subprocess.run(["rsvg-convert", "-w", "1800", "-o", os.path.join(here, "..", "docs", "architecture.png"), svg], check=True)
print("wrote docs/architecture.svg and docs/architecture.png")
