import { useState } from "react";
import { ArcReactorLogo } from "../components/ArcReactorLogo";
import { SettingsIcon, MicIcon, PlayIcon, StopIcon, CheckIcon, ChevronDownIcon, AlertTriangleIcon } from "../components/SvgIcons";
import { DeviceControl, isNativeAndroid, PhoneAction } from "../deviceControl";
import { Status, Step } from "../usePhoneAgent";
import { Screen } from "../App";

const CHIPS = ["Set an alarm for 7 AM", "Text Mom I'm late", "Summarize this screen", "Turn on battery saver"];

const label = (a: PhoneAction) => {
  switch (a.action) {
    case "tap": return a.node !== undefined ? `Tapped element ${a.node}` : "Tapped the screen";
    case "type": return "Typed text";
    case "scroll": return `Scrolled ${a.direction ?? "down"}`;
    case "open_app": return `Opened ${a.name ?? "an app"}`;
    case "back": return "Went back";
    case "home": return "Went home";
    case "wait": return "Waited for the screen";
    case "done": return "Finished";
    case "fail": return "Couldn't continue";
    default: return a.action;
  }
};

interface Props {
  accessibility: boolean | null;
  wake: { running: boolean; overlay: boolean };
  wakeNote: string;
  onToggleWake: () => void;
  onFixAccessibility: () => void;
  status: Status; steps: Step[]; message: string;
  run: (goal: string) => void; stop: () => void;
  onNavigate: (s: Screen) => void;
  onboarded: boolean; onFinishOnboarding: () => void;
}

export function HomeScreen(p: Props) {
  const [goal, setGoal] = useState("");
  const [mic, setMic] = useState<"idle" | "listening" | "transcribing">("idle");
  const [micNote, setMicNote] = useState("");
  const [showLog, setShowLog] = useState(false);
  const running = p.status === "running";
  const ready = p.accessibility === true;

  const toggleMic = async () => {
    if (mic === "listening") { setMic("transcribing"); await DeviceControl.stopListening().catch(() => {}); return; }
    if (mic === "transcribing") return;
    setMicNote(""); setMic("listening");
    try {
      const { text } = await DeviceControl.listen();
      setGoal((g) => (g.trim() ? g.trim() + " " : "") + text);
    } catch (e: any) { setMicNote(e?.message || "Couldn't hear that."); }
    setMic("idle");
  };

  const header = (
    <>
      <header className="home-header">
        <div className="home-brand">
          <ArcReactorLogo size={34} state={running ? "thinking" : "idle"} />
          <span className="home-brand-title">JARVIS</span>
        </div>
        <button className="home-settings-btn touch-target" onClick={() => p.onNavigate("settings")} aria-label="Open settings"><SettingsIcon size={20} /></button>
      </header>
      <div className="brand-tagline-bar">
        <span className="brand-tagline">Delegate a task. It figures out how to do it.</span>
        <span className="brand-subtitle">Just A Rather Very Intelligent System</span>
      </div>
    </>
  );

  if (!p.onboarded && !(ready && p.wake.running)) {
    return (
      <div className="home-screen">
        {header}
        <div className="first-launch-box">
          <div className="welcome-hero">
            <ArcReactorLogo size={54} state="idle" />
            <h2 className="welcome-title">Welcome to JARVIS</h2>
            <p className="welcome-desc">To operate apps for you, turn on these three things.</p>
          </div>
          <div className="first-launch-checklist">
            <div className="checklist-step-item">
              <div className="step-item-left"><span className="step-number-badge">1</span>
                <div><div className="step-item-title">Accessibility service</div><div className="step-item-desc">Lets JARVIS tap, type and scroll in apps</div></div></div>
              <button className="status-fix-btn touch-target" onClick={p.onFixAccessibility} disabled={ready}>{ready ? "Enabled ✓" : "Enable"}</button>
            </div>
            <div className="checklist-step-item">
              <div className="step-item-left"><span className="step-number-badge">2</span>
                <div><div className="step-item-title">“Hey Jarvis” wake word</div><div className="step-item-desc">Listens on this phone for the trigger phrase</div></div></div>
              <button className="status-fix-btn touch-target" onClick={p.onToggleWake} disabled={p.wake.running}>{p.wake.running ? "Active ✓" : "Turn on"}</button>
            </div>
            <div className="checklist-step-item">
              <div className="step-item-left"><span className="step-number-badge">3</span>
                <div><div className="step-item-title">Permissions</div><div className="step-item-desc">Microphone, contacts, SMS, calendar, overlay</div></div></div>
              <button className="status-fix-btn touch-target" onClick={() => p.onNavigate("permissions")}>Configure</button>
            </div>
          </div>
          {p.wakeNote && <p className="message">{p.wakeNote}</p>}
          <button className="run-primary-btn" onClick={p.onFinishOnboarding}>Get started</button>
        </div>
      </div>
    );
  }

  const ok = p.status === "done";
  return (
    <div className="home-screen">
      {header}

      <div className="status-strip">
        <div className={`status-card ${ready ? "active" : ""}`}>
          <div className="status-card-info">
            <span className="status-label">Accessibility</span>
            <span className={`status-value ${ready ? "on" : "off"}`}>{ready ? "Active" : "Off"}</span>
          </div>
          {ready ? <CheckIcon size={16} color="var(--success)" />
            : <button className="status-fix-btn touch-target" onClick={p.onFixAccessibility}>Fix</button>}
        </div>
        <div className={`status-card ${p.wake.running ? "active" : ""}`}>
          <div className="status-card-info">
            <span className="status-label">“Hey Jarvis”</span>
            <span className={`status-value ${p.wake.running ? "on" : "off"}`}>{p.wake.running ? "Listening" : "Off"}</span>
          </div>
          {p.wake.running
            ? <div className="wave-bars-anim" role="button" aria-label="Turn off Hey Jarvis" onClick={p.onToggleWake} style={{ cursor: "pointer" }}>
                <span className="wave-bar" /><span className="wave-bar" /><span className="wave-bar" /><span className="wave-bar" /></div>
            : <button className="status-fix-btn touch-target" onClick={p.onToggleWake}>Turn on</button>}
        </div>
      </div>
      {p.wakeNote && <p className="message">{p.wakeNote}</p>}

      <div className="prompt-box-container">
        <textarea className="prompt-textarea" rows={3} value={goal} onChange={(e) => setGoal(e.target.value)}
          placeholder="Tell JARVIS what to do…" aria-label="Command for JARVIS" />
        <div className="prompt-box-bottom">
          <span className="prompt-status-hint">
            {mic === "listening" ? <><span style={{ color: "var(--danger)" }}>●</span> Listening… tap to stop</>
              : mic === "transcribing" ? <><span style={{ color: "var(--secondary-violet)" }}>●</span> Transcribing…</>
              : micNote || "Type or tap the mic"}
          </span>
          {isNativeAndroid() && (
            <button className={`mic-btn-round touch-target ${mic}`} onClick={toggleMic}
              aria-label={mic === "listening" ? "Stop listening" : "Speak your request"}>
              {mic === "listening" ? <StopIcon size={20} /> : <MicIcon size={22} />}
            </button>
          )}
        </div>
      </div>

      <div className="suggestion-chips-grid">
        {CHIPS.map((c) => <button key={c} className="suggestion-chip" onClick={() => setGoal(c)}><span>{c}</span></button>)}
      </div>

      <div className="execution-action-row">
        {running
          ? <button className="stop-danger-btn touch-target" onClick={p.stop}><StopIcon size={16} /><span>Stop</span></button>
          : <button className="run-primary-btn touch-target" onClick={() => { p.run(goal); setShowLog(true); }} disabled={!goal.trim() || !ready}>
              <PlayIcon size={16} /><span>Run</span></button>}
        <div className={`status-badge-pill ${p.status}`}><span>●</span><span>{p.status}</span></div>
      </div>
      {!ready && <p className="message">Turn on the accessibility service so JARVIS can operate apps.</p>}

      {(p.steps.length > 0 || p.message) && (
        <div className="run-log-card">
          <div className="run-log-header">
            <span className="run-log-title">Activity</span>
            <button className="run-log-toggle-btn touch-target" onClick={() => setShowLog(!showLog)} aria-expanded={showLog}>
              <span>{showLog ? "Hide details" : "Show details"}</span>
              <ChevronDownIcon size={14} style={{ transform: showLog ? "rotate(180deg)" : "none", transition: "transform 180ms ease" }} />
            </button>
          </div>
          {p.message && (
            <div className={`result-card-banner ${ok ? "success" : p.status === "running" ? "" : "failed"}`}>
              {ok ? <CheckIcon size={18} color="var(--success)" style={{ marginTop: 2, flexShrink: 0 }} />
                : <AlertTriangleIcon size={18} color="var(--warning)" style={{ marginTop: 2, flexShrink: 0 }} />}
              <div className="result-card-text"><strong>Result:</strong> {p.message}</div>
            </div>
          )}
          {showLog && (
            <div className="run-log-timeline">
              {p.steps.map((s) => (
                <div key={s.step} className={`timeline-step-row ${/failed|stale/i.test(s.result) ? "failed" : "success"}`}>
                  <div className="step-label-time"><span className="step-label">{label(s.action)}</span><span className="step-time">Step {s.step}</span></div>
                  <div className="step-detail">{s.action.reason || s.result}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
