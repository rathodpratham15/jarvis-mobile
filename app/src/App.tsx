import { useCallback, useEffect, useRef, useState } from "react";
import { DeviceControl, isNativeAndroid } from "./deviceControl";
import { Settings, usePhoneAgent } from "./usePhoneAgent";

const PRESETS = [
  "Turn on battery saver",
  "Set an alarm for 7 AM",
  "Open Chrome and search the weather in Boston",
];

const load = (): Settings => {
  try { return JSON.parse(localStorage.getItem("jarvis.settings") ?? ""); } catch { /* first run */ }
  return {
    baseUrl: import.meta.env.VITE_DEFAULT_BACKEND ?? "http://localhost:5050",
    token: import.meta.env.VITE_DEFAULT_TOKEN ?? "",
    fallbackUrl: import.meta.env.VITE_DEFAULT_FALLBACK ?? "",
  };
};

/** Trim whitespace and trailing slashes so a pasted URL always works. */
const clean = (v: Settings): Settings => ({
  ...v,
  baseUrl: v.baseUrl.trim().replace(/\/+$/, ""),
  fallbackUrl: (v.fallbackUrl ?? "").trim().replace(/\/+$/, ""),
  token: v.token.trim(),
});

export default function App() {
  const [settings, setSettings] = useState<Settings>(load);
  const [showSettings, setShowSettings] = useState(!load().token);
  const [draft, setDraft] = useState<Settings>(load);
  const [saveNote, setSaveNote] = useState("");
  const [testNote, setTestNote] = useState("");
  const [goal, setGoal] = useState("");
  const [listening, setListening] = useState(false);
  const [micNote, setMicNote] = useState("");
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [wake, setWake] = useState<{ running: boolean; overlay: boolean }>({ running: false, overlay: false });
  const [wakeNote, setWakeNote] = useState("");
  const [pending, setPending] = useState<string | null>(null);
  const resolver = useRef<((ok: boolean) => void) | null>(null);

  const confirm = useCallback((prompt: string) => new Promise<boolean>((resolve) => {
    resolver.current = resolve; setPending(prompt);
  }), []);
  const answer = (ok: boolean) => { resolver.current?.(ok); resolver.current = null; setPending(null); };

  const { status, steps, message, run, stop } = usePhoneAgent(settings, confirm);
  const running = status === "running";

  // Share backend settings with the native assistant (voice panel runs without the web UI).
  useEffect(() => {
    if (isNativeAndroid()) DeviceControl.saveConfig(settings).catch(() => { /* ignore */ });
  }, [settings]);

  useEffect(() => {
    const check = async () => {
      if (!isNativeAndroid()) return setEnabled(false);
      try { setEnabled((await DeviceControl.isEnabled()).enabled); } catch { setEnabled(false); }
      try { setWake(await DeviceControl.wakeWordStatus()); } catch { /* not native */ }
    };
    check();
    const t = setInterval(check, 2000);
    return () => clearInterval(t);
  }, []);

  const save = (s: Settings) => {
    setSettings(s);
    try { localStorage.setItem("jarvis.settings", JSON.stringify(s)); } catch { /* ignore */ }
  };

  return (
    <main>
      <header>
        <h1>J.A.R.V.I.S</h1>
        <button className="ghost" onClick={() => setShowSettings((v) => !v)}>⚙</button>
      </header>

      {showSettings && (
        <section className="card">
          <label>Backend URL
            <input value={draft.baseUrl} inputMode="url" autoCapitalize="off" autoCorrect="off"
              onChange={(e) => { setDraft({ ...draft, baseUrl: e.target.value }); setSaveNote(""); }} />
          </label>
          <label>Backup URL (optional)
            <input value={draft.fallbackUrl ?? ""} inputMode="url" autoCapitalize="off" autoCorrect="off"
              onChange={(e) => { setDraft({ ...draft, fallbackUrl: e.target.value }); setSaveNote(""); }} />
          </label>
          <label>Device token
            <input type="password" value={draft.token}
              onChange={(e) => { setDraft({ ...draft, token: e.target.value }); setSaveNote(""); }} />
          </label>
          <div className="row">
            <button className="primary" disabled={JSON.stringify(clean(draft)) === JSON.stringify(settings)}
              onClick={() => { const c = clean(draft); setDraft(c); save(c); setSaveNote("Saved \u2713"); }}>Save</button>
            <button onClick={async () => {
              setTestNote("Connecting\u2026 (a sleeping server can take ~30 s)");
              try {
                const r = await fetch(`${clean(draft).baseUrl}/api/health`);
                const j = await r.json();
                setTestNote(r.ok && j.status === "ok" ? `Connected \u2713 (agent ${j.agent ? "ready" : "not configured"})` : `Server answered ${r.status}`);
              } catch { setTestNote("Can't reach that URL. Check it and your connection."); }
            }}>Test connection</button>
            <span className="status">{saveNote}</span>
          </div>
          {testNote && <p className="message">{testNote}</p>}
        </section>
      )}

      {enabled === false && (
        <section className="card warn">
          <p>{isNativeAndroid()
            ? "Turn on the JARVIS accessibility service so it can see and operate your screen."
            : "Device control only works inside the Android app."}</p>
          {isNativeAndroid() && <button onClick={() => DeviceControl.openSettings()}>Open Accessibility Settings</button>}
        </section>
      )}

      {isNativeAndroid() && (
        <section className={`card ${wake.running ? "" : "warn"}`}>
          <b>{wake.running ? "\u201cHey Jarvis\u201d is on" : "\u201cHey Jarvis\u201d is off"}</b>
          {wakeNote && <small>{wakeNote}</small>}
          {wake.running
            ? <button onClick={() => DeviceControl.disableWakeWord()}>Turn off</button>
            : <button className="primary" onClick={async () => {
                setWakeNote("");
                try { await DeviceControl.enableWakeWord(); }
                catch (e) {
                  const m = e instanceof Error ? e.message : String(e);
                  setWakeNote(m === "overlay" ? "Allow \u201cDisplay over other apps\u201d for JARVIS, come back, and tap again."
                    : m === "mic" ? "Microphone permission is required." : m);
                }
              }}>Turn on &ldquo;Hey Jarvis&rdquo;</button>}
        </section>
      )}

      <div className="promptbox">
        <textarea rows={3} value={goal} placeholder={listening ? "Listening\u2026" : "Type or tap the mic and speak"}
          onChange={(e) => setGoal(e.target.value)} />
        {isNativeAndroid() && (
          <button className={`mic ${listening ? "on" : ""}`} aria-label={listening ? "Stop listening" : "Speak your request"}
            onClick={async () => {
              if (listening) { await DeviceControl.stopListening(); return; }
              setMicNote(""); setListening(true);
              try {
                const { text } = await DeviceControl.listen();
                setGoal((g) => (g.trim() ? g.trim() + " " : "") + text);
              } catch (e: any) {
                setMicNote(e?.message || "Couldn't hear that.");
              } finally { setListening(false); }
            }}>{listening ? "\u25A0" : "\uD83C\uDF99"}</button>
        )}
      </div>
      {micNote && <p className="message">{micNote}</p>}
      <div className="chips">
        {PRESETS.map((p) => <button key={p} className="chip" onClick={() => setGoal(p)}>{p}</button>)}
      </div>

      <div className="row">
        <button className="primary" disabled={running || !goal.trim() || !enabled} onClick={() => run(goal)}>Run</button>
        {running && <button className="danger" onClick={stop}>Stop</button>}
        <span className={`status ${status}`}>{status}</span>
      </div>

      {message && <p className="message">{message}</p>}
      <ol className="log">
        {steps.map((s) => (
          <li key={s.step}>
            <b>{s.step}. {s.action.action}</b>{s.action.reason && <em> — {s.action.reason}</em>}
            <small>{s.result}</small>
          </li>
        ))}
      </ol>

      {pending && (
        <div className="modal"><div className="card warn">
          <h2>Confirm action</h2><p>{pending}</p>
          <div className="row">
            <button onClick={() => answer(false)}>Deny</button>
            <button className="primary" onClick={() => answer(true)}>Allow</button>
          </div>
        </div></div>
      )}
    </main>
  );
}
