"""Phone agent — decides the next UI action on an Android device.

Stateless per step: the device sends the goal, a screenshot, the accessibility
UI tree and the action history; we return ONE action. The device executes it
and calls again. This keeps the backend trivially deployable (no sessions,
no websockets) and makes every step independently testable.

Grounding strategy (most → least reliable):
  1. Pick a numbered accessibility node  ({"action":"tap","node":7})
  2. Raw pixel coordinates               ({"action":"tap","x":..,"y":..})
  3. SAM-grounded box for screens with no usable tree (see sam_grounding.py)
"""

from __future__ import annotations

import json
import logging
import os
import re
from dataclasses import dataclass
from typing import Any, Optional

logger = logging.getLogger(__name__)

MAX_NODES = 80  # cap prompt size; nodes are pre-sorted by usefulness

_SYSTEM_PROMPT = """You are JARVIS, an AI assistant operating an Android phone on the user's behalf.

You receive: the user's goal, a screenshot, a numbered list of on-screen UI elements, and your recent actions.
Respond with EXACTLY ONE JSON object and nothing else.

Actions:
  {"action":"tap","node":<id>,"reason":"..."}            # preferred: tap a listed element
  {"action":"tap","target":"<short noun phrase>","reason":"..."}  # if NO listed element fits (game, canvas, custom UI): name the thing, e.g. "play button", "red heart icon". A vision model locates it.
  {"action":"tap","x":<int>,"y":<int>,"reason":"..."}    # last resort
  {"action":"type","text":"...","node":<id optional>,"reason":"..."}
  {"action":"scroll","direction":"up|down|left|right","reason":"..."}
  {"action":"back","reason":"..."}
  {"action":"home","reason":"..."}
  {"action":"open_app","name":"<app name>","reason":"..."}
  {"action":"wait","reason":"..."}
  {"action":"done","result":"<what was accomplished>"}
  {"action":"fail","reason":"<why it cannot be done>"}

Rules:
- Prefer opening the right app with open_app over hunting through the launcher.
- Prefer node ids to coordinates. Coordinates are screen pixels.
- Never repeat an action that did not change the screen; try something different.
- If the goal is already satisfied, respond with done.
- VERIFY before done: only respond with done when the CURRENT screenshot/elements show the goal was achieved (e.g. a sent message visible in the chat, a confirmation screen). In `result`, name the evidence you saw. If you cannot see evidence, keep acting or respond with fail.
- If CURRENT APP is com.jarvis.assistant, that is YOU (the assistant UI). Never tap anything in it; use open_app or home to leave first.
- Do not invent contacts, amounts or credentials. If required info is missing, respond with fail and say what you need.
- Output JSON only. No markdown, no commentary."""

# Words that make an action consequential enough to require on-device confirmation.
_SENSITIVE = re.compile(
    r"\b(pay|payment|buy|purchase|place order|order now|checkout|confirm order|"
    r"send|submit|post|tweet|delete|remove|erase|uninstall|transfer|withdraw|"
    r"subscribe|book now|call)\b",
    re.IGNORECASE,
)

# Generic buttons that become consequential when the screen is about money or a recipient.
_GENERIC_CONFIRM = re.compile(r"^\W*(confirm|continue|next|done|ok|yes|proceed|agree|accept|approve|authori[sz]e|apply|submit)\W*$", re.I)
_RISK_CONTEXT = re.compile(r"(\$\s?\d|₹\s?\d|€\s?\d|£\s?\d|\b(total|amount|balance|payment|pay to|recipient|send to|transfer|card number|order summary|delete|permanently)\b)", re.I)

_VALID_ACTIONS = {
    "tap", "type", "scroll", "back", "home", "open_app", "wait", "done", "fail",
}


@dataclass
class PhoneObservation:
    width: int
    height: int
    package: str
    nodes: list[dict]
    screenshot_b64: Optional[str] = None

    @classmethod
    def from_payload(cls, p: dict) -> "PhoneObservation":
        return cls(
            width=int(p.get("width") or 0),
            height=int(p.get("height") or 0),
            package=str(p.get("package") or ""),
            nodes=list(p.get("nodes") or []),
            screenshot_b64=p.get("screenshot_b64") or None,
        )


def parse_action(text: str) -> Optional[dict]:
    """Extract the first JSON object from model output (tolerates fences/prose)."""
    text = re.sub(r"```(?:json)?", "", text or "").strip()
    start = text.find("{")
    if start == -1:
        return None
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                try:
                    obj = json.loads(text[start : i + 1])
                except json.JSONDecodeError:
                    return None
                return obj if isinstance(obj, dict) else None
    return None


def _node_line(n: dict) -> str:
    label = (n.get("text") or n.get("desc") or "").strip().replace("\n", " ")[:60]
    flags = []
    if n.get("clickable"):
        flags.append("clickable")
    if n.get("editable"):
        flags.append("editable")
    if n.get("scrollable"):
        flags.append("scrollable")
    b = n.get("bounds") or [0, 0, 0, 0]
    cls = (n.get("cls") or "").split(".")[-1]
    return f'[{n.get("id")}] {cls} "{label}" {",".join(flags)} @{b}'


def render_nodes(nodes: list[dict]) -> str:
    # Keep elements that carry a label or can be interacted with.
    useful = [
        n for n in nodes
        if (n.get("text") or n.get("desc")) or n.get("clickable") or n.get("editable")
    ]
    return "\n".join(_node_line(n) for n in useful[:MAX_NODES]) or "(no accessible elements)"


def _target_label(action: dict, nodes: list[dict]) -> str:
    node_id = action.get("node")
    if node_id is None:
        return ""
    for n in nodes:
        if n.get("id") == node_id:
            return (n.get("text") or n.get("desc") or "")
    return ""


def validate_action(action: Optional[dict], obs: PhoneObservation) -> dict:
    """Coerce model output into a safe, well-formed action (or a fail)."""
    if not action or action.get("action") not in _VALID_ACTIONS:
        return {"action": "fail", "reason": "Model returned no valid action."}
    kind = action["action"]
    if kind == "tap":
        has_node = isinstance(action.get("node"), int)
        has_xy = isinstance(action.get("x"), (int, float)) and isinstance(action.get("y"), (int, float))
        has_target = isinstance(action.get("target"), str) and action["target"].strip() != ""
        if not (has_node or has_xy or has_target):
            return {"action": "fail", "reason": "tap needs a node, a target phrase or x/y."}
        if has_node and not any(n.get("id") == action["node"] for n in obs.nodes):
            return {"action": "fail", "reason": f"Unknown node {action['node']}."}
        if has_xy and not has_node and obs.width and obs.height:
            if not (0 <= action["x"] <= obs.width and 0 <= action["y"] <= obs.height):
                return {"action": "fail", "reason": "tap coordinates off-screen."}
    if kind == "type" and not isinstance(action.get("text"), str):
        return {"action": "fail", "reason": "type needs text."}
    if kind == "scroll" and action.get("direction") not in {"up", "down", "left", "right"}:
        return {"action": "fail", "reason": "scroll needs a valid direction."}
    if kind == "open_app" and not action.get("name"):
        return {"action": "fail", "reason": "open_app needs a name."}
    return action


def _risk_lines(obs: PhoneObservation) -> list[str]:
    out = []
    for n in obs.nodes:
        t = (n.get("text") or n.get("desc") or "").strip().replace("\n", " ")
        if t and _RISK_CONTEXT.search(t) and t not in out:
            out.append(t[:60])
    return out[:3]


def apply_safety_gate(action: dict, obs: PhoneObservation, goal: str) -> dict:
    """Flag consequential taps so the device asks the user before executing.
    A tap is consequential if its label/reason is sensitive, OR it is a generic Confirm/Continue button on a
    screen that shows money, a recipient or a deletion. The prompt quotes what is on screen."""
    if action.get("action") != "tap":
        return action
    label = _target_label(action, obs.nodes)
    haystack = f"{label} {action.get('reason', '')}"
    context = _risk_lines(obs)
    risky = bool(_SENSITIVE.search(haystack)) or (bool(_GENERIC_CONFIRM.match(label)) and bool(context))
    if risky:
        action = dict(action)
        action["needs_confirmation"] = True
        detail = f' Screen shows: {"; ".join(context)}.' if context else ""
        action["confirm_prompt"] = f'JARVIS wants to tap "{label or "an element"}" for: {goal[:80]}.{detail} Allow?'
    return action


class PhoneAgent:
    def __init__(self, client: Any, model: str, temperature: float = 0.1, max_tokens: int = 4000, locate=None):
        self._locate = locate   # injectable for tests: (b64, phrase, w, h) -> (x, y) | None
        self._client = client
        self._model = model
        self._temperature = temperature
        self._max_tokens = max_tokens
        # Muse Spark is a reasoning model; "low" roughly halves per-step latency.
        effort = os.getenv("META_REASONING_EFFORT", "low")
        self._extra_body = {"reasoning_effort": effort} if effort else None

    def _messages(self, goal: str, obs: PhoneObservation, history: list[str], step: int,
                  attachments: Optional[list[str]] = None) -> list[dict]:
        text = (
            f"GOAL: {goal}\n"
            f"STEP: {step}\n"
            f"CURRENT APP: {obs.package or 'unknown'}\n"
            f"SCREEN: {obs.width}x{obs.height}px\n\n"
            f"UI ELEMENTS:\n{render_nodes(obs.nodes)}\n\n"
            f"RECENT ACTIONS:\n{chr(10).join(history[-6:]) or 'None yet.'}\n\n"
            "What is the single next action?"
        )
        if attachments:
            text += (f"\n\nThe user attached {len(attachments)} reference image(s), "
                     "shown AFTER the screenshot. Use them as context for the goal.")
        content: list[dict] = [{"type": "text", "text": text}]
        if obs.screenshot_b64:
            content.append({
                "type": "image_url",
                "image_url": {"url": f"data:image/jpeg;base64,{obs.screenshot_b64}"},
            })
        for b64 in attachments or []:
            content.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}})
        return [
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": content},
        ]

    def next_action(self, goal: str, obs: PhoneObservation, history: list[str], step: int,
                    attachments: Optional[list[str]] = None) -> dict:
        try:
            resp = self._client.chat.completions.create(
                model=self._model,
                messages=self._messages(goal, obs, history, step, attachments),
                temperature=self._temperature,
                max_tokens=self._max_tokens,  # reasoning tokens count against this
                extra_body=self._extra_body,
            )
            raw = resp.choices[0].message.content or ""
        except Exception as exc:  # network / rate limit / bad key
            logger.exception("PhoneAgent LLM call failed")
            return {"action": "fail", "reason": f"Model call failed: {exc}"}
        action = validate_action(parse_action(raw), obs)
        action = self._ground(action, obs)
        return apply_safety_gate(action, obs, goal)

    def _ground(self, action: dict, obs: PhoneObservation) -> dict:
        """Turn a named target into pixel coordinates with SAM 3.1."""
        if action.get("action") != "tap" or "node" in action or "x" in action or not action.get("target"):
            return action
        if not obs.screenshot_b64:
            return {"action": "fail", "reason": "Cannot locate the target without a screenshot."}
        import sam_grounding
        pt = self._locate(obs.screenshot_b64, action["target"], obs.width, obs.height) if self._locate else \
            sam_grounding.tap_point(obs.screenshot_b64, action["target"], obs.width, obs.height)
        if pt is None:
            return {"action": "fail", "reason": f'Could not find "{action["target"]}" on screen.'}
        return {**{k: v for k, v in action.items() if k != "target"}, "x": pt[0], "y": pt[1],
                "reason": f'{action.get("reason", "")} (located "{action["target"]}" with SAM 3.1)'.strip()}


def build_phone_agent() -> Optional[PhoneAgent]:
    """Build from env. Meta Model API is OpenAI-compatible; override via env."""
    key = os.getenv("META_API_KEY") or os.getenv("META_LLAMA_API_KEY")
    base = os.getenv("META_API_BASE_URL")
    model = os.getenv("META_MODEL")
    if not (key and base and model):
        logger.warning("Phone agent disabled: set META_API_KEY, META_API_BASE_URL, META_MODEL")
        return None
    import httpx
    from openai import OpenAI

    return PhoneAgent(OpenAI(api_key=key, base_url=base, timeout=httpx.Timeout(45.0, connect=4.0), max_retries=0), model)
