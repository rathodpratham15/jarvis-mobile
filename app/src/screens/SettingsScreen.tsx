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
    <div className="screen">
      <div className="screen-bar">
        <button className="icon-btn glass" onClick={onBack} aria-label="Go back"><ChevronLeftIcon size={20} /></button>
      </div>
      <h1 className="screen-title">Settings</h1>

      <div>
        <div className="group-title">Connection</div>
        <div className="group glass">
          <div className="field">
            <label htmlFor="backend-url">Backend URL</label>
            <input id="backend-url" inputMode="url" autoCapitalize="off" autoCorrect="off"
              value={draft.baseUrl} onChange={(e) => edit({ baseUrl: e.target.value })} placeholder="https://your-backend.onrender.com" />
          </div>
          <div className="field">
            <label htmlFor="backup-url">Backup URL (optional)</label>
            <input id="backup-url" inputMode="url" autoCapitalize="off" autoCorrect="off"
              value={draft.fallbackUrl ?? ""} onChange={(e) => edit({ fallbackUrl: e.target.value })} placeholder="http://localhost:5050" />
          </div>
          <div className="field">
            <label htmlFor="device-token">Device token</label>
            <div className="row">
              <input id="device-token" className="mono" type={showToken ? "text" : "password"} autoCapitalize="off" autoCorrect="off"
                value={draft.token} onChange={(e) => edit({ token: e.target.value })} />
              <button type="button" className="icon-btn" style={{ width: 36, height: 36, color: "var(--ink-3)" }} onClick={() => setShowToken(!showToken)}
                aria-label={showToken ? "Hide token" : "Show token"}>{showToken ? <EyeOffIcon size={18} /> : <EyeIcon size={18} />}</button>
            </div>
          </div>
        </div>
      </div>

      <button className="btn btn-primary btn-block" disabled={!dirty && !saved}
        onClick={() => { const c = clean(draft); setDraft(c); onSave(c); setSaved(true); }}>
        {saved ? <><CheckIcon size={18} /><span>Saved</span></> : <span>Save changes</span>}
      </button>
      <button className="btn btn-glass btn-block" onClick={runTest} disabled={testing} style={{ height: 50, fontSize: 15 }}>
        {testing ? <span>Connecting… (a sleeping server can take ~30 s)</span> : <><RefreshIcon size={17} /><span>Test connection</span></>}
      </button>
      {test && (
        <div className={`test-result ${test.ok ? "success" : "error"}`}>
          {test.ok ? <CheckIcon size={18} color="var(--ok)" /> : <AlertTriangleIcon size={18} color="var(--bad)" />}
          <span>{test.msg}</span>
        </div>
      )}

      <div className="callout glass">
        <ShieldCheckIcon size={22} style={{ flexShrink: 0, marginTop: 1 }} />
        <p><strong>Privacy.</strong> Screenshots are sent only when you point at the screen. Contacts never leave your phone.</p>
      </div>

      <div>
        <div className="group-title">More</div>
        <div className="group glass">
          <button className="nav-row" onClick={() => onNavigate("permissions")}><span>Permissions</span><span className="chev"><ChevronLeftIcon size={18} /></span></button>
          <button className="nav-row" onClick={() => onNavigate("memory")}><span>Memories</span><span className="chev"><ChevronLeftIcon size={18} /></span></button>
          <button className="nav-row" onClick={() => onNavigate("about")}><span>About</span><span className="chev"><ChevronLeftIcon size={18} /></span></button>
        </div>
      </div>
    </div>
  );
}
