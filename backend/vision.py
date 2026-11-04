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


def build_vision() -> Optional[Vision]:
    key, base, model = os.getenv("META_API_KEY"), os.getenv("META_API_BASE_URL"), os.getenv("META_MODEL")
    if not (key and base and model):
        return None
    from openai import OpenAI
    return Vision(OpenAI(api_key=key, base_url=base, timeout=httpx.Timeout(20.0, connect=4.0), max_retries=0), model)
