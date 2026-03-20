<script setup lang="ts">
import { ref, onMounted } from "vue";
import TitleBar from "./components/TitleBar.vue";
import CodeList from "./components/CodeList.vue";
import PairPanel from "./components/PairPanel.vue";
import SettingsPanel from "./components/SettingsPanel.vue";
import { useConfig } from "./composables/useConfig";
import { useWebSocket } from "./composables/useWebSocket";

type Tab = "codes" | "pair" | "settings";

const { config, loadConfig } = useConfig();
const { connected, connect } = useWebSocket();

const activeTab = ref<Tab>("codes");

onMounted(async () => {
  await loadConfig();
  if (config.value.room_id && config.value.desktop_token) {
    connect();
  }
});
</script>

<template>
  <div class="app-shell">
    <TitleBar :connected="connected" />

    <nav class="tab-bar">
      <button
        v-for="tab in (['codes', 'pair', 'settings'] as Tab[])"
        :key="tab"
        :class="['tab-btn', { active: activeTab === tab }]"
        @click="activeTab = tab"
      >
        <v-icon size="16" class="tab-icon">
          {{ tab === 'codes' ? 'mdi-message-text' : tab === 'pair' ? 'mdi-qrcode' : 'mdi-cog-outline' }}
        </v-icon>
        {{ tab === 'codes' ? '验证码' : tab === 'pair' ? '配对' : '设置' }}
      </button>
    </nav>

    <main class="content-area">
      <CodeList v-if="activeTab === 'codes'" />
      <PairPanel v-else-if="activeTab === 'pair'" />
      <SettingsPanel v-else />
    </main>
  </div>
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html, body {
  overflow: hidden;
  background: #f5f5f7;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  color: #1d1d1f;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  user-select: none;
  -webkit-user-select: none;
}

.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #f5f5f7;
}

.tab-bar {
  display: flex;
  background: #ffffff;
  border-bottom: 1px solid #e5e5e7;
  padding: 0 8px;
}

.tab-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 10px 0;
  border: none;
  background: none;
  font-size: 13px;
  font-weight: 500;
  color: #86868b;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s ease;
  font-family: inherit;
}

.tab-btn:hover {
  color: #007aff;
}

.tab-btn.active {
  color: #007aff;
  font-weight: 600;
  border-bottom-color: #007aff;
}

.tab-icon {
  opacity: 0.7;
}

.tab-btn.active .tab-icon {
  opacity: 1;
}

.content-area {
  flex: 1;
  overflow-y: auto;
}

/* Override Vuetify's heavy defaults */
.v-application {
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", "Segoe UI", Roboto, Helvetica, Arial, sans-serif !important;
}

/* Smooth scrollbar */
.content-area::-webkit-scrollbar {
  width: 6px;
}

.content-area::-webkit-scrollbar-track {
  background: transparent;
}

.content-area::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.15);
  border-radius: 3px;
}

.content-area::-webkit-scrollbar-thumb:hover {
  background: rgba(0, 0, 0, 0.25);
}
</style>
