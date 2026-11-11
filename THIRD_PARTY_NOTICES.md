# Third-party notices

JARVIS Mobile is released under the MIT License (see `LICENSE`). It builds on the open-source software and
services below, each under its own terms.

## Libraries

| Component | Used for | License |
|---|---|---|
| Flask, Flask-CORS | Backend web API | BSD-3-Clause, MIT |
| gunicorn | Production server | MIT |
| openai (Python SDK) | OpenAI-compatible client for the Meta Model API | Apache-2.0 |
| httpx, python-dotenv | HTTP client, environment loading | BSD-3-Clause |
| certifi | TLS certificates | MPL-2.0 |
| Pillow | Benchmark script only (`backend/bench`) | HPND |
| React, Vite, TypeScript | In-app screens | MIT, MIT, Apache-2.0 |
| Capacitor | Android app shell and native bridge | MIT |
| Vosk (library) | On-device "Hey Jarvis" wake-word detection | Apache-2.0 |
| vosk-model-small-en-us | Wake-word model, downloaded by `scripts/fetch-wake-model.sh` (not stored in this repo) | Apache-2.0 |

## Models and services (accessed through their APIs, not redistributed)

- **Meta Model API**: Muse Spark 1.3 (reasoning and vision), Muse Voice Transcribe 1.0 (speech to text) and SAM 3.1
  (icon grounding), used under Meta's API terms. The non-contributor model tier is used so inputs are not used for training.
- **ElevenLabs**: text to speech, used under ElevenLabs' terms. The phone falls back to its built-in voice.
- **Open-Meteo**: weather data, licensed CC BY 4.0. Weather data by [Open-Meteo.com](https://open-meteo.com/).

## Design

The visual design (colors, panel and screen layouts, icons) was drafted with Google AI Studio and then adapted and
re-implemented for this app. The animated guide is the author's own character design, with its mouth shapes and blink generated as edits by Meta's
Muse Image model (see `docs/GUIDE_CHARACTER.md`). No other third-party artwork or fonts are bundled.

- **Inter** (variable font, bundled via `@fontsource-variable/inter`): SIL Open Font License 1.1, https://rsms.me/inter/
