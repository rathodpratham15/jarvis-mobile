from types import SimpleNamespace

from phone_agent import (
    PhoneAgent, PhoneObservation, parse_action, validate_action, apply_safety_gate,
)

NODES = [
    {"id": 0, "text": "Search", "clickable": True, "bounds": [0, 0, 100, 50]},
    {"id": 1, "text": "Place order", "clickable": True, "bounds": [0, 60, 100, 120]},
]
OBS = PhoneObservation(width=1080, height=2400, package="com.x", nodes=NODES)


class FakeClient:
    def __init__(self, reply):
        self.reply = reply
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kw):
        self.last = kw
        return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=self.reply))])


def test_parse_handles_fences_and_prose():
    assert parse_action('Sure!\n```json\n{"action":"back"}\n```') == {"action": "back"}
    assert parse_action("nothing") is None


def test_validate_rejects_unknown_node_and_offscreen():
    assert validate_action({"action": "tap", "node": 99}, OBS)["action"] == "fail"
    assert validate_action({"action": "tap", "x": 5000, "y": 5}, OBS)["action"] == "fail"
    assert validate_action({"action": "tap", "node": 0}, OBS)["action"] == "tap"
    assert validate_action({"action": "rm -rf"}, OBS)["action"] == "fail"


def test_safety_gate_flags_order():
    a = apply_safety_gate({"action": "tap", "node": 1}, OBS, "get dinner")
    assert a["needs_confirmation"] and "Place order" in a["confirm_prompt"]
    assert "needs_confirmation" not in apply_safety_gate({"action": "tap", "node": 0}, OBS, "x")


def test_next_action_sends_image_and_gates():
    c = FakeClient('{"action":"tap","node":1,"reason":"finish"}')
    obs = PhoneObservation(1080, 2400, "com.x", NODES, screenshot_b64="AAAA")
    out = PhoneAgent(c, "m").next_action("get dinner", obs, [], 1)
    assert out["needs_confirmation"]
    assert c.last["messages"][1]["content"][1]["type"] == "image_url"


def test_llm_error_becomes_fail():
    class Boom:
        chat = SimpleNamespace(completions=SimpleNamespace(create=lambda **k: 1 / 0))
    assert PhoneAgent(Boom(), "m").next_action("g", OBS, [], 1)["action"] == "fail"


def test_attachments_are_sent_after_screenshot():
    c = FakeClient('{"action":"done","result":"ok"}')
    obs = PhoneObservation(1080, 2400, "com.x", NODES, screenshot_b64="AAAA")
    PhoneAgent(c, "m").next_action("who is this", obs, [], 1, attachments=["BBBB"])
    content = c.last["messages"][1]["content"]
    assert [x["type"] for x in content] == ["text", "image_url", "image_url"]
    assert "attached 1 reference" in content[0]["text"]
