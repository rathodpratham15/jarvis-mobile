import { useState } from "react";
import { ChevronLeftIcon, EyeIcon, EyeOffIcon, CheckIcon, AlertTriangleIcon, RefreshIcon, ShieldCheckIcon } from "../components/SvgIcons";
import { Settings } from "../usePhoneAgent";
import { Screen } from "../App";

/** Trim whitespace and trailing slashes so a pasted URL always works. */
export const clean = (v: Settings): Settings => ({
  ...v,
  baseUrl: v.baseUrl.trim().replace(/\/+$/, ""),
  fallbackUrl: (v.fallbackUrl ?? "").trim().replace(/\/+$/, ""),
  token: v.token.trim(),
});

interface Props { settings: Settings; onSave: (s: Settings) => void; onBack: () => void; onNavigate: (s: Screen) => void }

export function SettingsScreen({ settings, onSave, onBack, onNavigate }: Props) {
  const [draft, setDraft] = useState<Settings>(settings);
  const [showToken, setShowToken] = useState(false);
  const [saved, setSaved] = useState(false);
  const [testing, setTesting] = useState(false);
  const [test, setTest] = useState<{ ok: boolean; msg: string } | null>(null);
  const dirty = JSON.stringify(clean(draft)) !== JSON.stringify(settings);
  const edit = (patch: Partial<Settings>) => { setDraft({ ...draft, ...patch }); setSaved(false); };

  const runTest = async () => {
    setTesting(true); setTest(null);
    const t0 = performance.now();
    try {
      const r = await fetch(`${clean(draft).baseUrl}/api/health`);
      const j = await r.json();
      const ms = Math.round(performance.now() - t0);
      setTest(r.ok && j.status === "ok"
        ? { ok: true, msg: `Connected, agent ${j.agent ? "ready" : "not configured"} (${ms} ms)` }
        : { ok: false, msg: `Server answered ${r.status}` });
    } catch { setTest({ ok: false, msg: "Can't reach that URL. Check it and your connection." }); }
    setTesting(false);
  };

  return (
    <div className="settings-screen">
      <div className="screen-top-bar">
        <button className="screen-back-btn touch-target" onClick={onBack} aria-label="Go back"><ChevronLeftIcon size={20} /></button>
        <h1 className="screen-title">Settings</h1>
        <div style={{ width: 48 }} />
      </div>

      <div className="form-group">
        <label className="form-label" htmlFor="backend-url">Backend URL</label>
        <input id="backend-url" className="form-input" inputMode="url" autoCapitalize="off" autoCorrect="off"
          value={draft.baseUrl} onChange={(e) => edit({ baseUrl: e.target.value })} placeholder="https://your-backend.onrender.com" />
      </div>
      <div className="form-group">
        <label className="form-label" htmlFor="backup-url">Backup URL <span style={{ color: "var(--text-subtle)", fontWeight: 400 }}>(optional)</span></label>
        <input id="backup-url" className="form-input" inputMode="url" autoCapitalize="off" autoCorrect="off"
          value={draft.fallbackUrl ?? ""} onChange={(e) => edit({ fallbackUrl: e.target.value })} placeholder="http://localhost:5050" />
      </div>
      <div className="form-group">
        <label className="form-label" htmlFor="device-token">Device token</label>
        <div className="form-input-wrap">
          <input id="device-token" className="form-input" type={showToken ? "text" : "password"} autoCapitalize="off" autoCorrect="off"
            value={draft.token} onChange={(e) => edit({ token: e.target.value })}
            style={{ paddingRight: 48, fontFamily: "var(--font-mono)" }} />
          <button type="button" className="form-input-icon-btn touch-target" onClick={() => setShowToken(!showToken)}
            aria-label={showToken ? "Hide token" : "Show token"}>{showToken ? <EyeOffIcon size={18} /> : <EyeIcon size={18} />}</button>
        </div>
      </div>

      <button className="run-primary-btn touch-target" disabled={!dirty && !saved}
        onClick={() => { const c = clean(draft); setDraft(c); onSave(c); setSaved(true); }}>
        {saved ? <><CheckIcon size={16} /><span>Saved</span></> : <span>Save changes</span>}
      </button>

      <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 6 }}>
        <button className="touch-target" onClick={runTest} disabled={testing}
          style={{ height: 48, background: "var(--surface)", border: "1px solid var(--border)", borderRadius: "var(--radius-control)",
            color: "var(--accent-cyan)", fontSize: 13, fontWeight: 600, gap: 8 }}>
          {testing ? <span>Connecting… (a sleeping server can take ~30 s)</span> : <><RefreshIcon size={16} /><span>Test connection</span></>}
        </button>
        {test && (
          <div className={`test-conn-result ${test.ok ? "success" : "error"}`}>
            {test.ok ? <CheckIcon size={16} color="var(--success)" /> : <AlertTriangleIcon size={16} color="var(--danger)" />}
            <span>{test.msg}</span>
          </div>
        )}
      </div>

      <div className="privacy-note-card">
        <ShieldCheckIcon size={20} color="var(--accent-cyan)" style={{ flexShrink: 0, marginTop: 2 }} />
        <p className="privacy-note-text"><strong>Privacy:</strong> screenshots are sent only when you point at the screen; contacts never leave your phone.</p>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 6 }}>
        <span style={{ fontSize: 11, color: "var(--text-muted)", textTransform: "uppercase", letterSpacing: "0.06em" }}>More</span>
        <div className="nav-link-grid">
          <button className="nav-link-btn" onClick={() => onNavigate("permissions")}>Permissions</button>
          <button className="nav-link-btn" onClick={() => onNavigate("memory")}>Memories</button>
          <button className="nav-link-btn" onClick={() => onNavigate("about")}>About</button>
        </div>
      </div>
    </div>
  );
}
