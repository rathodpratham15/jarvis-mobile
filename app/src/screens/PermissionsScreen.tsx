import { useCallback, useEffect, useState } from "react";
import { ChevronLeftIcon, ShieldCheckIcon } from "../components/SvgIcons";
import { DeviceControl, isNativeAndroid, PermissionState } from "../deviceControl";

const ITEMS: { id: keyof PermissionState; name: string; desc: string }[] = [
  { id: "accessibility", name: "Accessibility service", desc: "Lets JARVIS read the screen and tap, type and scroll in other apps." },
  { id: "microphone", name: "Microphone", desc: "For “Hey Jarvis” and for speaking your requests." },
  { id: "camera", name: "Camera", desc: "For “scan this” and live scan. Photos and frames are sent only when you scan, and are never saved." },
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
    <div className="screen">
      <div className="screen-bar">
        <button className="icon-btn glass" onClick={onBack} aria-label="Go back"><ChevronLeftIcon size={20} /></button>
      </div>
      <h1 className="screen-title">Permissions</h1>
      <div className="callout glass">
        <ShieldCheckIcon size={22} style={{ flexShrink: 0, marginTop: 1 }} />
        <p><strong>{todo === 0 ? "All permissions granted" : `${todo} permission${todo > 1 ? "s" : ""} needed`}.</strong> Each one is used only for the task you ask for.</p>
      </div>
      <div className="group glass">
        {ITEMS.map((p) => {
          const granted = !!st?.[p.id];
          return (
            <div key={p.id} className="perm-row">
              <div className="perm-info">
                <div className="perm-name"><span>{p.name}</span><span className={`badge ${granted ? "granted" : "todo"}`}>{granted ? "Granted" : "Required"}</span></div>
                <p className="perm-desc">{p.desc}</p>
              </div>
              <button className={`perm-btn ${granted ? "manage" : "grant"}`} onClick={() => act(p.id, granted)}>{granted ? "Manage" : "Grant"}</button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
