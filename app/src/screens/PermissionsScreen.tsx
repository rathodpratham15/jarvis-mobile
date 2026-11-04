import { useCallback, useEffect, useState } from "react";
import { ChevronLeftIcon, ShieldCheckIcon } from "../components/SvgIcons";
import { DeviceControl, isNativeAndroid, PermissionState } from "../deviceControl";

const ITEMS: { id: keyof PermissionState; name: string; desc: string }[] = [
  { id: "accessibility", name: "Accessibility service", desc: "Lets JARVIS read the screen and tap, type and scroll in other apps." },
  { id: "microphone", name: "Microphone", desc: "For “Hey Jarvis” and for speaking your requests." },
  { id: "contacts", name: "Contacts", desc: "Resolves names like “Mom” on this phone. Contacts are never uploaded." },
  { id: "sms", name: "SMS", desc: "Sends text messages you ask for." },
  { id: "calendar", name: "Calendar", desc: "Adds events you ask for." },
  { id: "overlay", name: "Display over other apps", desc: "Shows the assistant panel and the “working” pill." },
];

export function PermissionsScreen({ onBack }: { onBack: () => void }) {
  const [st, setSt] = useState<PermissionState | null>(null);
  const refresh = useCallback(async () => {
    if (!isNativeAndroid()) return;
    try { setSt(await DeviceControl.permissionStatus()); } catch { /* ignore */ }
  }, []);
  useEffect(() => { refresh(); const t = setInterval(refresh, 1500); return () => clearInterval(t); }, [refresh]);

  const todo = st ? ITEMS.filter((i) => !st[i.id]).length : 0;
  const act = async (id: keyof PermissionState, granted: boolean) => {
    try {
      if (granted) await (id === "accessibility" ? DeviceControl.openSettings() : DeviceControl.openAppSettings());
      else await DeviceControl.requestPermission({ id });
    } catch { /* user cancelled */ }
    refresh();
  };

  return (
    <div className="permissions-screen">
      <div className="screen-top-bar">
        <button className="screen-back-btn touch-target" onClick={onBack} aria-label="Go back"><ChevronLeftIcon size={20} /></button>
        <h1 className="screen-title">Permissions</h1>
        <div style={{ width: 48 }} />
      </div>
      <div className="status-card active" style={{ padding: 14 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <ShieldCheckIcon size={22} color="var(--accent-cyan)" />
          <div>
            <div style={{ fontSize: 13, fontWeight: 600 }}>{todo === 0 ? "All permissions granted" : `${todo} permission${todo > 1 ? "s" : ""} needed`}</div>
            <div style={{ fontSize: 11, color: "var(--text-muted)" }}>Each one is used only for the task you ask for.</div>
          </div>
        </div>
      </div>
      <div className="permissions-list">
        {ITEMS.map((p) => {
          const granted = !!st?.[p.id];
          return (
            <div key={p.id} className={`permission-row ${granted ? "granted" : "todo"}`}>
              <div className="permission-info">
                <div className="permission-name-status">
                  <span className="permission-name">{p.name}</span>
                  <span className={`permission-badge ${granted ? "granted" : "todo"}`}>{granted ? "Granted" : "Required"}</span>
                </div>
                <p className="permission-desc">{p.desc}</p>
              </div>
              <button className={`permission-btn touch-target ${granted ? "revoke" : "grant"}`} onClick={() => act(p.id, granted)}>
                {granted ? "Manage" : "Grant"}
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
