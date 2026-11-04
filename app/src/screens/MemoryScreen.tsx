import { useCallback, useEffect, useState } from "react";
import { ChevronLeftIcon, TrashIcon, PlusIcon, AlertTriangleIcon } from "../components/SvgIcons";
import { DeviceControl, isNativeAndroid } from "../deviceControl";

export function MemoryScreen({ onBack }: { onBack: () => void }) {
  const [items, setItems] = useState<string[]>([]);
  const [k, setK] = useState("");
  const [v, setV] = useState("");
  const [confirmWipe, setConfirmWipe] = useState(false);

  const refresh = useCallback(async () => {
    if (!isNativeAndroid()) return;
    try { setItems((await DeviceControl.getMemories()).items); } catch { /* ignore */ }
  }, []);
  useEffect(() => { refresh(); }, [refresh]);

  const add = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!k.trim() || !v.trim()) return;
    setItems((await DeviceControl.addMemory({ fact: `${k.trim()}: ${v.trim()}` })).items);
    setK(""); setV("");
  };
  const split = (f: string) => { const i = f.indexOf(":"); return i > 0 ? [f.slice(0, i), f.slice(i + 1).trim()] : ["", f]; };

  return (
    <div className="memory-screen">
      <div className="screen-top-bar">
        <button className="screen-back-btn touch-target" onClick={onBack} aria-label="Go back"><ChevronLeftIcon size={20} /></button>
        <h1 className="screen-title">Memory</h1>
        <div style={{ width: 48 }} />
      </div>
      <p style={{ fontSize: 12, color: "var(--text-muted)", lineHeight: 1.5 }}>
        What JARVIS remembers, stored only on this phone. Say “remember Mom uses WhatsApp” or add one here. Delete anything at any time.
      </p>

      <form onSubmit={add} style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        <div style={{ display: "flex", gap: 8 }}>
          <input className="form-input" style={{ flex: 1, height: 42, fontSize: 12 }} placeholder="About (e.g. Mom)" value={k} onChange={(e) => setK(e.target.value)} />
          <input className="form-input" style={{ flex: 1.4, height: 42, fontSize: 12 }} placeholder="Rule (e.g. always WhatsApp)" value={v} onChange={(e) => setV(e.target.value)} />
        </div>
        <button type="submit" className="touch-target" disabled={!k.trim() || !v.trim()}
          style={{ height: 42, background: "var(--surface)", border: "1px solid var(--border)", borderRadius: "var(--radius-control)",
            color: "var(--accent-cyan)", fontSize: 12, fontWeight: 600, gap: 6 }}>
          <PlusIcon size={16} /><span>Add memory</span>
        </button>
      </form>

      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {items.length === 0 ? (
          <div style={{ padding: 24, textAlign: "center", color: "var(--text-muted)", fontSize: 13, background: "var(--surface)", borderRadius: "var(--radius-control)" }}>
            Nothing remembered yet. JARVIS will ask when it needs a detail.
          </div>
        ) : items.map((f) => {
          const [key, val] = split(f);
          return (
            <div key={f} className="memory-item-card">
              <div className="memory-text">{key && <strong style={{ color: "var(--accent-cyan)" }}>{key}: </strong>}{val}</div>
              <button className="memory-del-btn touch-target" aria-label={`Delete memory ${f}`}
                onClick={async () => setItems((await DeviceControl.removeMemory({ fact: f })).items)}><TrashIcon size={16} /></button>
            </div>
          );
        })}
      </div>

      {items.length > 0 && (
        <button className="forget-all-btn touch-target" onClick={() => setConfirmWipe(true)}>
          <AlertTriangleIcon size={16} /><span>Forget everything</span>
        </button>
      )}

      {confirmWipe && (
        <div className="modal-overlay">
          <div className="modal-dialog">
            <h3 className="modal-title">Erase all memories?</h3>
            <p className="modal-desc">This deletes all {items.length} saved preferences from this phone. JARVIS will ask again next time.</p>
            <div className="modal-actions">
              <button className="modal-cancel-btn touch-target" onClick={() => setConfirmWipe(false)}>Cancel</button>
              <button className="modal-confirm-btn touch-target"
                onClick={async () => { setItems((await DeviceControl.clearMemories()).items); setConfirmWipe(false); }}>Erase all</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
