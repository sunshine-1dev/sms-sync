# SMS Sync

手机验证码自动同步到电脑。Android 手机收到短信或通知中的验证码后，自动转发到桌面客户端，复制到剪贴板并弹出系统通知。

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/sunshine-1dev/sms-sync/tree/main/worker)

## 功能特点

- 自动提取短信和应用通知中的验证码
- 通过云端中转实时同步到桌面
- 桌面端自动复制到剪贴板 + 系统通知
- 扫描二维码一键配对
- 系统托盘常驻，关闭窗口最小化到托盘
- 支持 HTTP 回退，确保消息送达
- 消息去重，避免重复推送

## 架构

```
Android 手机                    Cloudflare Worker                桌面客户端
┌──────────────┐              ┌─────────────────┐              ┌──────────────┐
│  短信接收器   │──WebSocket──▶│                 │──WebSocket──▶│  Tauri 2 App │
│  通知监听器   │──HTTP POST──▶│   Durable Object│              │  Vue 3       │
│  前台服务     │              │   (中转房间)     │              │  自动复制     │
└──────────────┘              └─────────────────┘              └──────────────┘
```

**配对流程：** 桌面端创建房间 → 生成二维码（含服务器地址 + 配对码）→ Android 扫码配对 → 双端通过 WebSocket 实时通信

## 项目结构

```
sms-sync/
├── android/          # Android 端 (Kotlin + Jetpack Compose + Material 3)
├── desktop-tauri/    # 桌面端 (Tauri 2 + Rust + Vue 3 + Vuetify)
├── worker/           # 云端中转 (Cloudflare Workers + Durable Objects)
├── proto/            # 消息协议定义 (JSON Schema)
├── LICENSE
└── README.md
```

## 部署指南

### 1. 部署 Cloudflare Worker（云端中转）

**一键部署（推荐）：** 点击下方按钮，自动 Fork 仓库并部署到你的 Cloudflare 账号：

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/sunshine-1dev/sms-sync/tree/main/worker)

**手动部署：**

**前提条件：** [Cloudflare 账号](https://dash.cloudflare.com/sign-up)、[Node.js](https://nodejs.org/) 18+

```bash
cd worker
npm install
npm run deploy
```

部署成功后会得到一个 URL，形如 `https://sms-sync-relay.<your-account>.workers.dev`。

### 2. 构建桌面端

**前提条件：** [Node.js](https://nodejs.org/) 18+、[Rust](https://rustup.rs/)

```bash
cd desktop-tauri
npm install

# 修改默认服务器地址（可选，也可在应用设置中修改）
# 编辑 src/composables/useConfig.ts 中的 DEFAULT_SERVER

# 开发模式
npm run tauri dev

# 构建安装包
npm run tauri build
```

构建产物：
- macOS: `src-tauri/target/release/bundle/dmg/SMS Sync_*.dmg`
- Windows: `src-tauri/target/release/bundle/msi/` 或 `nsis/`
- Linux: `src-tauri/target/release/bundle/deb/` 或 `appimage/`

### 3. 编译 Android 端

**前提条件：** [Android Studio](https://developer.android.com/studio)

1. 用 Android Studio 打开 `android/` 目录
2. 等待 Gradle 同步完成
3. 连接手机或启动模拟器
4. 点击 Run 运行

## 使用方法

1. **部署 Worker** — 参照上方部署指南
2. **安装桌面端** — 打开应用，在设置中填入你的 Worker 地址，点击保存
3. **打开配对页面** — 桌面端会显示二维码和 6 位配对码
4. **安装 Android 端** — 打开应用，授予短信和通知权限
5. **扫码配对** — 用 Android 端扫描桌面端二维码
6. **开始使用** — 手机收到验证码后会自动同步到电脑剪贴板

## 小米 / MIUI / HyperOS 用户必读

小米系统会积极冻结后台进程，导致锁屏后无法接收验证码。请进行以下设置：

1. **自启动管理** — 设置 → 应用设置 → 自启动管理 → 开启 SMS Sync
2. **电池策略** — 设置 → 应用设置 → 省电策略 → SMS Sync → 选择「无限制」
3. **电池优化** — 设置 → 电池 → 更多电池设置 → 关闭 SMS Sync 的电池优化
4. **锁定后台** — 最近任务界面，下拉 SMS Sync 卡片，点击锁定图标
5. **开发者选项**（可选）— MIUI 优化 → 关闭

> 其他厂商（三星 OneUI、华为 EMUI 等）也可能有类似的后台限制，请在系统设置中为 SMS Sync 开放后台权限。

## API 接口

Worker 提供以下 HTTP 接口：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/create-room` | 创建房间，返回 roomId、pairCode、desktopToken |
| POST | `/api/pair` | Android 用配对码加入房间 |
| GET | `/api/room-info` | 查询房间配对状态 |
| POST | `/api/send-code` | HTTP 回退发送验证码 |
| GET | `/ws` | WebSocket 实时连接 |

## 技术栈

| 组件 | 技术 |
|------|------|
| Android | Kotlin、Jetpack Compose、Material 3、CameraX、ML Kit |
| 桌面端 | Tauri 2、Rust、Vue 3、TypeScript、Vuetify |
| 云端中转 | Cloudflare Workers、Durable Objects、TypeScript |
| 通信协议 | WebSocket + HTTP 回退 |

## 许可证

[MIT](LICENSE)
