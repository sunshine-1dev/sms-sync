import { ref } from "vue";
import { LazyStore } from "@tauri-apps/plugin-store";
import type { RoomConfig } from "../types";

// 用户需自行部署 Worker 后填入地址，或在设置中修改
const DEFAULT_SERVER = "https://sms-sync-relay.<your-account>.workers.dev";

const config = ref<RoomConfig>({
  server_base: DEFAULT_SERVER,
  room_id: "",
  desktop_token: "",
  pair_code: "",
});

const store = new LazyStore("config.json");

export function useConfig() {
  async function loadConfig() {
    config.value = {
      server_base:
        ((await store.get<string>("server_base")) as string) || DEFAULT_SERVER,
      room_id: ((await store.get<string>("room_id")) as string) || "",
      desktop_token: ((await store.get<string>("desktop_token")) as string) || "",
      pair_code: ((await store.get<string>("pair_code")) as string) || "",
    };
  }

  async function saveConfig(partial: Partial<RoomConfig>) {
    Object.assign(config.value, partial);
    for (const [key, value] of Object.entries(partial)) {
      await store.set(key, value);
    }
    await store.save();
  }

  async function clearRoom() {
    await saveConfig({ room_id: "", desktop_token: "", pair_code: "" });
  }

  return { config, loadConfig, saveConfig, clearRoom, DEFAULT_SERVER };
}
