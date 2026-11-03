"""Voice-assistant router.

One utterance in, one of three outcomes out:
  answer  – spoken reply (+ optional info card), produced entirely server-side (weather, time, facts)
  device  – a direct Android action (alarm, timer, flashlight, open app, dial, compose SMS) + spoken confirmation
  operate – no direct tool fits; the phone runs the hidden screen-driving agent (phone_agent.py)

Server tools run here; device tools are returned to the phone, which executes them via system APIs.
"""
from __future__ import annotations

import json
import logging
import os
from datetime import datetime
from typing import Any, Callable, Optional

import httpx

logger = logging.getLogger(__name__)

SYSTEM = """You are JARVIS, a voice assistant on the user's Android phone. Be fast and brief:
replies are spoken aloud, so use one or two short sentences and no markdown.
Always prefer a direct tool over operating the phone's screen:
- facts about the world you know -> just answer
- weather -> get_weather (say BOTH Celsius and Fahrenheit);  date/time -> get_datetime
- alarms/timers/flashlight/open app/dial/text -> the matching device tool (include a short `say` confirmation in past tense, e.g. "Alarm set for 7 AM.")
- only if NO tool fits (e.g. "turn on battery saver", "order my usual on DoorDash", anything inside another app) call operate_phone.
Messaging and calls:
- send_message needs a contact, the message body and an app (whatsapp or sms). If the BODY is missing, call ask_user("What should the message say?"). If the app is not stated and more than one messaging app is available, call ask_user("WhatsApp or Messages?"); if only one is available use it. Do NOT ask for things the user already said.
- Pass the contact EXACTLY as the user said it ("mummy", "dad"); the phone resolves it locally. Clean the body into what should be sent ("say hello" -> "Hello").
- call_contact: "video call"/"video" -> video=true and app=whatsapp unless another app is named; plain "call X" -> app=phone unless WhatsApp is named.
- The `say` on these tools is spoken after it succeeds: "Text sent to Mom." / "Video calling Mummy on WhatsApp."
- Earlier turns of this conversation may be included; use them to fill in missing details.
Screen awareness:
- If a SCREEN image is attached, "this", "here" and "it" refer to it. Answer questions about it directly ("what does this say", "summarize this", "translate this", "who is this from").
- For actions on the current screen (reply to a message, press something, fill a field) call operate_phone with a goal written for an ALREADY OPEN screen: name the app and exactly what to do, e.g. "In the open WhatsApp chat with Rohan, type \"I'll be late\" in the message box and tap Send."
- For an event or date shown on the screen, call add_calendar_event. Resolve relative dates against the current local time given below.
Never claim you did something unless you called the tool. Never invent phone numbers."""

WMO = {0: "Clear", 1: "Mostly clear", 2: "Partly cloudy", 3: "Overcast", 45: "Fog", 48: "Fog",
       51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle", 61: "Light rain", 63: "Rain", 65: "Heavy rain",
       71: "Light snow", 73: "Snow", 75: "Heavy snow", 80: "Rain showers", 81: "Rain showers", 82: "Heavy showers",
       95: "Thunderstorm", 96: "Thunderstorm with hail", 99: "Thunderstorm with hail"}


def _fn(name: str, desc: str, props: dict, required: list[str]) -> dict:
    return {"type": "function", "function": {"name": name, "description": desc,
            "parameters": {"type": "object", "properties": props, "required": required}}}


_SAY = {"say": {"type": "string", "description": "Short spoken confirmation in past tense."}}

TOOLS = [
    _fn("get_weather", "Current weather and today's high/low for a city.",
        {"location": {"type": "string", "description": "City, e.g. 'Boston'"}}, ["location"]),
    _fn("get_datetime", "Current local date and time on the user's phone.", {}, []),
    _fn("set_alarm", "Set an alarm (24h clock).",
        {"hour": {"type": "integer"}, "minute": {"type": "integer"}, "label": {"type": "string"}, **_SAY},
        ["hour", "minute", "say"]),
    _fn("set_timer", "Start a countdown timer.",
        {"seconds": {"type": "integer"}, "label": {"type": "string"}, **_SAY}, ["seconds", "say"]),
    _fn("flashlight", "Turn the flashlight on or off.", {"on": {"type": "boolean"}, **_SAY}, ["on", "say"]),
    _fn("open_app", "Open an installed app by name.", {"name": {"type": "string"}, **_SAY}, ["name", "say"]),
    _fn("dial_number", "Open the dialer with a number the user stated (does not place the call).",
        {"number": {"type": "string"}, **_SAY}, ["number", "say"]),
    _fn("send_sms", "Open a text message draft to a number the user stated (user taps send).",
        {"number": {"type": "string"}, "body": {"type": "string"}, **_SAY}, ["number", "body", "say"]),
    _fn("send_message", "Send a text message to a saved contact.",
        {"contact": {"type": "string"}, "body": {"type": "string"},
         "app": {"type": "string", "enum": ["whatsapp", "sms"]}, **_SAY}, ["contact", "body", "app", "say"]),
    _fn("call_contact", "Call or video-call a saved contact.",
        {"contact": {"type": "string"}, "app": {"type": "string", "enum": ["phone", "whatsapp"]},
         "video": {"type": "boolean"}, **_SAY}, ["contact", "app", "say"]),
    _fn("add_calendar_event", "Add an event to the user's calendar.",
        {"title": {"type": "string"},
         "start": {"type": "string", "description": "Local start time, ISO format YYYY-MM-DDTHH:MM"},
         "end": {"type": "string", "description": "Optional local end time, same format; default 1 hour"},
         "location": {"type": "string"}, **_SAY}, ["title", "start", "say"]),
    _fn("ask_user", "Ask the user ONE short question to get missing information, then wait for the reply.",
        {"question": {"type": "string"}}, ["question"]),
    _fn("operate_phone", "Fallback: operate the phone's screen to accomplish a goal when no other tool fits.",
        {"goal": {"type": "string", "description": "Full, self-contained instruction."}}, ["goal"]),
]
DEVICE_TOOLS = {"set_alarm", "set_timer", "flashlight", "open_app", "dial_number", "send_sms",
                "send_message", "call_contact", "add_calendar_event"}


def get_weather(location: str, units: str = "", _http: Optional[httpx.Client] = None) -> dict:
    """Always reports both °C and °F (and km/h + mph). `units` is accepted for compatibility."""
    http = _http or httpx.Client(timeout=10)
    geo = http.get("https://geocoding-api.open-meteo.com/v1/search",
                   params={"name": location, "count": 1}).json().get("results")
    if not geo:
        return {"error": f"Could not find a place called {location}."}
    g = geo[0]
    w = http.get("https://api.open-meteo.com/v1/forecast", params={
        "latitude": g["latitude"], "longitude": g["longitude"], "timezone": "auto",
        "current": "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m",
        "daily": "temperature_2m_max,temperature_2m_min",
        "temperature_unit": "celsius", "wind_speed_unit": "kmh"}).json()
    cur, day = w["current"], w["daily"]
    place = ", ".join(x for x in (g.get("name"), g.get("admin1"), g.get("country")) if x)
    both = lambda c: f"{round(c)}°C / {round(c * 9 / 5 + 32)}°F"
    kmh = cur["wind_speed_10m"]
    return {
        "place": place, "temp": both(cur["temperature_2m"]), "feels_like": both(cur["apparent_temperature"]),
        "condition": WMO.get(cur["weather_code"], "—"), "humidity": f'{cur["relative_humidity_2m"]}%',
        "wind": f"{round(kmh)} km/h / {round(kmh * 0.621371)} mph",
        "high": both(day["temperature_2m_max"][0]), "low": both(day["temperature_2m_min"][0]),
    }


def get_datetime() -> dict:
    n = datetime.now().astimezone()
    return {"date": n.strftime("%A, %B %d, %Y"), "time": n.strftime("%I:%M %p").lstrip("0")}


def _weather_card(d: dict) -> dict:
    return {"title": d["place"], "big": d["temp"], "subtitle": d["condition"],
            "rows": [["High", d["high"]], ["Low", d["low"]], ["Feels like", d["feels_like"]],
                     ["Humidity", d["humidity"]], ["Wind", d["wind"]]]}


class Assistant:
    def __init__(self, client: Any, model: str, max_loops: int = 4):
        self.client, self.model, self.max_loops = client, model, max_loops
        effort = os.getenv("META_REASONING_EFFORT", "low")
        self.extra = {"reasoning_effort": effort} if effort else None

    def turn(self, utterance: str, units: str = "imperial", attachments: Optional[list[str]] = None,
             server_tools: Optional[dict[str, Callable]] = None, history: Optional[list[dict]] = None,
             messaging_apps: Optional[list[str]] = None, screen: Optional[dict] = None,
             now: str = "", tz: str = "") -> dict:
        tools_impl = server_tools or {"get_weather": get_weather, "get_datetime": get_datetime}
        user: list[dict] = [{"type": "text", "text": utterance}]
        if screen and screen.get("b64"):
            txt = (screen.get("text") or "")[:1500]
            user.append({"type": "text", "text": f"[SCREEN of {screen.get('package') or 'the current app'}"
                                                 + (f"; visible text: {txt}" if txt else "") + "]"})
            user.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{screen['b64']}"}})
        for b64 in attachments or []:
            user.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}})
        system = SYSTEM
        if now:
            system += f"\nCurrent local time: {now}" + (f" ({tz})." if tz else ".")
        if messaging_apps is not None:
            system += f"\nMessaging apps installed on this phone: {', '.join(messaging_apps) or 'none'}."
        prior = [{"role": h["role"], "content": str(h["content"])[:500]} for h in (history or [])[-8:]
                 if isinstance(h, dict) and h.get("role") in ("user", "assistant") and h.get("content")]
        msgs: list[dict] = [{"role": "system", "content": system}, *prior, {"role": "user", "content": user}]
        card = None
        for _ in range(self.max_loops):
            try:
                r = self.client.chat.completions.create(
                    model=self.model, messages=msgs, tools=TOOLS, tool_choice="auto",
                    max_tokens=4000, extra_body=self.extra)
            except Exception as exc:
                logger.exception("assistant LLM call failed")
                import openai
                net = isinstance(exc, (openai.APITimeoutError, openai.APIConnectionError))
                return {"type": "answer", "error": str(exc),
                        "say": "I can't reach Meta's servers right now. Check your connection and try again." if net
                        else "Sorry, something went wrong on my side."}
            m = r.choices[0].message
            calls = m.tool_calls or []
            if not calls:
                return {"type": "answer", "say": (m.content or "").strip() or "Done.", "card": card}
            msgs.append({"role": "assistant", "content": m.content, "tool_calls": [
                {"id": c.id, "type": "function", "function": {"name": c.function.name, "arguments": c.function.arguments}}
                for c in calls]})
            for c in calls:
                name = c.function.name
                try:
                    args = json.loads(c.function.arguments or "{}")
                except json.JSONDecodeError:
                    args = {}
                if name == "ask_user":
                    return {"type": "ask", "say": args.get("question") or "Could you say more?"}
                if name == "operate_phone":
                    return {"type": "operate", "goal": args.get("goal") or utterance,
                            "say": (m.content or "").strip() or "On it."}
                if name in DEVICE_TOOLS:
                    say = args.pop("say", None) or "Done."
                    return {"type": "device", "action": name, "args": args, "say": say}
                fn = tools_impl.get(name)
                try:
                    result = fn(**({"units": units, **args} if name == "get_weather" else args)) if fn else {"error": "unknown tool"}
                except Exception as exc:
                    logger.exception("tool %s failed", name)
                    result = {"error": f"{name} failed: {exc}"}
                if name == "get_weather" and "error" not in result:
                    card = _weather_card(result)
                    city = result["place"].split(",")[0]
                    return {"type": "answer", "card": card,
                            "say": f"It's {result['temp']} and {result['condition'].lower()} in {city}."}
                msgs.append({"role": "tool", "tool_call_id": c.id, "content": json.dumps(result)})
        return {"type": "answer", "say": "Sorry, that took too many steps.", "card": card}


def build_assistant() -> Optional[Assistant]:
    key, base, model = os.getenv("META_API_KEY"), os.getenv("META_API_BASE_URL"), os.getenv("META_MODEL")
    if not (key and base and model):
        return None
    from openai import OpenAI
    # Fail fast: a spoken assistant should not hang for minutes.
    return Assistant(OpenAI(api_key=key, base_url=base, timeout=httpx.Timeout(20.0, connect=4.0), max_retries=0), model)
