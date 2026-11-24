# V2Vex — 99% Fast & Light Android V2Ray / Xray Client

**V2Vex** is a next-generation, ultra-lightweight Android V2Ray/Xray client engineered around the **"99% Fast & Light"** philosophy: zero dependency bloat, hot-swappable Xray-core updates, high-accuracy multi-stream Mbps benchmarking, deep power-user flexibility, and an interactive Material 3 interface.

## Core Philosophy & Highlights

- **99% Fast & Light**: Pure Kotlin + Coroutines + Jetpack Compose Material 3 + `kotlinx.serialization`. Zero reflection DI, zero analytics/SDK bloat, aggressive R8/ProGuard shrinking, and zero-drain standby.
- **Effortless Core Updates (2 Ways)**:
  - **In-App Core & GeoData Manager**: One-tap check & install latest `XTLS/Xray-core` and `Loyalsoldier/v2ray-rules-dat` (`geoip.dat` / `geosite.dat`) from GitHub Releases, or import any custom `.zip` / `xray` binary via system file picker.
  - **1-Command Build-Time Bundler**: Run `./scripts/update-core.sh` to fetch the latest official Android `arm64-v8a` & `armeabi-v7a` Xray-core binaries into `app/src/main/jniLibs/<abi>/libxray.so` (Android 10–15 W^X compliant).
- **High-Accuracy Mbps Measurement (`SpeedLab`)**:
  - Monotonic nanosecond clock (`System.nanoTime()`), parallel multi-stream Download/Upload (1x / 4x / 8x), warm-up discard (eliminates TLS/TCP slow-start skew), 150ms sliding-window sampling with trimmed-mean steady-state Mbps, RFC 3550-style Jitter, and Stability %.
  - Interactive 60fps Canvas logarithmic Arc Speedometer + touch-scrubbable live throughput chart.
- **Power-User Flexibility**:
  - **Protocols**: `VLESS` (`XTLS-Vision`, `REALITY`), `VMess`, `Trojan`, `Shadowsocks` (SIP002 & legacy), `SOCKS5`, `HTTP`, `WireGuard`, and **Raw Xray JSON** with live validation & JSON preview.
  - **Transports**: `TCP`, `WebSocket`, `gRPC`, `HTTPUpgrade`, `XHTTP / SplitHTTP`, `HTTP/2`, `mKCP`.
  - **Engine Controls**: TLS Fragmentation (DPI Bypass), Mux.Cool (`xudpConcurrency`), Traffic Sniffing, DoH/DoT/FakeDNS, Custom Routing Rule Builder, Per-App Split Tunneling, Subscriptions with `subscription-userinfo` quota parsing, Auto-Fastest Node Selector, Quick Settings Tile, and Full JSON Backup/Restore.

## Building

```bash
# Optional: bundle latest official Xray-core binaries into jniLibs/
chmod +x scripts/update-core.sh
./scripts/update-core.sh

# Run unit tests
gradle testDebugUnitTest

# Build Debug / Release APK
gradle assembleDebug
gradle assembleRelease
```
