from types import SimpleNamespace as NS

from vision import Vision, SYSTEM


class Fake:
    def __init__(self, reply="A blue bottle."):
        self.seen = {}
        self.reply = reply
        self.chat = NS(completions=NS(create=self._create))

    def _create(self, **kw):
        self.seen = kw
        return NS(choices=[NS(message=NS(content=self.reply))])


def test_frame_and_question_reach_the_model_with_the_people_rule():
    f = Fake()
    out = Vision(f, "m").describe("IMGB64", "what does the label say?")
    assert out == "A blue bottle."
    user = f.seen["messages"][1]["content"]
    assert user[0]["text"] == "what does the label say?"
    assert "IMGB64" in user[1]["image_url"]["url"]
    assert "NEVER identify a real person" in SYSTEM and "Never offer to look anyone up" in SYSTEM


def test_default_prompt_and_empty_reply_fallback():
    f = Fake(reply="")
    out = Vision(f, "m").describe("IMG")
    assert "What is in view" in f.seen["messages"][1]["content"][0]["text"]
    assert "Try moving closer" in out


def test_guide_reply_is_parsed_and_people_are_never_returned_as_items():
    from vision import parse_guide
    assert parse_guide("ITEM: red mug\nSAY: A mug. Fun fact: ceramic keeps heat.") == ("A mug. Fun fact: ceramic keeps heat.", "red mug")
    assert parse_guide("ITEM: NONE\nSAY: Too dark.") == ("Too dark.", None)
    assert parse_guide("ITEM: a woman\nSAY: A person waving.")[1] is None
    assert parse_guide("ITEM: two people talking\nSAY: x")[1] is None
    assert parse_guide("Just text")[0] == "Just text"


def test_guide_mode_returns_text_and_item():
    f = Fake(reply="ITEM: coffee mug\nSAY: That is a coffee mug. Fun fact: they hold heat well.")
    out = Vision(f, "m").describe_guide("IMG")
    assert out == {"text": "That is a coffee mug. Fun fact: they hold heat well.", "item": "coffee mug"}
    assert "NEVER identify a real person" in f.seen["messages"][0]["content"]


def test_locate_endpoint_refuses_people_and_returns_fractions(monkeypatch):
    import app as appmod, sam_grounding
    from sam_grounding import Box
    monkeypatch.setattr(appmod, "TOKEN", "t")
    c = appmod.app.test_client(); h = {"Authorization": "Bearer t"}
    assert c.post("/api/locate", json={"image": "A", "item": "woman"}, headers=h).get_json() == {"found": False}
    monkeypatch.setattr(sam_grounding, "locate", lambda img, item: [Box(100, 200, 300, 400, 1000, 2000, 0.9)])
    r = c.post("/api/locate", json={"image": "A", "item": "red mug"}, headers=h).get_json()
    assert r["found"] and (r["x1"], r["y1"], r["x2"], r["y2"]) == (0.1, 0.1, 0.3, 0.2)
