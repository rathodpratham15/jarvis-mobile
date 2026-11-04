"""JARVIS Mobile backend — one stateless endpoint that decides the phone's next action."""
import hmac
import os
import threading
import time
from collections import defaultdict, deque

from dotenv import load_dotenv
from flask import Flask, Response, jsonify, request
from flask_cors import CORS

from assistant import build_assistant
from vision import build_vision
import speech
from phone_agent import PhoneObservation, build_phone_agent

load_dotenv()
app = Flask(__name__)
CORS(app, origins=["capacitor://localhost", "http://localhost", "https://localhost", "http://localhost:5173"])
agent = build_phone_agent()
assistant = build_assistant()
vision = build_vision()
TOKEN = os.getenv("JARVIS_DEVICE_TOKEN", "")


_hits: dict = defaultdict(deque)
_hits_lock = threading.Lock()
RATE_PER_MIN = int(os.getenv("JARVIS_RATE_PER_MIN", "90"))


def rate_limited(key: str, now: float = None, limit: int = None) -> bool:
    """Sliding 60 s window per client. True = reject this request."""
    now = time.time() if now is None else now
    limit = RATE_PER_MIN if limit is None else limit
    with _hits_lock:
        q = _hits[key]
        while q and now - q[0] > 60:
            q.popleft()
        if len(q) >= limit:
            return True
        q.append(now)
        return False


@app.before_request
def _auth():
    if request.method == "OPTIONS" or request.path == "/api/health":
        return None
    sent = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    if not TOKEN or not hmac.compare_digest(sent, TOKEN):
        return jsonify(error="unauthorized"), 401
    client = (request.headers.get("X-Forwarded-For", request.remote_addr or "").split(",")[0].strip())
    if rate_limited(f"{client}|{sent[:8]}"):
        return jsonify(error="too many requests, slow down"), 429
    return None


@app.get("/api/health")
def health():
    return jsonify(status="ok", agent=agent is not None, tts_chars_left=speech.tts_chars_left())


@app.post("/api/phone/step")
def step():
    if agent is None:
        return jsonify(error="agent not configured (META_API_KEY/BASE_URL/MODEL)"), 503
    p = request.get_json(silent=True) or {}
    goal = (p.get("goal") or "").strip()
    if not goal:
        return jsonify(error="goal is required"), 400
    obs = PhoneObservation.from_payload(p.get("observation") or {})
    history = [str(h) for h in (p.get("history") or [])][-20:]
    # Up to 2 reference images, each <= ~2 MB of base64.
    atts = [a for a in (p.get("attachments") or []) if isinstance(a, str) and len(a) < 2_800_000][:2]
    return jsonify(action=agent.next_action(goal, obs, history, int(p.get("step") or 1), atts))


@app.post("/api/assistant")
def assist():
    """Route one spoken request: answer | device action | operate-phone fallback."""
    if assistant is None:
        return jsonify(error="assistant not configured"), 503
    p = request.get_json(silent=True) or {}
    text = (p.get("utterance") or "").strip()
    if not text:
        return jsonify(error="utterance is required"), 400
    atts = [a for a in (p.get("attachments") or []) if isinstance(a, str) and len(a) < 2_800_000][:2]
    units = "metric" if p.get("units") == "metric" else "imperial"
    apps = p.get("messaging_apps")
    apps = [str(a) for a in apps][:6] if isinstance(apps, list) else None
    hist = p.get("history") if isinstance(p.get("history"), list) else None
    scr = p.get("screen") if isinstance(p.get("screen"), dict) else None
    if scr and (not isinstance(scr.get("screenshot_b64"), str) or len(scr["screenshot_b64"]) > 3_500_000):
        scr = None
    screen = ({"b64": scr["screenshot_b64"], "package": str(scr.get("package") or "")[:80],
               "text": str(scr.get("text") or "")[:1500]} if scr else None)
    return jsonify(assistant.turn(text, units=units, attachments=atts, history=hist, messaging_apps=apps,
                                  screen=screen, now=str(p.get("now") or "")[:40], tz=str(p.get("tz") or "")[:40],
                                  memory=[str(x)[:200] for x in (p.get("memory") or [])[:30]] if isinstance(p.get("memory"), list) else None))


@app.post("/api/vision")
def vision_frame():
    """Live scan: one camera frame (+ optional spoken question) -> a short description."""
    if vision is None:
        return jsonify(error="vision not configured"), 503
    p = request.get_json(silent=True) or {}
    img = p.get("image")
    if not isinstance(img, str) or not img or len(img) > 2_800_000:
        return jsonify(error="image (base64 JPEG, under ~2 MB) is required"), 400
    try:
        q = str(p.get("question") or "")[:300]
        if p.get("mode") == "full":  # live scan "Full summary": short spoken line plus every section on screen
            return jsonify(**vision.describe_full(img, q))
        if p.get("guide"):          # animated guide: friendly line plus the main item's name (never a person)
            return jsonify(**vision.describe_guide(img, q))
        return jsonify(text=vision.describe(img, q))
    except Exception as exc:   # network or model problems: tell the phone plainly, never crash the live view
        import openai
        net = isinstance(exc, (openai.APITimeoutError, openai.APIConnectionError))
        return jsonify(error=str(exc)[:200], text="I can't reach Meta's servers right now." if net else "I couldn't read that frame."), 502


@app.post("/api/locate")
def locate_item():
    """Where is the named item in this frame? Returns its box as fractions of the frame (SAM 3.1). Never locates people."""
    p = request.get_json(silent=True) or {}
    img, item = p.get("image"), str(p.get("item") or "").strip()[:60]
    if not isinstance(img, str) or not img or len(img) > 2_800_000 or not item:
        return jsonify(error="image and item are required"), 400
    from vision import parse_guide
    if parse_guide(f"ITEM: {item}\nSAY: x")[1] is None:
        return jsonify(found=False)
    import sam_grounding
    try:
        boxes = sam_grounding.locate(img, item)
    except Exception as exc:
        return jsonify(found=False, error=str(exc)[:120])
    if not boxes:
        return jsonify(found=False)
    b = boxes[0]
    return jsonify(found=True, x1=b.x1 / b.w, y1=b.y1 / b.h, x2=b.x2 / b.w, y2=b.y2 / b.h, frame_w=b.w, frame_h=b.h)


@app.post("/api/stt")
def stt():
    """Raw WAV body (mono 16-bit PCM, 16 kHz) -> {"text": ...} using Meta's muse-voice-transcribe-1.0."""
    wav = request.get_data()
    if len(wav) < 1000 or len(wav) > 8_000_000 or wav[:4] != b"RIFF":
        return jsonify(error="send a RIFF/WAV body"), 400
    try:
        return jsonify(text=speech.transcribe(wav))
    except Exception as exc:  # network / API error
        app.logger.exception("stt failed")
        return jsonify(error=f"transcription failed: {exc}"), 502


@app.post("/api/tts")
def tts():
    """{"text": ...} -> audio/mpeg from ElevenLabs. 503 tells the phone to use its built-in voice."""
    text = ((request.get_json(silent=True) or {}).get("text") or "").strip()
    if not text:
        return jsonify(error="text is required"), 400
    if os.getenv("TTS_PAUSED", "").lower() in ("1", "true", "yes"):
        return jsonify(error="tts paused (TTS_PAUSED is set); the phone uses its built-in voice"), 503
    try:
        audio = speech.synthesize(text)
    except Exception as exc:
        app.logger.exception("tts failed")
        return jsonify(error=f"tts failed: {exc}"), 502
    if audio is None:
        return jsonify(error="tts unavailable (not configured or daily budget spent)"), 503
    return Response(audio, mimetype="audio/mpeg")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.getenv("PORT", "5050")))
