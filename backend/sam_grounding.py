"""Pixel grounding with Meta SAM 3.1 for screens whose accessibility tree is empty or unhelpful
(games, canvases, custom-drawn UIs, some WebViews). Muse Spark names WHAT to tap in a short noun phrase;
SAM 3.1 says WHERE it is. Only box tokens are read; masks are ignored."""
from __future__ import annotations

import json
import logging
import os
import re
from dataclasses import dataclass
from typing import Optional

import httpx

logger = logging.getLogger(__name__)

_BOX = re.compile(r"<\|box;x1=(\d+);y1=(\d+);x2=(\d+);y2=(\d+);w=(\d+);h=(\d+)(?:;c=([0-9.]+))?\|>")


@dataclass
class Box:
    x1: int
    y1: int
    x2: int
    y2: int
    w: int
    h: int
    conf: float = 1.0

    @property
    def center(self) -> tuple[int, int]:
        return (self.x1 + self.x2) // 2, (self.y1 + self.y2) // 2

    @property
    def area(self) -> int:
        return max(0, self.x2 - self.x1) * max(0, self.y2 - self.y1)


def parse_boxes(text: str) -> list[Box]:
    out = []
    for m in _BOX.finditer(text or ""):
        x1, y1, x2, y2, w, h = (int(v) for v in m.groups()[:6])
        out.append(Box(x1, y1, x2, y2, w, h, float(m.group(7)) if m.group(7) else 1.0))
    return out


def _collect_stream(lines) -> str:
    """Concatenate response.output_text.delta payloads from an SSE stream."""
    text, event = [], ""
    for raw in lines:
        raw = raw.strip()
        if raw.startswith("event:"):
            event = raw[6:].strip()
        elif raw.startswith("data:") and raw[5:].strip() not in ("", "[DONE]"):
            try:
                d = json.loads(raw[5:])
            except json.JSONDecodeError:
                continue
            if event == "response.output_text.delta" or d.get("type") == "response.output_text.delta":
                text.append(d.get("delta") or "")
    return "".join(text)


def locate(image_b64: str, phrase: str, mime: str = "image/jpeg", timeout: float = 25.0) -> list[Box]:
    """All instances of `phrase` (a short noun phrase like 'play button') found in the image, best first."""
    key = os.getenv("META_API_KEY", "")
    base = os.getenv("META_API_BASE_URL", "https://api.meta.ai/v1").rstrip("/")
    body = {
        "model": os.getenv("META_SAM_MODEL", "sam-3.1"),
        "input": [{"type": "message", "role": "user", "content": [
            {"type": "input_text", "text": phrase.strip()[:80]},
            {"type": "input_image", "image_url": f"data:{mime};base64,{image_b64}"}]}],
        "stream": True,
        "metadata": {"include_confidence": "true"},
    }
    with httpx.stream("POST", f"{base}/responses", json=body, timeout=httpx.Timeout(timeout, connect=4.0),
                      headers={"Authorization": f"Bearer {key}", "Accept": "text/event-stream"}) as r:
        if r.status_code >= 400:
            r.read()
            raise RuntimeError(f"SAM {r.status_code}: {r.text[:200]}")
        text = _collect_stream(r.iter_lines())
    boxes = parse_boxes(text)
    boxes.sort(key=lambda b: (-b.conf, -b.area))
    return boxes


def tap_point(image_b64: str, phrase: str, shot_w: int, shot_h: int) -> Optional[tuple[int, int]]:
    """Center of the best match, in the coordinate space of a shot_w x shot_h screenshot."""
    try:
        boxes = locate(image_b64, phrase)
    except Exception as exc:
        logger.warning("SAM grounding failed: %s", exc)
        return None
    if not boxes:
        return None
    b = boxes[0]
    cx, cy = b.center
    return round(cx * shot_w / b.w), round(cy * shot_h / b.h)
