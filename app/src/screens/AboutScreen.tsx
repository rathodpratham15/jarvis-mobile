import { ArcReactorLogo } from "../components/ArcReactorLogo";
import { FluidOrb } from "../components/FluidOrb";
import { ChevronLeftIcon } from "../components/SvgIcons";

export function AboutScreen({ onBack }: { onBack: () => void }) {
  const meta: [string, string][] = [
    ["Version", "1.0"],
    ["Reasoning and vision", "Meta Muse Spark 1.3"],
    ["Voice in", "Meta Muse Voice Transcribe"],
    ["Icon grounding", "Meta SAM 3.1"],
    ["Guide expressions", "Meta Muse Image"],
  ];
  return (
    <div className="screen">
      <div className="screen-bar">
        <button className="icon-btn glass" onClick={onBack} aria-label="Go back"><ChevronLeftIcon size={20} /></button>
      </div>
      <div className="about-hero">
        <FluidOrb size={200} state="idle" />
        <div className="about-name">JARVIS</div>
        <p className="lead">Delegate a task. It figures out how to do it.</p>
      </div>
      <div className="group glass">
        {meta.map(([k, v]) => <div className="meta-row" key={k}><span>{k}</span><span>{v}</span></div>)}
      </div>
      <div className="quote glass">“Hi, allow me to introduce myself. I am JARVIS, a virtual artificial intelligence, and I'm here to assist you with a variety of tasks as best I can.”</div>
      <div className="footer-note">
        <ArcReactorLogo size={18} glow={false} />
        <div style={{ marginTop: 8 }}>Built for the Meta Global AI Developer Hackathon. Reasoning runs on Meta's servers; actions and contacts stay on your phone. Open source (MIT).</div>
      </div>
    </div>
  );
}
