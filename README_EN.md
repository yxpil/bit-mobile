# BIT Mobile — Android Companion for BIT

[![Release](https://img.shields.io/github/v/release/yxpil/bit-mobile?style=flat-square&label=Version)](https://github.com/yxpil/bit-mobile/releases/latest) [![Downloads](https://img.shields.io/github/downloads/yxpil/bit-mobile/total?style=flat-square&label=Downloads)](https://github.com/yxpil/bit-mobile/releases) [![License](https://img.shields.io/github/license/yxpil/bit-mobile?style=flat-square)](https://github.com/yxpil/bit-mobile/blob/main/LICENSE) [![Platform](https://img.shields.io/badge/Platform-Android%209%2B-black?style=flat-square)](https://github.com/yxpil/bit)

English | [简体中文](README.md)

Native Android companion (Kotlin / Jetpack Compose) for BIT desktop ([yxpil/bit](https://github.com/yxpil/bit)): show the pairing QR code from **Remote Access** in desktop BIT, scan it with this app, and chat with your agent on the go — including approving its tool calls. Targets desktop protocol **v0.5.14+**.

**BIT is forever free**: fully open source (Apache-2.0), no IAP, no subscription, no telemetry.

> Black-and-white pill design · Light/dark themes consistent with desktop · Scan to pair

## Features

- **Scan to pair**: the QR code carries a BIT-Crypt v1 ciphertext (`BIT1:`) only this app can decrypt — connection credentials survive screenshots; manual code entry as fallback.
- **Multi-path connect**: tries candidates in priority order — LAN direct → IPv6 direct → cloud relay (symmetric NAT); per-candidate health/auth results are shown honestly.
- **Chat anywhere**: each device gets its own session on the desktop (`remote-<random>`, sidPolicy=device), never mixing with the desktop's active session; chats stay on your device and desktop only.
- **Tool approvals**: when the desktop agent wants to run a tool (ask mode), an approval card pops up on the phone — allow or deny.
- **Honest status**: auth failures, unreachable candidates and relay limits are surfaced with concrete reasons, never swallowed silently.

## Security & Privacy

- QR payload is encrypted with BIT-Crypt v1 (SHA256-CTR + embedded master key), cross-verified against the desktop implementation.
- The cloud relay forwards but never stores content.
- On desktop v0.5.14 the bitsign-v2 device material never leaves the desktop, so relayed requests are rejected with 403 by design — the app presents this honestly; **LAN / IPv6 direct are unaffected**. Once the desktop ships the companion upgrade, relay works without app changes.

## Install

Grab the APK from [Releases](https://github.com/yxpil/bit-mobile/releases) (Android 9+).

1. Desktop BIT → Remote Access → show the QR code
2. Scan on the phone (camera permission required on first use)
3. Chat; tool requests appear as approval cards in ask mode

## Development

- Stack: Kotlin + Jetpack Compose + CameraX / ML Kit + OkHttp + Coroutines
- Build: Android Studio or `./gradlew assembleDebug` (requires **JDK 17**; pin via `org.gradle.java.home` in `~/.gradle/gradle.properties`)
- Tests: `./gradlew testDebugUnitTest` (14 protocol tests; crypto/signing vectors independently generated with node crypto, mirroring desktop security.rs)
- Full-link E2E: `node e2e/mobile-e2e.cjs` — boots a real BIT instance (headless + isolated data dir + mock-ai upstream; needs a local debug build of [yxpil/bit](https://github.com/yxpil/bit), override with `BIT_BIN`/`BITLC_DIR`) and covers scan→connect→chat→approve/deny→illegal-input scenarios

## License

[Apache-2.0](LICENSE)
