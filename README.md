# JARVIS Mobile

An AI assistant that operates your Android phone — Siri/Bixby, but it can actually *do* things in any app.
Say a goal; JARVIS looks at the screen, decides the next tap/type/scroll, and repeats until done.
Built on Meta's **Muse Spark 1.3** (vision + reasoning) via the Meta Model API.

```
app/       React + Capacitor shell, Java AccessibilityService + `DeviceControl` plugin
backend/   Flask: POST /api/phone/step  (screenshot + UI tree + history → next action)
```

## How it works
1. `DeviceControl.observe()` returns a screenshot and a numbered accessibility UI tree.
2. The backend asks Muse Spark for ONE action (`tap node`, `type`, `scroll`, `open_app`, `done`, …).
3. Actions are validated; consequential taps (pay/send/delete/order…) require on-device confirmation.
4. `DeviceControl.act()` performs it; loop until `done`/`fail` (max 25 steps).

## Run
```bash
cd backend && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
cp .env.example .env   # fill META_API_KEY and JARVIS_DEVICE_TOKEN
.venv/bin/python app.py                       # :5050
.venv/bin/python -m pytest tests -q

cd ../app && npm install
cp .env.example .env   # optional debug defaults baked into the APK
npm run android:apk    # needs Node 22 (fetched via npx), JDK 17+, Android SDK 36
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:5050 tcp:5050                 # phone localhost:5050 -> laptop
```
Then in the app: Settings → Accessibility → JARVIS → enable.

## Notes
- Muse Spark is a reasoning model; `max_tokens` must be generous and `reasoning_effort=low` keeps steps ~2–3 s.
- Use the standard `muse-spark-1.3`, not `-contributor` (that tier trains on your inputs — i.e. your screenshots).
