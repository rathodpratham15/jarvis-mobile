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
