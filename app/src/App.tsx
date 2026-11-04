import { useCallback, useEffect, useRef, useState } from "react";
import { DeviceControl, isNativeAndroid } from "./deviceControl";
import { Settings, usePhoneAgent } from "./usePhoneAgent";
import { HomeScreen } from "./screens/HomeScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { PermissionsScreen } from "./screens/PermissionsScreen";
import { MemoryScreen } from "./screens/MemoryScreen";
import { AboutScreen } from "./screens/AboutScreen";

export type Screen = "home" | "settings" | "permissions" | "memory" | "about";

const load = (): Settings => {
  try { return JSON.parse(localStorage.getItem("jarvis.settings") ?? ""); } catch { /* first run */ }
  return {
    baseUrl: import.meta.env.VITE_DEFAULT_BACKEND ?? "http://localhost:5050",
    token: import.meta.env.VITE_DEFAULT_TOKEN ?? "",
    fallbackUrl: import.meta.env.VITE_DEFAULT_FALLBACK ?? "",
  };
};

const flag = (k: string) => { try { return localStorage.getItem(k) === "1"; } catch { return false; } };

export default function App() {
  const [settings, setSettings] = useState<Settings>(load);
  const [screen, setScreen] = useState<Screen>(load().token ? "home" : "settings");
  const [onboarded, setOnboarded] = useState(() => flag("jarvis.onboarded"));
  const [accessibility, setAccessibility] = useState<boolean | null>(null);
  const [wake, setWake] = useState<{ running: boolean; overlay: boolean }>({ running: false, overlay: false });
  const [wakeNote, setWakeNote] = useState("");
  const [pending, setPending] = useState<string | null>(null);
  const resolver = useRef<((ok: boolean) => void) | null>(null);

  const confirm = useCallback((prompt: string) => new Promise<boolean>((resolve) => {
    resolver.current = resolve; setPending(prompt);
  }), []);
  const answer = (ok: boolean) => { resolver.current?.(ok); resolver.current = null; setPending(null); };

  const { status, steps, message, run, stop } = usePhoneAgent(settings, confirm);

  // Share backend settings with the native assistant (the voice panel runs without the web UI).
  useEffect(() => {
    if (isNativeAndroid()) DeviceControl.saveConfig(settings).catch(() => { /* ignore */ });
  }, [settings]);

  useEffect(() => {
    const check = async () => {
      if (!isNativeAndroid()) return setAccessibility(false);
      try { setAccessibility((await DeviceControl.isEnabled()).enabled); } catch { setAccessibility(false); }
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

  const toggleWake = async () => {
    setWakeNote("");
    try {
      if (wake.running) await DeviceControl.disableWakeWord();
      else await DeviceControl.enableWakeWord();
    } catch (e: any) {
      const m = e?.message ?? String(e);
      setWakeNote(m === "overlay" ? "Allow “Display over other apps” for JARVIS, come back, and tap again."
        : m === "mic" ? "Microphone permission is required." : m);
    }
  };

  const finishOnboarding = () => { setOnboarded(true); try { localStorage.setItem("jarvis.onboarded", "1"); } catch { /* ignore */ } };
  const back = () => setScreen("home");

  return (
    <div className="app-viewport">
      {screen === "home" && (
        <HomeScreen accessibility={accessibility} wake={wake} wakeNote={wakeNote} onToggleWake={toggleWake}
          onFixAccessibility={() => DeviceControl.openSettings().catch(() => {})}
          status={status} steps={steps} message={message} run={run} stop={stop}
          onNavigate={setScreen} onboarded={onboarded} onFinishOnboarding={finishOnboarding} />
      )}
      {screen === "settings" && <SettingsScreen settings={settings} onSave={save} onBack={back} onNavigate={setScreen} />}
      {screen === "permissions" && <PermissionsScreen onBack={() => setScreen("settings")} />}
      {screen === "memory" && <MemoryScreen onBack={() => setScreen("settings")} />}
      {screen === "about" && <AboutScreen onBack={() => setScreen("settings")} />}

      {pending && (
        <div className="modal-overlay">
          <div className="modal-dialog">
            <h3 className="modal-title">Allow this action?</h3>
            <p className="modal-desc">{pending}</p>
            <div className="modal-actions">
              <button className="modal-cancel-btn touch-target" onClick={() => answer(false)}>Deny</button>
              <button className="modal-confirm-btn touch-target" onClick={() => answer(true)}>Allow</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
