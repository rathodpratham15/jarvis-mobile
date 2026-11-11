import { useState } from "react";
import { ArcReactorLogo } from "../components/ArcReactorLogo";
import { FluidOrb, OrbState } from "../components/FluidOrb";
import { SettingsIcon, MicIcon, StopIcon, CheckIcon, ChevronDownIcon, AlertTriangleIcon } from "../components/SvgIcons";
import { DeviceControl, PhoneAction } from "../deviceControl";
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

const ArrowUp = () => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round"><path d="M12 19V5M5 12l7-7 7 7" /></svg>
);

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

  const orb: OrbState = running ? "thinking" : mic === "listening" ? "listening" : mic === "transcribing" ? "thinking" : "idle";

  const topbar = (
    <header className="topbar">
      <div className="brand"><ArcReactorLogo size={24} glow={false} /><span className="brand-word">JARVIS</span></div>
      <button className="icon-btn glass" onClick={() => p.onNavigate("settings")} aria-label="Open settings"><SettingsIcon size={20} /></button>
    </header>
  );

  if (!p.onboarded && !(ready && p.wake.running)) {
    const Row = ({ n, done, title, desc, action, onClick, disabled }: { n: number; done: boolean; title: string; desc: string; action: string; onClick: () => void; disabled?: boolean }) => (
      <div className="check-row">
        <span className={`check-num ${done ? "done" : ""}`}>{done ? <CheckIcon size={15} /> : n}</span>
        <div className="check-text"><div className="check-title">{title}</div><div className="check-desc">{desc}</div></div>
        <button className={done ? "perm-btn manage" : "perm-btn grant"} onClick={onClick} disabled={disabled}>{done ? "On" : action}</button>
      </div>
    );
    return (
      <div className="home">
        {topbar}
        <div className="hero">
          <FluidOrb size={230} state="idle" />
          <h1 className="hero-title">Welcome to JARVIS</h1>
          <p className="hero-sub">Turn on three things so it can work for you.</p>
        </div>
        <div className="welcome">
          <div className="checklist glass">
            <Row n={1} done={ready} title="Accessibility" desc="Lets JARVIS tap, type and scroll in apps" action="Enable" onClick={p.onFixAccessibility} disabled={ready} />
            <Row n={2} done={p.wake.running} title="“Hey Jarvis”" desc="Listens on this phone for the wake phrase" action="Turn on" onClick={p.onToggleWake} disabled={p.wake.running} />
            <Row n={3} done={false} title="Permissions" desc="Microphone, contacts, camera, calendar" action="Review" onClick={() => p.onNavigate("permissions")} />
          </div>
          {p.wakeNote && <p className="note">{p.wakeNote}</p>}
          <div className="spacer" />
          <button className="btn btn-primary btn-block" onClick={p.onFinishOnboarding}>Get started</button>
        </div>
      </div>
    );
  }

  const ok = p.status === "done";
  const hint = mic === "listening" ? "Listening… tap to stop" : mic === "transcribing" ? "Transcribing…" : micNote || (running ? "Working on it…" : "");

  return (
    <div className="home">
      {topbar}

      <div className="hero">
        <FluidOrb size={236} state={orb} onClick={toggleMic} label="Tap to speak" />
        <h1 className="hero-title">{running ? "On it." : "How can I help?"}</h1>
        <p className="hero-sub">{running ? "You can stop me any time." : "Say “Hey Jarvis”, or tap the orb."}</p>
      </div>

      <div className="pills">
        <div className={`pill glass ${ready ? "on" : ""}`}>
          <span className="pill-dot" />
          <div className="pill-text"><span className="pill-label">Accessibility</span><span className="pill-value">{ready ? "Active" : "Off"}</span></div>
          {!ready && <button className="pill-act" onClick={p.onFixAccessibility}>Fix</button>}
        </div>
        <div className={`pill glass ${p.wake.running ? "on" : ""}`}>
          <span className="pill-dot" />
          <div className="pill-text"><span className="pill-label">“Hey Jarvis”</span><span className="pill-value">{p.wake.running ? "Listening" : "Off"}</span></div>
          {p.wake.running
            ? <button className="bars" onClick={p.onToggleWake} aria-label="Turn off Hey Jarvis"><span /><span /><span /><span /></button>
            : <button className="pill-act" onClick={p.onToggleWake}>Turn on</button>}
        </div>
      </div>
      {p.wakeNote && <p className="note">{p.wakeNote}</p>}
      {!ready && <p className="note">Turn on accessibility so JARVIS can operate apps for you.</p>}

      {(p.steps.length > 0 || p.message) && (
        <div className="activity glass">
          <div className="activity-head">
            <span className="activity-title">Activity</span>
            <button className="link-btn" onClick={() => setShowLog(!showLog)} aria-expanded={showLog}>
              <span>{showLog ? "Hide" : "Details"}</span>
              <ChevronDownIcon size={14} style={{ transform: showLog ? "rotate(180deg)" : "none", transition: "transform 200ms" }} />
            </button>
          </div>
          {p.message && (
            <div className={`result ${ok ? "success" : running ? "" : "failed"}`}>
              {ok ? <CheckIcon size={19} color="var(--ok)" style={{ marginTop: 1, flexShrink: 0 }} />
                : <AlertTriangleIcon size={19} color="var(--warn)" style={{ marginTop: 1, flexShrink: 0 }} />}
              <div>{p.message}</div>
            </div>
          )}
          {showLog && (
            <div className="timeline">
              {p.steps.map((s) => (
                <div key={s.step} className={`step ${/failed|stale/i.test(s.result) ? "failed" : ""}`}>
                  <div className="step-top"><span>{label(s.action)}</span><span className="step-time">Step {s.step}</span></div>
                  <div className="step-detail">{s.action.reason || s.result}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <div className="spacer" />

      <div className="chips">
        {CHIPS.map((c) => <button key={c} className="chip glass" onClick={() => setGoal(c)}>{c}</button>)}
        <button className="chip glass live" onClick={() => DeviceControl.openLiveScan().catch(() => {})}>Live scan</button>
      </div>

      <div className="composer glass">
        <textarea rows={1} value={goal} onChange={(e) => setGoal(e.target.value)} placeholder="Ask JARVIS…" aria-label="Command for JARVIS" />
        <button className={`composer-btn mic ${mic}`} onClick={toggleMic} aria-label={mic === "listening" ? "Stop listening" : "Speak your request"}>
          {mic === "listening" ? <StopIcon size={18} /> : <MicIcon size={21} />}
        </button>
        {running
          ? <button className="composer-btn send" style={{ background: "var(--bad)", color: "#fff" }} onClick={p.stop} aria-label="Stop"><StopIcon size={16} /></button>
          : <button className="composer-btn send" onClick={() => { p.run(goal); setShowLog(true); }} disabled={!goal.trim() || !ready} aria-label="Run"><ArrowUp /></button>}
      </div>
      <div className="hint">{hint}</div>
    </div>
  );
}
