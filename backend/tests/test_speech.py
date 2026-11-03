from types import SimpleNamespace as NS

import speech


def _setup(monkeypatch, tmp_path, limit):
    monkeypatch.setenv("JARVIS_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ELEVENLABS_API_KEY", "k")
    monkeypatch.setenv("ELEVENLABS_VOICE_ID", "v")
    monkeypatch.setenv("ELEVENLABS_DAILY_CHARS", str(limit))
    calls = []
    def fake_post(*a, **kw):
        calls.append(kw["json"]["text"])
        return NS(content=b"MP3", raise_for_status=lambda: None)
    monkeypatch.setattr(speech.httpx, "post", fake_post)
    return calls


def test_budget_blocks_after_limit_and_counts_only_successes(monkeypatch, tmp_path):
    calls = _setup(monkeypatch, tmp_path, 20)
    assert speech.synthesize("0123456789") == b"MP3"      # 10 used
    assert speech.tts_chars_left() == 10
    assert speech.synthesize("0123456789ABCDE") is None   # needs 15 > 10 left -> device voice
    assert len(calls) == 1
    assert speech.synthesize("short") == b"MP3"           # 5 fits
    assert speech.tts_chars_left() == 5


def test_not_configured_returns_none(monkeypatch, tmp_path):
    monkeypatch.setenv("JARVIS_DATA_DIR", str(tmp_path))
    monkeypatch.delenv("ELEVENLABS_API_KEY", raising=False)
    assert speech.synthesize("hi") is None


def test_failed_call_does_not_spend_budget(monkeypatch, tmp_path):
    _setup(monkeypatch, tmp_path, 100)
    def boom(*a, **kw): raise RuntimeError("quota exceeded")
    monkeypatch.setattr(speech.httpx, "post", boom)
    try:
        speech.synthesize("hello")
    except RuntimeError:
        pass
    assert speech.tts_chars_left() == 100
