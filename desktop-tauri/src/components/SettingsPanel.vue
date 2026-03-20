<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useConfig } from "../composables/useConfig";
import { useWebSocket } from "../composables/useWebSocket";

const { config, saveConfig, clearRoom, DEFAULT_SERVER } = useConfig();
const { connected, connect, disconnect } = useWebSocket();

const serverInput = ref("");
const saving = ref(false);

onMounted(() => {
  serverInput.value = config.value.server_base;
});

async function saveAndConnect() {
  saving.value = true;
  try {
    await saveConfig({ server_base: serverInput.value });
    if (config.value.room_id) {
      disconnect();
      connect();
    }
  } finally {
    saving.value = false;
  }
}

async function handleDisconnect() {
  disconnect();
  await clearRoom();
}

function resetServer() {
  serverInput.value = DEFAULT_SERVER;
}
</script>

<template>
  <div class="settings-panel">
    <div class="section">
      <div class="section-header">服务器</div>
      <div class="input-group">
        <input
          v-model="serverInput"
          type="url"
          placeholder="https://..."
          :disabled="connected"
          class="server-input"
        />
        <button
          v-if="!connected && serverInput !== DEFAULT_SERVER"
          class="reset-btn"
          @click="resetServer"
          title="恢复默认"
        >
          <v-icon size="14">mdi-restore</v-icon>
        </button>
      </div>
      <div class="action-row">
        <button v-if="!connected" class="btn btn-primary" @click="saveAndConnect" :disabled="saving">
          {{ saving ? '保存中...' : '保存' }}
        </button>
        <button v-else class="btn btn-danger" @click="handleDisconnect">
          断开连接
        </button>
      </div>
    </div>

    <div class="divider"></div>

    <div class="section">
      <div class="section-header">关于</div>
      <div class="info-list">
        <div class="info-row">
          <span class="info-label">版本</span>
          <span class="info-value">1.0.0</span>
        </div>
        <div class="info-row">
          <span class="info-label">状态</span>
          <span :class="['status-pill', connected ? 'online' : 'offline']">
            {{ connected ? '已连接' : '已断开' }}
          </span>
        </div>
        <div class="info-row" v-if="config.room_id">
          <span class="info-label">房间</span>
          <span class="info-value mono">{{ config.room_id.slice(0, 12) }}...</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-panel {
  padding: 20px;
}

.section {
  margin-bottom: 4px;
}

.section-header {
  font-size: 12px;
  font-weight: 600;
  color: #86868b;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 10px;
}

.input-group {
  position: relative;
}

.server-input {
  width: 100%;
  padding: 10px 14px;
  border: 1.5px solid #e5e5e7;
  border-radius: 10px;
  font-size: 13px;
  font-family: inherit;
  background: #ffffff;
  color: #1d1d1f;
  outline: none;
  transition: border-color 0.2s;
  box-sizing: border-box;
}

.server-input:focus {
  border-color: #007aff;
  box-shadow: 0 0 0 3px rgba(0, 122, 255, 0.1);
}

.server-input:disabled {
  background: #f5f5f7;
  color: #86868b;
  cursor: not-allowed;
}

.reset-btn {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  background: none;
  color: #86868b;
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  display: flex;
  align-items: center;
}

.reset-btn:hover {
  background: #f0f0f2;
  color: #1d1d1f;
}

.action-row {
  margin-top: 12px;
}

.btn {
  width: 100%;
  padding: 10px;
  border: none;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
  font-family: inherit;
}

.btn:active {
  transform: scale(0.98);
}

.btn-primary {
  background: #007aff;
  color: #ffffff;
}

.btn-primary:hover {
  background: #0066d6;
}

.btn-primary:disabled {
  background: #b0d4ff;
  cursor: not-allowed;
}

.btn-danger {
  background: none;
  border: 1.5px solid #ff3b30;
  color: #ff3b30;
}

.btn-danger:hover {
  background: #fff2f0;
}

.divider {
  height: 1px;
  background: #e5e5e7;
  margin: 20px 0;
}

.info-list {
  background: #ffffff;
  border-radius: 12px;
  border: 1px solid #e5e5e7;
  overflow: hidden;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
}

.info-row + .info-row {
  border-top: 1px solid #f0f0f2;
}

.info-label {
  font-size: 14px;
  color: #1d1d1f;
}

.info-value {
  font-size: 14px;
  color: #86868b;
}

.info-value.mono {
  font-family: "SF Mono", SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.status-pill {
  font-size: 12px;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 10px;
}

.status-pill.online {
  background: #e8f5e9;
  color: #2e7d32;
}

.status-pill.offline {
  background: #f0f0f2;
  color: #86868b;
}
</style>
