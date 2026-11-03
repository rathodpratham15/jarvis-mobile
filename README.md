# JARVIS Mobile

**Delegate a task to your phone. It figures out how to do it.**

Siri and Bixby run the commands they were built for. JARVIS takes a goal in plain speech, uses a direct
phone tool when one exists, and otherwise looks at the screen and operates any app itself, one verified
step at a time. Built for the Meta Global AI Developer Hackathon (indie track, *Build for Your World*) on
**Meta Muse Spark 1.3**, **Muse Voice Transcribe** and **SAM 3.1**.

> Say "Hey Jarvis, set an alarm for 7 AM and text mummy on WhatsApp that I'm running late." One sentence,
> two actions, both done, one spoken summary.

## What it does

| You say | What happens |
|---|---|
| "Hey Jarvis, temperature in Boston" | Answered in the background, shown as a card, spoken in °C **and** °F |
| "Text Mom" (then answers its questions) | Multi-turn: asks the message, asks WhatsApp or SMS, sends |
| "Video call mummy" | Resolves the contact **on the phone** (an exact name beats a partial match: "mummy" is not "badi mummy") and starts a WhatsApp video call |
| "Set an alarm for 7 and text Mom I'm late and add dinner at 7 PM tomorrow" | **Mission Mode:** all actions in one request, run in order, one honest summary ("2 of 3 done" if one failed) |
| "Remember Mom is always on WhatsApp" | **On-device memory** you can read and delete ("what do you remember?", "forget Mom") |
| "Summarize this" / "add this to my calendar" / "reply to this saying I'll be late" | **Screen-aware:** screenshot and text are captured at the wake word, sent only when you point at the screen |
| "Turn on battery saver", or anything else | **Hidden agent:** operates the phone's screen in the background and reports the result |

Voice in is Meta's `muse-voice-transcribe-1.0`; voice out is ElevenLabs. If either fails, or ElevenLabs'
daily character budget is spent, the phone falls back to Samsung/Google speech so the assistant never goes silent.

## How it works

```
 "Hey Jarvis"  (Vosk wake word, runs on the phone)
        │  captures the screen first, then opens the panel
        ▼
 Panel records speech ──► POST /api/stt ──► Meta Muse Voice Transcribe
        │
        ▼
 POST /api/assistant ──► Muse Spark 1.3 with tools
        │      ┌───────────────┼────────────────┬──────────────────┐
        ▼      ▼               ▼                ▼                  ▼
     answer   device action   mission          ask_user          operate_phone
     (+card)  (alarm, SMS,    (several device  (speak a question,  (hidden agent loop)
              WhatsApp, call,  actions in       listen again)
              calendar, …)     order)
                                                        │
        ┌───────────────────────────────────────────────┘
        ▼   observe: screenshot + numbered accessibility tree
   POST /api/phone/step ──► Muse Spark picks ONE action ──► validate ──► safety gate ──► execute ──► repeat
                              tap node │ type │ scroll │ open_app │ tap "<icon description>" ──► SAM 3.1 finds pixels
```

**Design choices that matter**
- **Hybrid perception.** The agent sees the screenshot *and* a numbered accessibility tree, and prefers tapping real nodes over guessing coordinates.
- **One action per step, stateless backend.** Every step is independently testable and the server needs no session state.
- **Direct tools first.** Alarms, texts, calls and calendar events use Android APIs, which are faster and more reliable than driving the UI. The screen-driving agent is the fallback.
- **Verification.** The agent may only report `done` if it can cite evidence on the current screen. Mission Mode reports what actually failed instead of claiming success.
- **Contacts never leave the phone.** The model sends the name you said; matching happens on the device.

## Safety and privacy

- Consequential taps (pay, send, delete, order, call, …) need on-device **Allow / Deny**. A generic "Confirm" or "Continue" button is also gated when the screen shows money, a recipient or a deletion, and the prompt quotes what is on screen.
- Before acting, the phone checks the target element still exists and the foreground app is unchanged ("stale node" protection).
- A screenshot is sent **only** when your words point at the screen ("this", "here", "reply"). It is kept in memory for 90 seconds and cleared when the panel closes.
- Memory is stored only on the phone and sent as short text with each request.
- API keys live on the server. The app holds a device token. App backups are disabled.
- Use the standard `muse-spark-1.3` model, **not** the `-contributor` tier (that tier trains on inputs, including your screenshots).

## SAM 3.1 grounding: honest results

When no listed element fits (icon-only buttons, canvas or custom UIs), Muse Spark names the target
("three dots icon") and SAM 3.1 returns its pixel box. I benchmarked this on 17 targets from 3 screens of one
Samsung phone (home screen, Clock, app drawer) against accessibility-tree ground truth, tapping from the screenshot alone:

| Approach | Correct taps |
|---|---|
| Muse Spark guesses coordinates | 9 / 17 |
| Muse Spark names target + SAM 3.1 locates it | 6 / 17 |

SAM won the **icon-only** targets (plus, three-dots, close, phone icon: 4 of 4, where coordinate guessing got 0 of 4)
and lost on labeled folders and apps, where it sometimes picked the wrong match. So SAM is used **only as a fallback** when the accessibility
tree has no usable node. Small sample, one device; treat it as a signal, not a claim. Reproduce with
`backend/bench/grounding_bench.py`.

## Run it

**Backend**
```bash
cd backend && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env     # META_API_KEY, JARVIS_DEVICE_TOKEN; ELEVENLABS_* optional
.venv/bin/python app.py  # http://localhost:5050
.venv/bin/python -m pytest tests -q   # 22 tests
```

**Android app** (Node 22 is fetched via `npx`, JDK 17+, Android SDK 36)
```bash
bash scripts/fetch-wake-model.sh      # downloads the 68 MB Vosk model (not in git)
cd app && npm install && cp .env.example .env   # set VITE_DEFAULT_BACKEND / TOKEN
npm run build && npx -y node@22 node_modules/@capacitor/cli/bin/capacitor sync android
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:5050 tcp:5050         # only for a laptop backend over USB
```
In the app: enable **Accessibility → JARVIS**, tap **Turn on "Hey Jarvis"**, allow microphone, contacts, SMS, calendar and overlay permissions when asked.

**Deploy the backend** (Render): New → Blueprint → this repo (`render.yaml`). Enter `META_API_KEY`,
`JARVIS_DEVICE_TOKEN`, `ELEVENLABS_API_KEY`, `ELEVENLABS_VOICE_ID` in the dashboard, then put the service's HTTPS URL
in the app's Settings → Backend URL. The free tier sleeps when idle, so the first request after a pause can take about 30 s.

## Repo layout

```
backend/   Flask API: assistant.py (router + tools), phone_agent.py (screen agent + safety gate),
           sam_grounding.py, speech.py (STT/TTS), tests/, bench/
app/       React + Capacitor shell; android/ holds the native services:
           WakeWordService, AssistActivity (panel), JarvisAccessibilityService, AgentRunner,
           DeviceActions, ContactMatcher, Memory, ScreenContext
```

## Known limits

- **Accessibility-based control** is powerful and would face Google Play policy review; this is a sideloaded APK for the hackathon, not a store release.
- **One shared device token.** There are no per-user accounts yet; a per-client rate limit protects spend.
- **Server-side inference.** Reasoning and vision run on Meta's API; only the wake word runs on the device.
- WhatsApp sending relies on its on-screen layout and may need adjusting after app updates.
- The wake word can still mishear background speech; confidence is logged under `JarvisWake` for tuning.
- Tested on one Samsung phone. Hidden-agent success on arbitrary apps is not benchmarked yet.
