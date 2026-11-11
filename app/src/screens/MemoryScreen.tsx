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
    <div className="screen">
      <div className="screen-bar">
        <button className="icon-btn glass" onClick={onBack} aria-label="Go back"><ChevronLeftIcon size={20} /></button>
      </div>
      <h1 className="screen-title">Memory</h1>
      <p className="lead">What JARVIS remembers, stored only on this phone. Say “remember Mom uses WhatsApp” or add one here. Delete anything at any time.</p>

      <form onSubmit={add} className="mem-form">
        <div className="group glass">
          <div className="field"><label htmlFor="mem-k">About</label><input id="mem-k" placeholder="Mom" value={k} onChange={(e) => setK(e.target.value)} /></div>
          <div className="field"><label htmlFor="mem-v">Rule</label><input id="mem-v" placeholder="Always use WhatsApp" value={v} onChange={(e) => setV(e.target.value)} /></div>
        </div>
        <button type="submit" className="btn btn-glass btn-block" disabled={!k.trim() || !v.trim()} style={{ height: 50, fontSize: 15 }}>
          <PlusIcon size={18} /><span>Add memory</span>
        </button>
      </form>

      <div className="group glass">
        {items.length === 0 ? (
          <div className="empty">Nothing remembered yet. JARVIS will ask when it needs a detail.</div>
        ) : items.map((f) => {
          const [key, val] = split(f);
          return (
            <div key={f} className="mem-item">
              <div>{key && <strong>{key}: </strong>}{val}</div>
              <button className="mem-del" aria-label={`Delete memory ${f}`}
                onClick={async () => setItems((await DeviceControl.removeMemory({ fact: f })).items)}><TrashIcon size={18} /></button>
            </div>
          );
        })}
      </div>

      {items.length > 0 && (
        <button className="btn btn-danger btn-block" onClick={() => setConfirmWipe(true)} style={{ height: 50, fontSize: 15 }}>
          <AlertTriangleIcon size={17} /><span>Forget everything</span>
        </button>
      )}

      {confirmWipe && (
        <div className="modal-overlay">
          <div className="modal">
            <div className="modal-body">
              <h3 className="modal-title">Erase all memories?</h3>
              <p className="modal-desc">This deletes all {items.length} saved preferences from this phone. JARVIS will ask again next time.</p>
            </div>
            <div className="modal-actions">
              <button onClick={() => setConfirmWipe(false)}>Cancel</button>
              <button className="danger" onClick={async () => { setItems((await DeviceControl.clearMemories()).items); setConfirmWipe(false); }}>Erase all</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
