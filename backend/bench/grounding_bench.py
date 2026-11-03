"""Before/after benchmark: where does Muse Spark tap when it only sees a screenshot?
A) Muse Spark guesses pixel coordinates.   B) Muse Spark names the target, SAM 3.1 locates it.
Ground truth = element bounds from the accessibility tree (uiautomator dump). A hit = point inside the element.
Usage: python bench/grounding_bench.py <dir with NAME.png + NAME.xml pairs> [max_per_screen]"""
import base64, io, json, os, re, sys, time, xml.etree.ElementTree as ET
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from PIL import Image
from openai import OpenAI
import httpx
import sam_grounding as sg
from phone_agent import parse_action

for l in open(os.path.join(os.path.dirname(__file__), "..", ".env")):
    if l.startswith(("META_API_", "META_MODEL", "META_REASONING")):
        k, v = l.strip().split("=", 1); os.environ[k] = v
client = OpenAI(api_key=os.environ["META_API_KEY"], base_url=os.environ["META_API_BASE_URL"],
                timeout=httpx.Timeout(40, connect=4), max_retries=0)
EXTRA = {"reasoning_effort": "low"}


def targets(xml_path, W, H, limit):
    out, seen = [], {}
    for n in ET.parse(xml_path).iter("node"):
        label = (n.get("content-desc") or n.get("text") or "").strip()
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not (label and m and n.get("clickable") == "true" and len(label) < 30):
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        if not (60 < x2 - x1 < W * .6 and 60 < y2 - y1 < H * .25):
            continue
        seen.setdefault(label.lower(), []).append((label, (x1, y1, x2, y2)))
    uniq = [v[0] for v in seen.values() if len(v) == 1]   # unambiguous labels only
    step = max(1, len(uniq) // limit)
    return uniq[::step][:limit]


def ask(b64, text):
    r = client.chat.completions.create(model=os.environ["META_MODEL"], max_tokens=4000, extra_body=EXTRA, temperature=0.1,
        messages=[{"role": "user", "content": [{"type": "text", "text": text},
                  {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}}]}])
    return r.choices[0].message.content or ""


def main(d, per):
    rows = []
    for png in sorted(f for f in os.listdir(d) if f.endswith(".png") and os.path.exists(os.path.join(d, f[:-4] + ".xml"))):
        im = Image.open(os.path.join(d, png)).convert("RGB"); W, H = im.size
        im.thumbnail((1080, 2400)); sw, sh = im.size; buf = io.BytesIO(); im.save(buf, "JPEG", quality=70)
        b64 = base64.b64encode(buf.getvalue()).decode(); k = sw / W
        for label, (x1, y1, x2, y2) in targets(os.path.join(d, png[:-4] + ".xml"), W, H, per):
            box = tuple(round(v * k) for v in (x1, y1, x2, y2))
            hit = lambda p: bool(p) and box[0] <= p[0] <= box[2] and box[1] <= p[1] <= box[3]
            row = {"screen": png[:-4], "label": label}
            try:
                t = time.time()
                a = parse_action(ask(b64, f'Screenshot is {sw}x{sh} px. Goal: tap the "{label}". Reply ONLY JSON {{"x":int,"y":int}} for the tap point.'))
                row["A_point"] = (a["x"], a["y"]) if a else None; row["A_s"] = round(time.time() - t, 1)
            except Exception as e:
                row["A_point"] = None; row["A_err"] = str(e)[:60]
            try:
                t = time.time()
                phrase = ask(b64, f'Goal: tap the "{label}". Name the on-screen thing to tap as a SHORT visual noun phrase (2-4 words, no verbs, no quotes). Reply with the phrase only.').strip().strip('."\'')
                row["phrase"] = phrase
                row["B_point"] = sg.tap_point(b64, phrase, sw, sh); row["B_s"] = round(time.time() - t, 1)
            except Exception as e:
                row["B_point"] = None; row["B_err"] = str(e)[:60]
            row["A_hit"], row["B_hit"] = hit(row["A_point"]), hit(row["B_point"])
            rows.append(row); print(json.dumps(row), flush=True)
    n = len(rows)
    print(f"\nN={n}  A (Muse Spark coords): {sum(r['A_hit'] for r in rows)}/{n}   B (Muse Spark + SAM 3.1): {sum(r['B_hit'] for r in rows)}/{n}")
    json.dump(rows, open(os.path.join(os.path.dirname(__file__), "results.json"), "w"), indent=1)


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 6)
