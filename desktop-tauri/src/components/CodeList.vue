<script setup lang="ts">
import { ref } from "vue";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import { useCodeStore } from "../composables/useCodeStore";

const { codes, clearCodes } = useCodeStore();
const copiedIndex = ref(-1);

function formatTime(timestamp: number): string {
  if (!timestamp) return "";
  const date = new Date(timestamp * 1000);
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function formatSource(msg: { source: string; sender: string; app_name: string }): string {
  if (msg.source === "sms") return msg.sender || "SMS";
  if (msg.app_name) return friendlyAppName(msg.app_name);
  return "Notification";
}

function friendlyAppName(pkg: string): string {
  const map: Record<string, string> = {
    "com.tencent.mm": "微信",
    "com.tencent.mobileqq": "QQ",
    "com.eg.android.AlipayGphone": "支付宝",
    "com.taobao.taobao": "淘宝",
    "com.jingdong.app.mall": "京东",
    "com.sankuai.meituan": "美团",
    "com.ss.android.ugc.aweme": "抖音",
    "com.ss.android.article.news": "今日头条",
    "com.sina.weibo": "微博",
    "com.autonavi.minimap": "高德地图",
    "com.baidu.BaiduMap": "百度地图",
    "com.sdu.didi.psnger": "滴滴出行",
    "com.pinduoduo.android": "拼多多",
    "com.xunmeng.pinduoduo": "拼多多",
    "com.zhihu.android": "知乎",
    "com.netease.cloudmusic": "网易云音乐",
    "com.tencent.qqmusic": "QQ音乐",
    "com.alibaba.android.rimet": "钉钉",
    "com.tencent.wework": "企业微信",
    "com.lark.messenger": "飞书",
    "tv.danmaku.bili": "哔哩哔哩",
    "com.kuaishou.nebula": "快手",
    "com.xiaomi.market": "小米应用商店",
    "com.google.android.apps.messaging": "Google Messages",
    "com.google.android.gm": "Gmail",
  };
  if (map[pkg]) return map[pkg];
  // Extract readable name from package: com.foo.bar → Foo
  const parts = pkg.split(".");
  if (parts.length >= 2) {
    const name = parts[parts.length - 1];
    return name.charAt(0).toUpperCase() + name.slice(1);
  }
  return pkg;
}

function sourceIcon(source: string): string {
  return source === "sms" ? "mdi-message-text" : "mdi-bell-outline";
}

async function copyCode(code: string, index: number) {
  await writeText(code);
  copiedIndex.value = index;
  setTimeout(() => {
    copiedIndex.value = -1;
  }, 1500);
}
</script>

<template>
  <div class="code-list">
    <div class="list-header">
      <h3>最近验证码</h3>
      <button v-if="codes.length > 0" class="clear-btn" @click="clearCodes">清空</button>
    </div>

    <div v-if="codes.length === 0" class="empty">
      <div class="empty-icon">
        <v-icon size="56" color="#d1d1d6">mdi-message-text-outline</v-icon>
      </div>
      <p class="empty-title">暂无验证码</p>
      <p class="empty-hint">手机收到的验证码会自动显示在这里</p>
    </div>

    <div v-else class="list">
      <div
        v-for="(msg, index) in codes"
        :key="msg.timestamp + msg.code"
        class="code-item"
        @click="copyCode(msg.code, index)"
      >
        <div class="code-left">
          <div class="code-value">{{ msg.code }}</div>
          <div class="code-meta">
            <v-icon size="12" class="source-icon">{{ sourceIcon(msg.source) }}</v-icon>
            <span class="source">{{ formatSource(msg) }}</span>
            <span class="sep">&middot;</span>
            <span class="time">{{ formatTime(msg.timestamp) }}</span>
          </div>
        </div>
        <div :class="['copy-tag', { copied: copiedIndex === index }]">
          {{ copiedIndex === index ? '已复制' : '复制' }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.code-list {
  padding: 16px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding: 0 2px;
}

h3 {
  font-size: 15px;
  font-weight: 600;
  color: #1d1d1f;
}

.clear-btn {
  border: none;
  background: none;
  color: #007aff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  transition: background 0.15s;
  font-family: inherit;
}

.clear-btn:hover {
  background: rgba(0, 122, 255, 0.08);
}

.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
}

.empty-icon {
  margin-bottom: 12px;
  opacity: 0.5;
}

.empty-title {
  font-size: 15px;
  font-weight: 500;
  color: #86868b;
  margin-bottom: 4px;
}

.empty-hint {
  font-size: 13px;
  color: #aeaeb2;
  text-align: center;
  max-width: 220px;
  line-height: 1.4;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  overflow-y: auto;
}

.code-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #ffffff;
  padding: 14px 16px;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.15s ease;
  border: 1px solid #e5e5e7;
}

.code-item:hover {
  border-color: #007aff;
  box-shadow: 0 2px 8px rgba(0, 122, 255, 0.08);
}

.code-item:active {
  transform: scale(0.99);
}

.code-value {
  font-size: 24px;
  font-weight: 700;
  color: #1d1d1f;
  font-variant-numeric: tabular-nums;
  letter-spacing: 2px;
  line-height: 1.2;
}

.code-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
  font-size: 12px;
  color: #86868b;
}

.source {
  font-weight: 500;
}

.sep {
  opacity: 0.4;
}

.copy-tag {
  font-size: 12px;
  font-weight: 500;
  color: #007aff;
  padding: 4px 10px;
  border-radius: 12px;
  background: rgba(0, 122, 255, 0.08);
  white-space: nowrap;
  transition: all 0.2s ease;
}

.copy-tag.copied {
  background: #e8f5e9;
  color: #2e7d32;
}
</style>
