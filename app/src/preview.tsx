/** Dev-only: renders one screen with mock data at /preview.html?s=<name>, so the design can be checked in a browser. */
import { createRoot } from "react-dom/client";
import "@fontsource-variable/inter";
import "./styles.css";
import { HomeScreen } from "./screens/HomeScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { PermissionsScreen } from "./screens/PermissionsScreen";
import { MemoryScreen } from "./screens/MemoryScreen";
import { AboutScreen } from "./screens/AboutScreen";

const noop = () => {};
const q = new URLSearchParams(location.search).get("s") ?? "home";
const base = {
  accessibility: true as boolean | null, wake: { running: true, overlay: true }, wakeNote: "", onToggleWake: noop, onFixAccessibility: noop,
  status: "idle" as any, steps: [] as any[], message: "", run: noop, stop: noop, onNavigate: noop, onboarded: true, onFinishOnboarding: noop,
};
const steps = [
  { step: 1, action: { action: "open_app", name: "Settings", reason: "Open Settings to find Battery." }, result: "ok" },
  { step: 2, action: { action: "tap", node: 14, reason: "Battery and device care." }, result: "ok" },
  { step: 3, action: { action: "tap", node: 6, reason: "Turn on power saving." }, result: "ok" },
];

function Screen() {
  switch (q) {
    case "onboard": return <HomeScreen {...base} accessibility={false} wake={{ running: false, overlay: false }} onboarded={false} />;
    case "off": return <HomeScreen {...base} accessibility={false} wake={{ running: false, overlay: false }} />;
    case "done": return <HomeScreen {...base} status="done" steps={steps} message="Battery saver is on." />;
    case "running": return <HomeScreen {...base} status="running" steps={steps.slice(0, 2)} message="Opening Battery settings…" />;
    case "settings": return <SettingsScreen settings={{ baseUrl: "https://jarvis-mobile-backend.onrender.com", token: "••••••••••••", fallbackUrl: "http://localhost:5050" }} onSave={noop} onBack={noop} onNavigate={noop} />;
    case "permissions": return <PermissionsScreen onBack={noop} />;
    case "memory": return <MemoryScreen onBack={noop} />;
    case "about": return <AboutScreen onBack={noop} />;
    case "modal": return (<>
      <HomeScreen {...base} />
      <div className="modal-overlay"><div className="modal"><div className="modal-body"><h3 className="modal-title">Allow this action?</h3>
        <p className="modal-desc">Send “I'm running late” to Mummy on WhatsApp?</p></div>
        <div className="modal-actions"><button>Deny</button><button>Allow</button></div></div></div></>);
    default: return <HomeScreen {...base} />;
  }
}
createRoot(document.getElementById("root")!).render(<div className="app-viewport" style={{ width: 390, margin: 0 }}><Screen /></div>);
