import { useCallback, useRef, useState } from "react";
import { DeviceControl, PhoneAction } from "./deviceControl";

export interface Settings { baseUrl: string; token: string; fallbackUrl?: string }
export interface Step { step: number; action: PhoneAction; result: string }
export type Status = "idle" | "running" | "done" | "failed" | "stopped";

const MAX_STEPS = 25;
const SETTLE_MS = 700;
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
const describe = (a: PhoneAction) =>
  [a.action, a.node !== undefined && `node=${a.node}`, a.text && `"${a.text}"`, a.name, a.direction]
    .filter(Boolean).join(" ");

/** "open the clock app" -> "clock". Plain open requests need no model and no screen reading. */
export function openOnlyTarget(goal: string): string | null {
  const m = goal.trim().replace(/[.!? ]+$/, "").match(/^(?:please )?open (?:the )?([a-z0-9][a-z0-9 .&'+-]{0,40}?)(?: app| application)?$/i);
  if (!m || /\b(and|then|to|in|on|search|for|with|from|scroll|play|tap|click)\b/i.test(m[1])) return null;
  return m[1].trim();
}

/** observe → backend decides → act, until done/fail. Risky actions go through `confirm`. */
export function usePhoneAgent(settings: Settings, confirm: (prompt: string) => Promise<boolean>) {
  const [status, setStatus] = useState<Status>("idle");
  const [steps, setSteps] = useState<Step[]>([]);
  const [message, setMessage] = useState("");
  const stopRef = useRef(false);
  const abortRef = useRef<AbortController | null>(null);
  // Stop works immediately: it cancels the in-flight model request and the UI flips to "stopped" at once.
  const stop = useCallback(() => {
    stopRef.current = true;
    abortRef.current?.abort();
    setMessage("Stopped."); setStatus("stopped");
  }, []);

  const run = useCallback(async (goal: string) => {
    stopRef.current = false;
    setSteps([]); setMessage(""); setStatus("running");
    const history: string[] = [];
    const quick = openOnlyTarget(goal);
    if (quick) {                                   // instant path: open it now, do not press Home first
      try {
        const { result } = await DeviceControl.act({ action: "open_app", name: quick });
        const opened = /^opened/i.test(result);
        setSteps([{ step: 1, action: { action: "open_app", name: quick }, result }]);
        setMessage(opened ? `Opened ${quick}.` : result); setStatus(opened ? "done" : "failed");
        if (opened) return;                        // if the name did not match an app, fall through to the full agent
      } catch (e) { /* fall through to the full agent */ }
      setSteps([]); setMessage(""); setStatus("running");
    }
    // Leave our own UI first so the agent observes the target app, not this screen.
    try {
      await DeviceControl.act({ action: "home" });
      await sleep(900);
      for (let step = 1; step <= MAX_STEPS; step++) {
        if (stopRef.current) { setStatus("stopped"); setMessage("Stopped."); return; }
        const observation = await DeviceControl.observe({ screenshot: true });
        const ctl = new AbortController();
        abortRef.current = ctl;
        const res = await fetch(`${settings.baseUrl.replace(/\/$/, "")}/api/phone/step`, {
          method: "POST", signal: ctl.signal,
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${settings.token}` },
          body: JSON.stringify({ goal, step, history, observation }),
        });
        const data = await res.json().catch(() => ({}));
        if (stopRef.current) return;                       // stopped while the model was thinking: do nothing more
        if (!res.ok) throw new Error(data.error || `backend ${res.status}`);
        const action: PhoneAction = data.action;

        if (action.action === "done" || action.action === "fail") {
          const text = (action.action === "done" ? action.result : action.reason) ?? action.action;
          setSteps((s) => [...s, { step, action, result: text }]);
          setMessage(text); setStatus(action.action === "done" ? "done" : "failed");
          return;
        }
        if (action.needs_confirmation && !(await confirm(action.confirm_prompt ?? "Allow this action?"))) {
          setMessage("Cancelled before a sensitive action."); setStatus("stopped"); return;
        }
        if (stopRef.current) return;
        const { result } = await DeviceControl.act(action);
        history.push(`${step}. ${describe(action)} -> ${result}`);
        setSteps((s) => [...s, { step, action, result }]);
        await sleep(SETTLE_MS);
      }
      setMessage("Reached the step limit."); setStatus("failed");
    } catch (e) {
      if (stopRef.current) return;                       // the abort we caused, not a failure
      setMessage(e instanceof Error ? e.message : String(e)); setStatus("failed");
    }
  }, [settings, confirm]);

  return { status, steps, message, run, stop };
}
