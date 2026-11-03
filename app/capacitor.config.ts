import type { CapacitorConfig } from "@capacitor/cli";
const config: CapacitorConfig = {
  appId: "com.jarvis.assistant",
  appName: "JARVIS",
  webDir: "dist",
  android: { allowMixedContent: true },
};
export default config;
