import json
from types import SimpleNamespace as NS

from assistant import Assistant


def call(name, **args):
    return NS(id="c1", function=NS(name=name, arguments=json.dumps(args)))


class Scripted:
    """Returns queued messages in order."""
    def __init__(self, *msgs):
        self.q = list(msgs)
        self.chat = NS(completions=NS(create=self._create))

    def _create(self, **kw):
        return NS(choices=[NS(message=self.q.pop(0))])


def msg(content=None, calls=None):
    return NS(content=content, tool_calls=calls)


def test_weather_runs_server_side_and_returns_card():
    fake = lambda location, units: {"place": "Boston, MA", "temp": "21°C / 70°F", "condition": "Cloudy", "humidity": "38%",
                                    "wind": "5 mph", "high": "25°", "low": "9°", "feels_like": "20°"}
    c = Scripted(msg(calls=[call("get_weather", location="Boston")]))   # only ONE model call is needed
    out = Assistant(c, "m").turn("temp in boston", server_tools={"get_weather": fake})
    assert out["say"] == "It's 21°C / 70°F and cloudy in Boston." and out["card"]["big"] == "21°C / 70°F"


def test_alarm_is_returned_as_device_action():
    c = Scripted(msg(calls=[call("set_alarm", hour=7, minute=0, say="Alarm set for 7 AM.")]))
    out = Assistant(c, "m").turn("alarm 7am")
    assert out == {"type": "device", "action": "set_alarm", "args": {"hour": 7, "minute": 0}, "say": "Alarm set for 7 AM."}


def test_fallback_operate_phone():
    c = Scripted(msg(calls=[call("operate_phone", goal="turn on battery saver")]))
    assert Assistant(c, "m").turn("battery saver")["type"] == "operate"


def test_tool_error_does_not_crash():
    def boom(location, units): raise RuntimeError("net down")
    c = Scripted(msg(calls=[call("get_weather", location="X")]), msg("Sorry, couldn't get the weather."))
    assert Assistant(c, "m").turn("w", server_tools={"get_weather": boom})["type"] == "answer"


def test_ask_user_when_body_missing():
    c = Scripted(msg(calls=[call("ask_user", question="What should the message say?")]))
    out = Assistant(c, "m").turn("text mom", messaging_apps=["WhatsApp"])
    assert out == {"type": "ask", "say": "What should the message say?"}


def test_history_and_apps_reach_the_model():
    seen = {}
    def create(**kw):
        seen.update(kw)
        seen['messages'] = list(kw['messages'])  # snapshot: the caller appends to the live list afterwards
        return NS(choices=[NS(message=msg(calls=[call("send_message", contact="mom", body="Hello", app="sms", say="Text sent to Mom.")]))])
    c = NS(chat=NS(completions=NS(create=create)))
    hist = [{"role": "user", "content": "text mom"}, {"role": "assistant", "content": "What should the message say?"}]
    out = Assistant(c, "m").turn("say hello", history=hist, messaging_apps=["WhatsApp", "Messages"])
    roles = [m["role"] for m in seen["messages"]]
    assert roles == ["system", "user", "assistant", "user"]
    assert "WhatsApp, Messages" in seen["messages"][0]["content"]
    assert out["type"] == "device" and out["args"] == {"contact": "mom", "body": "Hello", "app": "sms"}


def test_network_failure_gives_accurate_message():
    import httpx, openai
    def boom(**kw): raise openai.APITimeoutError(request=httpx.Request("POST", "https://x"))
    c = NS(chat=NS(completions=NS(create=boom)))
    out = Assistant(c, "m").turn("hi")
    assert "can't reach Meta" in out["say"]


def test_screen_goes_to_the_model_with_context_and_time():
    seen = {}
    def create(**kw):
        seen["messages"] = list(kw["messages"])
        return NS(choices=[NS(message=msg(calls=[call("add_calendar_event", title="Demo night", start="2026-11-06T19:00", say="Added it.")]))])
    c = NS(chat=NS(completions=NS(create=create)))
    out = Assistant(c, "m").turn("add this to my calendar", now="2026-11-02T18:00", tz="America/New_York",
                                 screen={"b64": "SCR", "package": "com.x.poster", "text": "Demo night Nov 6 7pm"})
    types = [p["type"] for p in seen["messages"][1]["content"]]
    assert types == ["text", "text", "image_url"]
    assert "com.x.poster" in seen["messages"][1]["content"][1]["text"]
    assert "2026-11-02T18:00" in seen["messages"][0]["content"]
    assert out["type"] == "device" and out["action"] == "add_calendar_event"
    assert out["args"]["start"] == "2026-11-06T19:00"


def test_compound_request_becomes_mission_in_order():
    c = Scripted(msg("All set.", [call("send_message", contact="Mom", body="I'm late", app="whatsapp", say="Texted Mom."),
                                  call("set_alarm", hour=7, minute=0, say="Alarm set for 7."),
                                  call("add_calendar_event", title="Dinner", start="2026-11-03T19:00", say="Added dinner.")]))
    out = Assistant(c, "m").turn("text mom i'm late, alarm at 7, add dinner at 7pm tomorrow")
    assert out["type"] == "mission" and out["say"] == "All set."
    assert [s["action"] for s in out["steps"]] == ["send_message", "set_alarm", "add_calendar_event"]
    assert out["steps"][1]["args"] == {"hour": 7, "minute": 0} and out["steps"][0]["say"] == "Texted Mom."


def test_remember_is_a_device_action_and_memory_reaches_prompt():
    seen = {}
    class Spy(Scripted):
        def _create(self, **kw):
            seen["system"] = kw["messages"][0]["content"]
            return super()._create(**kw)
    c = Spy(msg(calls=[call("remember", fact="Mom: WhatsApp", say="Got it.")]))
    out = Assistant(c, "m").turn("always whatsapp mom", memory=["Dad: SMS"])
    assert out["action"] == "remember" and out["args"] == {"fact": "Mom: WhatsApp"}
    assert "Dad: SMS" in seen["system"]


def test_rate_limit_sliding_window():
    from app import rate_limited
    assert not rate_limited("t1", now=0, limit=2) and not rate_limited("t1", now=1, limit=2)
    assert rate_limited("t1", now=2, limit=2)            # third request inside the minute is rejected
    assert not rate_limited("t1", now=62, limit=2)       # window has moved on
    assert not rate_limited("other", now=2, limit=2)     # other clients unaffected


def test_fastpath_simple_commands_skip_the_model():
    from assistant import fastpath
    assert fastpath("Set an alarm for 8 a.m.") == {"type": "device", "action": "set_alarm",
            "args": {"hour": 8, "minute": 0}, "say": "Alarm set for 8 AM."}
    assert fastpath("set an alarm for 7:30 pm")["args"] == {"hour": 19, "minute": 30}
    assert fastpath("wake me up at 12 am")["args"]["hour"] == 0
    assert fastpath("set a 10 minute timer")["args"] == {"seconds": 600}
    assert fastpath("set a timer for 1 hour")["say"] == "Timer set for 1 hour."
    assert fastpath("turn off the flashlight")["args"] == {"on": False}


def test_fastpath_leaves_compound_and_ambiguous_to_the_model():
    from assistant import fastpath
    assert fastpath("set an alarm for 7 am and text mom i'm late") is None
    assert fastpath("set an alarm for 7") is None            # am or pm? let the model ask
    assert fastpath("set an alarm for 25 pm") is None
    assert fastpath("text mom hello") is None


def test_intro_is_instant_and_spoken_as_jarvis():
    from assistant import fastpath
    for q in ("who are you", "Introduce yourself", "hey what's your name?", "intro"):
        out = fastpath(q)
        assert out and "introduce myself" in out["say"] and "Jarvis" in out["say"] and "JARVIS" not in out["say"] and out["card"]["title"] == "JARVIS"
    assert fastpath("what is the weather who are you asking about in this long sentence of words here") is None


def test_turn_uses_fastpath_only_without_context():
    class Boom:
        chat = NS(completions=NS(create=lambda **kw: (_ for _ in ()).throw(AssertionError("model should not be called"))))
    assert Assistant(Boom(), "m").turn("set an alarm for 8 am")["action"] == "set_alarm"
