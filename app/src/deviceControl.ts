import { registerPlugin, Capacitor } from "@capacitor/core";

export interface UiNode {
  id: number; text: string; desc: string; cls: string;
  clickable: boolean; editable: boolean; scrollable: boolean;
  bounds: [number, number, number, number];
}
export interface Observation {
  package: string; width: number; height: number; nodes: UiNode[]; screenshot_b64?: string;
}
export interface PhoneAction {
  action: "tap" | "type" | "scroll" | "back" | "home" | "open_app" | "wait" | "done" | "fail";
  node?: number; x?: number; y?: number; text?: string; direction?: string; name?: string;
  reason?: string; result?: string;
  needs_confirmation?: boolean; confirm_prompt?: string;
}

interface DeviceControlPlugin {
  isEnabled(): Promise<{ enabled: boolean }>;
  openSettings(): Promise<void>;
  wakeWordStatus(): Promise<{ running: boolean; overlay: boolean }>;
  enableWakeWord(): Promise<void>;
  disableWakeWord(): Promise<void>;
  saveConfig(cfg: { baseUrl: string; token: string; fallbackUrl?: string }): Promise<void>;
  listen(): Promise<{ text: string }>;
  getMemories(): Promise<{ items: string[] }>;
  addMemory(o: { fact: string }): Promise<{ items: string[] }>;
  removeMemory(o: { fact: string }): Promise<{ items: string[] }>;
  clearMemories(): Promise<{ items: string[] }>;
  permissionStatus(): Promise<PermissionState>;
  requestPermission(o: { id: string }): Promise<unknown>;
  openAppSettings(): Promise<void>;
  stopListening(): Promise<void>;
  observe(opts?: { screenshot?: boolean }): Promise<Observation>;
  act(action: PhoneAction): Promise<{ result: string }>;
}

export const DeviceControl = registerPlugin<DeviceControlPlugin>("DeviceControl");
export const isNativeAndroid = () => Capacitor.getPlatform() === "android";

export interface PermissionState {
  accessibility: boolean; microphone: boolean; contacts: boolean; sms: boolean; calendar: boolean; overlay: boolean;
}
