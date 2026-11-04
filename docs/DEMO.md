# Demo script (target 2:45 to 3:00)

The submission needs a **three-minute** video. It is the main thing judges watch, so it must be public and play without
signing in. Show **only what was built during the hackathon** (this repo, started Nov 2): never show the older desktop
assistant.

## Before recording

- Open `https://jarvis-mobile-backend.onrender.com/api/health` once (wakes the free tier); laptop backend off.
- Phone: volume up, Do Not Disturb on, **close floating bubbles** from other apps (they appear in screen recordings),
  notifications cleared, "Hey Jarvis" on and showing "Listening", accessibility on.
- Make a contact named "mummy" (or use a friend who agreed) and open one WhatsApp chat in advance.
- Clear memory first (Settings → Memories → Forget everything), then do one dry run of every step.
- Record the screen with the phone's built-in recorder plus a voice-over, or record the voice-over afterwards.

## Script

| Time | Say / do | What the viewer should see |
|---|---|---|
| 0:00 | Voice-over, one personal sentence about why you built it (**yours to write**), then: "Siri and Bixby run commands. JARVIS takes a goal and figures out how to do it." | Phone home screen, then the JARVIS app |
| 0:15 | "Hey Jarvis, what's the temperature in Boston?" | Panel slides up, orb pulses, weather card with °C **and** °F, spoken answer |
| 0:30 | "Hey Jarvis, set an alarm for 7 AM and text mummy on WhatsApp that I'm running late." | **Mission Mode:** the checklist ticks off the steps, one spoken summary. Cut to the alarm and the chat. |
| 0:55 | "Hey Jarvis, scan this." Tap **Live scan**. Point at one object (a mug or a bottle) on a plain surface and tap it to focus. | The **guide pops up from the object**, talks with lip-sync and a fun fact. Pan the camera slowly: it follows. Point away: it leaves. The headline moment: Muse Image made the character, SAM 3.1 places it. |
| 1:35 | Point at your resume and tap **Full summary**. | One spoken line, every section on the screen |
| 1:50 | "Hey Jarvis, summarize this" on a chat, then "add this to my calendar" on a page with a date. | Screen-aware answers (keep this to about 15 seconds) |
| 2:05 | "Hey Jarvis, turn on battery saver." Start a longer task and tap **Stop** in the pill. | The hidden agent at work; Stop works within a second or two |
| 2:20 | Reach a send or payment-style screen and let the safety prompt appear; tap Deny. | The confirmation quotes what is on screen |
| 2:30 | Voice-over over the architecture diagram or README: "Muse Spark reasons and sees, Muse Voice Transcribe hears, Muse Image animated the guide's face, SAM 3.1 places it and finds icon-only buttons. Contacts never leave the phone. It's open source under MIT." | README diagram, repo page |
| 2:50 | "JARVIS Mobile. Delegate a task. It figures out how." | Logo, GitHub URL |

## If something fails

Keep recording and re-run the step; never splice in a result that did not happen. If the wake word does not fire, say
"Hey Jarvis" once more, clearly, and wait for the glow before speaking. If a step is flaky, cut it from the video rather than
fake it. A shorter honest demo beats a longer fragile one.

## Submission checklist

- [ ] Repository public and visible when logged out; `LICENSE` shows on the repo page
- [ ] Video link works when logged out, runs about 3 minutes, shows only hackathon work
- [ ] Release APK installs; token given **only** in the private testing-instructions field
- [ ] All team members listed on the submission page
- [ ] Render service awake; token changed after judging
