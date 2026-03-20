<script setup lang="ts">
import { getCurrentWindow } from "@tauri-apps/api/window";

defineProps<{
  connected: boolean;
}>();

const appWindow = getCurrentWindow();

function minimize() {
  appWindow.minimize();
}

function close() {
  appWindow.hide();
}
</script>

<template>
  <div class="titlebar" data-tauri-drag-region>
    <div class="traffic-lights">
      <button class="tl-btn tl-close" @click="close" title="Close to tray">
        <svg width="8" height="8" viewBox="0 0 8 8">
          <path d="M1 1L7 7M7 1L1 7" stroke="currentColor" stroke-width="1.2" stroke-linecap="round"/>
        </svg>
      </button>
      <button class="tl-btn tl-minimize" @click="minimize" title="Minimize">
        <svg width="8" height="2" viewBox="0 0 8 2">
          <rect y="0.5" width="8" height="1" rx="0.5" fill="currentColor"/>
        </svg>
      </button>
      <button class="tl-btn tl-maximize" title="Maximize" disabled>
        <svg width="8" height="8" viewBox="0 0 8 8">
          <path d="M1 6L4 2L7 6" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" stroke-linejoin="round" fill="none"/>
        </svg>
      </button>
    </div>
    <div class="titlebar-center" data-tauri-drag-region>
      <span class="app-title" data-tauri-drag-region>SMS Sync</span>
      <span :class="['status-badge', connected ? 'online' : 'offline']">
        {{ connected ? 'Connected' : 'Disconnected' }}
      </span>
    </div>
    <div class="titlebar-right" data-tauri-drag-region></div>
  </div>
</template>

<style scoped>
.titlebar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 16px;
  background: #ffffff;
  border-bottom: 1px solid #e5e5e7;
}

.traffic-lights {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tl-btn {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  color: transparent;
  transition: color 0.1s;
}

.tl-close {
  background: #FF5F57;
}

.tl-minimize {
  background: #FEBC2E;
}

.tl-maximize {
  background: #28C840;
}

.tl-maximize:disabled {
  background: #28C840;
  opacity: 0.5;
  cursor: default;
}

.traffic-lights:hover .tl-btn:not(:disabled) {
  color: rgba(0, 0, 0, 0.5);
}

.tl-btn:active:not(:disabled) {
  filter: brightness(0.85);
}

.titlebar-center {
  display: flex;
  align-items: center;
  gap: 10px;
}

.titlebar-right {
  width: 52px;
}

.app-title {
  font-size: 15px;
  font-weight: 700;
  color: #1d1d1f;
  letter-spacing: -0.2px;
}

.status-badge {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 8px;
  border-radius: 10px;
  transition: all 0.3s ease;
}

.status-badge.online {
  background: #e8f5e9;
  color: #2e7d32;
}

.status-badge.offline {
  background: #f0f0f2;
  color: #86868b;
}
</style>
