"""JARVIS Mobile backend — one stateless endpoint that decides the phone's next action."""
import hmac
import os

from dotenv import load_dotenv
from flask import Flask, Response, jsonify, request
from flask_cors import CORS

from assistant import build_assistant
import speech
from phone_agent import PhoneObservation, build_phone_agent

load_dotenv()
app = Flask(__name__)
CORS(app)
agent = build_phone_agent()
assistant = build_assistant()
TOKEN = os.getenv("JARVIS_DEVICE_TOKEN", "")


@app.before_request
def _auth():
    if request.method == "OPTIONS" or request.path == "/api/health":
        return None
    sent = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
    if not TOKEN or not hmac.compare_digest(sent, TOKEN):
        return jsonify(error="unauthorized"), 401
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
