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

/** observe → backend decides → act, until done/fail. Risky actions go through `confirm`. */
export function usePhoneAgent(settings: Settings, confirm: (prompt: string) => Promise<boolean>) {
  const [status, setStatus] = useState<Status>("idle");
  const [steps, setSteps] = useState<Step[]>([]);
  const [message, setMessage] = useState("");
  const stopRef = useRef(false);
  const stop = useCallback(() => { stopRef.current = true; }, []);

  const run = useCallback(async (goal: string) => {
    stopRef.current = false;
    setSteps([]); setMessage(""); setStatus("running");
    const history: string[] = [];
    // Leave our own UI first so the agent observes the target app, not this screen.
    await DeviceControl.act({ action: "home" });
    await sleep(900);
    try {
      for (let step = 1; step <= MAX_STEPS; step++) {
        if (stopRef.current) { setStatus("stopped"); setMessage("Stopped."); return; }
        const observation = await DeviceControl.observe({ screenshot: true });
        const res = await fetch(`${settings.baseUrl.replace(/\/$/, "")}/api/phone/step`, {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: `Bearer ${settings.token}` },
          body: JSON.stringify({ goal, step, history, observation }),
        });
        const data = await res.json().catch(() => ({}));
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
        const { result } = await DeviceControl.act(action);
        history.push(`${step}. ${describe(action)} -> ${result}`);
        setSteps((s) => [...s, { step, action, result }]);
        await sleep(SETTLE_MS);
      }
      setMessage("Reached the step limit."); setStatus("failed");
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e)); setStatus("failed");
    }
  }, [settings, confirm]);

  return { status, steps, message, run, stop };
}
