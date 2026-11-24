# 🙏 Open-Source Credits, Attribution & Respect

**V2Vex** exists only because of the years of relentless engineering, cryptographic research, and community stewardship by upstream open-source developers. We extend our deepest respect and gratitude to every creator whose work powers or inspires V2Vex:

---

## Core Engines & Native Networking

### 1. Project X / XTLS — [`XTLS/Xray-core`](https://github.com/XTLS/Xray-core)
- **Authors**: **@RPRX** and all Project X / Xray-core contributors
- **License**: Mozilla Public License 2.0 (`MPL-2.0`)
- **Role in V2Vex**: Primary high-performance proxy engine powering VLESS, XTLS-Vision, REALITY, XHTTP / SplitHTTP, HTTPUpgrade, VMess, Trojan, Shadowsocks, WireGuard outbound, DNS DoH cache, and Observatory failover.

### 2. HevSocks5Tunnel — [`heiher/hev-socks5-tunnel`](https://github.com/heiher/hev-socks5-tunnel)
- **Author**: **Wanli He (@heiher)**
- **License**: MIT License (`MIT`)
- **Role in V2Vex**: Ultra-lightweight, rock-solid native C Layer-3 TUN-to-SOCKS5 packet forwarder (`libhev-socks5-tunnel.so`) and exact kernel-free L3 byte counter (`TProxyGetStats`).

### 3. Sing-box — [`SagerNet/sing-box`](https://github.com/SagerNet/sing-box)
- **Author**: **nekohasekai (@nekohasekai)** and SagerNet contributors
- **License**: GNU General Public License v3.0 (`GPL-3.0`)
- **Role in V2Vex**: Modular Dual-Core target architecture enabling Hysteria2 and TUIC v5 protocol configuration & plugin compatibility.

---

## Routing Datasets & Ecosystem Pioneers

### 4. V2Ray Rules Dat — [`Loyalsoldier/v2ray-rules-dat`](https://github.com/Loyalsoldier/v2ray-rules-dat)
- **Author**: **@Loyalsoldier**
- **License**: GNU General Public License v3.0 (`GPL-3.0`)
- **Role in V2Vex**: Continuously maintained `geoip.dat` and `geosite.dat` datasets for private-LAN bypass, regional routing, and ad/tracker blocking.

### 5. Android Proxy Client Pioneers
- **v2rayNG** by **@2dust** ([`2dust/v2rayNG`](https://github.com/2dust/v2rayNG))
- **NekoBoxForAndroid** by **@MatsuriDayo** ([`MatsuriDayo/NekoBoxForAndroid`](https://github.com/MatsuriDayo/NekoBoxForAndroid))
- **Respect**: For establishing shareable proxy URI conventions (`vless://`, `vmess://`, `trojan://`, `hysteria2://`, `tuic://`), subscription headers, and Android plugin patterns used across the community.

### 6. WireGuard, Curve25519 & Cloudflare Edge
- **Jason A. Donenfeld** ([WireGuard](https://www.wireguard.com/)) & **Daniel J. Bernstein** (Curve25519 / X25519)
- **Cloudflare, Inc.** (`speed.cloudflare.com` & Cloudflare WARP network)

### 7. Android & Kotlin Toolchain
- **JetBrains** (Kotlin, Coroutines, `kotlinx.serialization`)
- **Android Open Source Project (AOSP)** (Jetpack Compose Material 3, AndroidX)
