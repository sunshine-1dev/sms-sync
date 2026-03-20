import { ref } from "vue";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
} from "@tauri-apps/plugin-notification";
import { useConfig } from "./useConfig";
import { useCodeStore } from "./useCodeStore";
import type { CodeMessage } from "../types";

const connected = ref(false);
let ws: WebSocket | null = null;
let pingTimer: ReturnType<typeof setInterval> | null = null;
let reconnectAttempt = 0;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let shouldReconnect = false;

const MAX_RECONNECT = 10;

export function useWebSocket() {
  const { config } = useConfig();
  const { addCode } = useCodeStore();

  function buildWsUrl(): string {
    const base = config.value.server_base
      .replace("https://", "wss://")
      .replace("http://", "ws://");
    const params = new URLSearchParams({
      roomId: config.value.room_id,
      token: config.value.desktop_token,
      role: "desktop",
    });
    return `${base}/ws?${params}`;
  }

  function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    if (!config.value.room_id || !config.value.desktop_token) {
      return;
    }

    shouldReconnect = true;
    reconnectAttempt = 0;
    doConnect();
  }

  function doConnect() {
    try {
      const url = buildWsUrl();
      ws = new WebSocket(url);

      ws.onopen = () => {
        connected.value = true;
        reconnectAttempt = 0;
        startPing();
      };

      ws.onmessage = async (event) => {
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === "code") {
            handleCodeMessage(msg as CodeMessage);
          }
        } catch {
          // ignore non-JSON messages
        }
      };

      ws.onclose = () => {
        connected.value = false;
        stopPing();
        if (shouldReconnect) {
          scheduleReconnect();
        }
      };

      ws.onerror = () => {
        // onclose will fire after this
      };
    } catch {
      connected.value = false;
      if (shouldReconnect) {
        scheduleReconnect();
      }
    }
  }

  async function handleCodeMessage(msg: CodeMessage) {
    addCode(msg);

    // Copy to clipboard
    try {
      await writeText(msg.code);
    } catch {
      // clipboard may fail
    }

    // Send notification
    try {
      let permissionGranted = await isPermissionGranted();
      if (!permissionGranted) {
        const permission = await requestPermission();
        permissionGranted = permission === "granted";
      }
      if (permissionGranted) {
        const source =
          msg.sender || msg.app_name || msg.source || "Unknown";
        sendNotification({
          title: `Verification Code: ${msg.code}`,
          body: `From: ${source} (copied to clipboard)`,
        });
      }
    } catch {
      // notification may fail
    }
  }

  function startPing() {
    stopPing();
    pingTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: "ping" }));
      }
    }, 30000);
  }

  function stopPing() {
    if (pingTimer) {
      clearInterval(pingTimer);
      pingTimer = null;
    }
  }

  function scheduleReconnect() {
    if (reconnectAttempt >= MAX_RECONNECT) {
      shouldReconnect = false;
      return;
    }
    const delay = (reconnectAttempt + 1) * 3000; // 3s, 6s, 9s, ... 30s
    reconnectAttempt++;
    reconnectTimer = setTimeout(() => {
      doConnect();
    }, delay);
  }

  function disconnect() {
    shouldReconnect = false;
    if (reconnectTimer) {
      clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    stopPing();
    if (ws) {
      ws.close();
      ws = null;
    }
    connected.value = false;
  }

  return { connected, connect, disconnect };
}
