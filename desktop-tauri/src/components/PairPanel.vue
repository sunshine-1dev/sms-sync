<script setup lang="ts">
import { ref, onMounted } from "vue";
import QRCode from "qrcode";
import { useConfig } from "../composables/useConfig";
import { useWebSocket } from "../composables/useWebSocket";
import type { CreateRoomResponse } from "../types";

const { config, saveConfig } = useConfig();
const { connect } = useWebSocket();

const pairCode = ref("");
const qrDataUrl = ref("");
const loading = ref(false);
const error = ref("");

async function createRoom() {
  loading.value = true;
  error.value = "";
  try {
    const resp = await fetch(`${config.value.server_base}/api/create-room`, {
      method: "POST",
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const data: CreateRoomResponse = await resp.json();

    await saveConfig({
      room_id: data.roomId,
      desktop_token: data.desktopToken,
      pair_code: data.pairCode,
    });

    pairCode.value = data.pairCode;
    await generateQR();
    connect();
  } catch (e: any) {
    error.value = e.message || "Failed to create room";
  } finally {
    loading.value = false;
  }
}

async function generateQR() {
  const qrData = JSON.stringify({
    server: config.value.server_base,
    pairCode: pairCode.value,
  });
  qrDataUrl.value = await QRCode.toDataURL(qrData, {
    width: 200,
    margin: 2,
    color: { dark: "#1d1d1f", light: "#ffffff" },
  });
}

onMounted(async () => {
  if (config.value.pair_code) {
    pairCode.value = config.value.pair_code;
    await generateQR();
  } else {
    await createRoom();
  }
});
</script>

<template>
  <div class="pair-panel">
    <!-- Error -->
    <div v-if="error" class="error-banner" @click="error = ''">
      <span>{{ error }}</span>
      <span class="dismiss">关闭</span>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="loading-state">
      <div class="spinner"></div>
      <p>正在创建房间...</p>
    </div>

    <!-- Content -->
    <template v-else-if="pairCode">
      <div class="qr-card">
        <img v-if="qrDataUrl" :src="qrDataUrl" alt="QR Code" class="qr-img" />
      </div>

      <div class="pair-label">配对码</div>
      <div class="pair-digits">
        <span v-for="(d, i) in pairCode.split('')" :key="i" class="digit">{{ d }}</span>
      </div>

      <button class="regen-btn" @click="createRoom" :disabled="loading">
        <v-icon size="14">mdi-refresh</v-icon>
        重新生成
      </button>

      <div class="instructions">
        <div class="step" v-for="(text, i) in [
          '在 Android 手机上打开 SMS Sync',
          '扫描二维码或输入配对码',
          '验证码将自动同步到电脑'
        ]" :key="i">
          <span class="step-num">{{ i + 1 }}</span>
          <span>{{ text }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.pair-panel {
  padding: 24px 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.error-banner {
  width: 100%;
  padding: 10px 14px;
  background: #fff2f0;
  border: 1px solid #ffd6d1;
  border-radius: 10px;
  font-size: 13px;
  color: #ff3b30;
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
}

.dismiss {
  font-weight: 500;
  opacity: 0.7;
}

.loading-state {
  padding: 48px 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: #86868b;
  font-size: 14px;
}

.spinner {
  width: 28px;
  height: 28px;
  border: 2.5px solid #e5e5e7;
  border-top-color: #007aff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.qr-card {
  background: #ffffff;
  padding: 16px;
  border-radius: 16px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06), 0 0 0 1px rgba(0, 0, 0, 0.04);
  margin-bottom: 20px;
}

.qr-img {
  width: 200px;
  height: 200px;
  display: block;
  border-radius: 4px;
}

.pair-label {
  font-size: 12px;
  font-weight: 500;
  color: #86868b;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 8px;
}

.pair-digits {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.digit {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 48px;
  background: #ffffff;
  border: 1.5px solid #e5e5e7;
  border-radius: 10px;
  font-size: 22px;
  font-weight: 700;
  color: #1d1d1f;
  font-variant-numeric: tabular-nums;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.regen-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: 1px solid #e5e5e7;
  border-radius: 20px;
  background: #ffffff;
  color: #007aff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
  font-family: inherit;
}

.regen-btn:hover {
  background: #f5f5f7;
  border-color: #007aff;
}

.regen-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.instructions {
  margin-top: 28px;
  width: 100%;
  max-width: 300px;
}

.step {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 6px 0;
  font-size: 13px;
  color: #6e6e73;
  line-height: 1.5;
}

.step-num {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f0f2;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 600;
  color: #86868b;
}
</style>
