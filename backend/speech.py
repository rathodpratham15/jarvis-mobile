"""Speech endpoints: Meta Muse Voice Transcribe (STT) and ElevenLabs (TTS). Keys stay server-side."""
from __future__ import annotations

import json
import logging
import os
import threading
from datetime import date
from typing import Optional

import httpx

logger = logging.getLogger(__name__)
_T = httpx.Timeout(20.0, connect=4.0)
_lock = threading.Lock()


def _usage_file() -> str:
    d = os.getenv("JARVIS_DATA_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "tts_usage.json")


def _read_used() -> int:
    try:
        with open(_usage_file()) as f:
            u = json.load(f)
        return int(u["chars"]) if u.get("date") == date.today().isoformat() else 0
    except (OSError, ValueError, KeyError):
        return 0


def _add_used(n: int) -> None:
    total = _read_used() + n   # read BEFORE open(..., "w") truncates the file
    with open(_usage_file(), "w") as f:
        json.dump({"date": date.today().isoformat(), "chars": total}, f)


def tts_chars_left() -> int:
    """Remaining ElevenLabs characters for today (daily budget, default 3000)."""
    return max(0, int(os.getenv("ELEVENLABS_DAILY_CHARS", "3000")) - _read_used())


KEYWORDS = ["JARVIS", "Hey Jarvis", "WhatsApp", "YouTube", "Chrome", "Clock", "Gmail", "Spotify", "Instagram",
            "Google Maps", "Camera", "Settings", "Messages", "Calendar", "battery saver", "alarm", "timer"]


def transcribe(wav: bytes) -> str:
    """WAV (mono, 16-bit PCM, 16/24 kHz) -> transcript via Meta's REST ASR endpoint."""
    base = os.getenv("META_API_BASE_URL", "https://api.meta.ai/v1").rstrip("/")
    # languageBias is a hint, not a lock: without it the model auto-detects and can write Hindi script for accented English.
    langs = [x.strip() for x in os.getenv("STT_LANGUAGES", "English").split(",") if x.strip()]
    settings = {"mode": "PUSH_TO_TALK", "model": "muse-voice-transcribe-1.0", "audioEncoding": "WAV",
                "languageBias": langs, "keywords": KEYWORDS}
    r = httpx.post(
        f"{base}/asr/transcribe",
        headers={"Authorization": f"Bearer {os.getenv('META_API_KEY', '')}", "Accept": "application/json"},
        files={"request": (None, json.dumps(settings), "application/json"), "audio": ("audio.wav", wav, "audio/wav")},
        timeout=_T,
    )
    r.raise_for_status()
    return (r.json().get("transcript") or "").strip()


def synthesize(text: str) -> Optional[bytes]:
    """Text -> MP3 bytes via ElevenLabs, or None if not configured or today's budget is spent
    (the phone then uses its built-in voice)."""
    key, voice = os.getenv("ELEVENLABS_API_KEY"), os.getenv("ELEVENLABS_VOICE_ID")
    if not (key and voice):
        return None
    text = text[:600]
    with _lock:
        if len(text) > tts_chars_left():
            logger.info("ElevenLabs daily budget spent; falling back to device voice")
            return None
    r = httpx.post(
        f"https://api.elevenlabs.io/v1/text-to-speech/{voice}",
        params={"output_format": "mp3_44100_64"},
        headers={"xi-api-key": key, "Content-Type": "application/json"},
        json={"text": text, "model_id": os.getenv("ELEVENLABS_MODEL", "eleven_flash_v2_5")},
        timeout=_T,
    )
    r.raise_for_status()
    with _lock:
        _add_used(len(text))
    return r.content
