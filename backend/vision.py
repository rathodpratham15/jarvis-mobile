"""Live camera understanding: one still frame (plus an optional spoken question) -> a short description.
A lean call to Muse Spark with no tool menu, so it answers in a couple of seconds."""
from __future__ import annotations

import logging
import os
from typing import Any, Optional

import httpx

logger = logging.getLogger(__name__)

SYSTEM = """You are the eyes of JARVIS, a voice assistant, looking through a phone camera.
Reply in one or two short plain sentences with no markdown.
- Say what is in view. Read prominent text exactly (signs, labels, screens, documents). Identify objects, products, plants, animals, food, signs, documents and landmarks.
- If the user asked a question, answer it from the image first.
- NEVER identify a real person from their face, body or appearance, and never guess a name, age, ethnicity, health or private details. For people, describe only generically (for example "a person at a desk") and read visible text such as a name badge. Never offer to look anyone up.
- If the view is blurry, dark or empty, say how to improve it (for example "Move closer" or "Too dark")."""

GUIDE_SYSTEM = SYSTEM.replace("Reply in one or two short plain sentences with no markdown.", "You are a friendly animated guide who speaks in the first person.") + """
Reply in EXACTLY this format, two lines:
ITEM: <the one main thing you are talking about, as a 1 to 4 word noun phrase such as "red mug", or NONE if the view is blurry, empty or mainly people>
SAY: <at most 25 words in total: what it is in a few words, then one genuinely interesting fact or tip. Warm and quick. No markdown.>
If the main subject is a person, ITEM must be NONE."""

_PEOPLE = ("person", "people", "man", "woman", "boy", "girl", "child", "kid", "face", "human", "baby", "guy", "lady", "someone", "crowd", "student", "worker")


def parse_guide(text: str) -> tuple[str, Optional[str]]:
    """'ITEM: red mug\nSAY: ...' -> (say, item). A person, NONE or a missing ITEM line -> item None."""
    import re
    m_item = re.search(r"ITEM:\s*(.+)", text or "", re.I)
    m_say = re.search(r"SAY:\s*(.+)", text or "", re.I | re.S)
    say = (m_say.group(1) if m_say else (text or "")).strip()
    item = (m_item.group(1).strip().strip('."\'') if m_item else "")
    low = item.lower()
    if not item or low == "none" or any(re.search(rf"\b{w}s?\b", low) for w in _PEOPLE):
        item = None
    return say, item


FULL_SYSTEM = SYSTEM.replace("Reply in one or two short plain sentences with no markdown.", "You read what the camera sees in full.") + """
Reply in EXACTLY this format:
SPOKEN: <one or two short sentences saying what this is>
DETAILS:
<a complete, well-organized summary of everything readable: for a resume list name, headline, every job with company and dates, education, skills and projects; for any other document, article, menu, receipt, label or sign, cover every section. Short lines, '-' bullets, no other markdown, under about 25 lines. For code, logs or tables say what it is and list the key items. If some text is unreadable, say which part.>"""

LIVE_PROMPT = "What is in view right now? Keep it to one short sentence; add any important text you can read."


class Vision:
    def __init__(self, client: Any, model: str):
        self._client, self._model = client, model
        effort = os.getenv("VISION_REASONING_EFFORT", "minimal")
        self._extra = {"reasoning_effort": effort} if effort else None

    def describe(self, image_b64: str, question: Optional[str] = None) -> str:
        q = (question or "").strip() or LIVE_PROMPT
        r = self._client.chat.completions.create(
            model=self._model, max_tokens=4000, temperature=0.2, extra_body=self._extra,
            messages=[{"role": "system", "content": SYSTEM},
                      {"role": "user", "content": [
                          {"type": "text", "text": q[:300]},
                          {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}}]}])
        return (r.choices[0].message.content or "").strip() or "I can't make that out. Try moving closer."


def describe_guide(self, image_b64: str, question: Optional[str] = None) -> dict:
    q = (question or "").strip() or "Introduce what you see."
    r = self._client.chat.completions.create(
        model=self._model, max_tokens=4000, temperature=0.5, extra_body=self._extra,
        messages=[{"role": "system", "content": GUIDE_SYSTEM},
                  {"role": "user", "content": [
                      {"type": "text", "text": q[:300]},
                      {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}}]}])
    say, item = parse_guide(r.choices[0].message.content or "")
    return {"text": say or "I can't make that out. Try moving closer.", "item": item}


Vision.describe_guide = describe_guide


def describe_full(self, image_b64: str, question: Optional[str] = None) -> dict:
    """Read everything in view: a short spoken line plus the complete details for the screen."""
    import re
    q = (question or "").strip() or "Read and summarize everything in view."
    client = self._client.with_options(timeout=50.0) if hasattr(self._client, "with_options") else self._client
    r = client.chat.completions.create(
        model=self._model, max_tokens=4000, temperature=0.2, extra_body={"reasoning_effort": "low"},
        messages=[{"role": "system", "content": FULL_SYSTEM},
                  {"role": "user", "content": [
                      {"type": "text", "text": q[:300]},
                      {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_b64}"}}]}])
    raw = (r.choices[0].message.content or "").strip()
    m = re.match(r"^\s*SPOKEN:\s*(.+?)\s*\n+\s*DETAILS:\s*(.+)$", raw, re.S | re.I)
    spoken, details = (m.group(1).strip(), m.group(2).strip()) if m else (raw.split("\n")[0][:200], raw)
    return {"text": spoken or "I can't make that out. Try moving closer.", "details": details}


Vision.describe_full = describe_full


def build_vision() -> Optional[Vision]:
    key, base, model = os.getenv("META_API_KEY"), os.getenv("META_API_BASE_URL"), os.getenv("META_MODEL")
    if not (key and base and model):
        return None
    from openai import OpenAI
    return Vision(OpenAI(api_key=key, base_url=base, timeout=httpx.Timeout(20.0, connect=4.0), max_retries=0), model)
