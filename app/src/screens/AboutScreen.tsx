import { ArcReactorLogo } from "../components/ArcReactorLogo";
import { ChevronLeftIcon } from "../components/SvgIcons";

export function AboutScreen({ onBack }: { onBack: () => void }) {
  const meta: [string, string][] = [
    ["Version", "1.0"],
    ["Reasoning & vision", "Meta Muse Spark 1.3"],
    ["Voice in", "Meta Muse Voice Transcribe"],
    ["Icon grounding", "Meta SAM 3.1"],
  ];
  return (
    <div className="about-screen">
      <div className="screen-top-bar">
        <button className="screen-back-btn touch-target" onClick={onBack} aria-label="Go back"><ChevronLeftIcon size={20} /></button>
        <h1 className="screen-title">About</h1>
        <div style={{ width: 48 }} />
      </div>
      <div className="about-hero">
        <ArcReactorLogo size={68} glow spinning state="idle" />
        <div>
          <h2 style={{ fontSize: 20, fontWeight: 700, color: "var(--accent-cyan)", letterSpacing: "0.12em" }}>JARVIS</h2>
          <div style={{ fontSize: 11, color: "var(--text-muted)", marginTop: 2 }}>Just A Rather Very Intelligent System</div>
        </div>
      </div>
      <div className="about-intro-quote">
        <p>“Hi, allow me to introduce myself. I am JARVIS, a virtual artificial intelligence, and I'm here to assist you with a variety of tasks as best I can, 24 hours a day, 7 days a week.”</p>
      </div>
      <div className="about-meta-grid">
        {meta.map(([k, v]) => (
          <div className="about-meta-card" key={k}><span className="about-meta-label">{k}</span><span className="about-meta-val">{v}</span></div>
        ))}
      </div>
      <div style={{ background: "rgba(7,14,24,0.7)", border: "1px solid var(--border)", borderRadius: "var(--radius-control)", padding: 14, textAlign: "center" }}>
        <div style={{ fontSize: 13, fontWeight: 600 }}>“Delegate a task. It figures out how to do it.”</div>
        <div style={{ fontSize: 11, color: "var(--text-muted)", marginTop: 4 }}>
          Built for the Meta Global AI Developer Hackathon. Reasoning runs on Meta's servers; actions and contacts stay on your phone.
        </div>
      </div>
    </div>
  );
}
