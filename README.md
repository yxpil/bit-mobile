# BIT Mobile — BIT 安卓伴侣端

[![Release](https://img.shields.io/github/v/release/yxpil/bit-mobile?style=flat-square&label=%E7%89%88%E6%9C%AC)](https://github.com/yxpil/bit-mobile/releases/latest) [![下载](https://img.shields.io/github/downloads/yxpil/bit-mobile/total?style=flat-square&label=%E4%B8%8B%E8%BD%BD)](https://github.com/yxpil/bit-mobile/releases) [![License](https://img.shields.io/github/license/yxpil/bit-mobile?style=flat-square)](https://github.com/yxpil/bit-mobile/blob/main/LICENSE) [![平台](https://img.shields.io/badge/%E5%B9%B3%E5%8F%B0-Android%209%2B-black?style=flat-square)](https://github.com/yxpil/bit)

简体中文 | [English](README_EN.md)

BIT 移动端伴侣应用（原生 Android / Jetpack Compose）：在桌面端 BIT（[yxpil/bit](https://github.com/yxpil/bit)）的 **远程访问** 页出示连接二维码，用本应用扫码即可配对，随身对话、随时审批 AI 的工具调用。当前对接桌面端 **v0.5.14+** 协议。

**BIT 永久免费**：完全开源（Apache-2.0），无内购、无订阅、无遥测，可随时自行编译。

> 黑白胶囊设计 · 与桌面端一致的深/浅色主题 · 扫码即配

## 功能特性

- **扫码配对**：二维码内容为 BIT-Crypt v1 加密密文（`BIT1:`），仅本应用可解——截图外泄也读不出连接凭据；无摄像头场景支持手动粘贴连接码。
- **多路连接**：按二维码携带的候选自动尝试——局域网直连 → IPv6 直连 → 云中继（对称 NAT 场景），每路候选的探活与认证结果如实呈现。
- **随身对话**：每台设备在桌面端拥有独立会话（`remote-<随机>`，sidPolicy=device），绝不与桌面激活会话或其它设备串线；对话仅存本机与桌面端。
- **工具审批**：桌面端 Agent 请求执行工具时（ask 模式），手机端实时弹出审批卡片，可允许 / 拒绝；与桌面端审批互为补充。
- **连接状态诚实呈现**：认证失败、候选不可达、中继不可用均给出具体原因，不静默吞错。

## 安全与隐私

- 二维码密文使用 BIT-Crypt v1（SHA256-CTR + 内置主密钥），与桌面端同算法交叉验证。
- 云中继只转发、不留存任何内容（与二维码隐私声明一致）。
- v0.5.14 桌面端的信道签名材料（bitsign-v2 设备凭证）不出桌面端，因此经云中继的请求会被桌面端 403 拒绝——应用如实呈现该限制；**局域网 / IPv6 直连不受影响**。桌面端配套升级后本应用无需改动即可启用中继。

## 安装

从 [Releases](https://github.com/yxpil/bit-mobile/releases) 下载 APK 安装（Android 9+）。

1. 桌面端 BIT → 远程访问 → 出示连接二维码
2. 手机端 扫码 配对（首次需授权相机）
3. 对话页发消息；ask 模式下工具请求会以卡片形式弹出供审批

## 开发

- 技术栈：Kotlin + Jetpack Compose + CameraX / ML Kit + OkHttp + Coroutines
- 构建：Android Studio 或 `./gradlew assembleDebug`（需 **JDK 17**；本机可用 `~/.gradle/gradle.properties` 的 `org.gradle.java.home` 固定）
- 测试：`./gradlew testDebugUnitTest`（14 个协议层用例，加密/签名向量由 node crypto 按桌面端 security.rs 同口径独立生成）
- 全链接 E2E：`node e2e/mobile-e2e.cjs`——自动拉起真实 BIT 实例（headless + 隔离数据目录 + mock-ai 上游，需本机有 [yxpil/bit](https://github.com/yxpil/bit) 的 debug 构建，可用 `BIT_BIN`/`BITLC_DIR` 覆盖路径），覆盖扫码→连接→对话→审批允许/拒绝→非法用例 7 个场景

## 许可

[Apache-2.0](LICENSE)
