# Speed Booster

A tiny (~55 KB) Android app that keeps your **Hutch** (Sri Lanka) connection awake to avoid idle speed throttling, inspired by [RunHutch](https://github.com/SunethUdayanga/RunHutch) and Z Pinger, but lighter.

## Features

- **Boost modes**: Gaming, Aggressive, Balanced (default), Relaxed, or Custom.
  - Balanced sends a request to Hutch's selfcare portal every 20 s, using the same method as RunHutch.
  - Gaming pings a Hutch server every 0.5 s.
- **Live status and connection score (0–100)** in the app: latency, jitter, loss, uptime.
- **Quick Settings tile.** Toggle it on to start. Toggle it off to stop and remove the app from memory completely.
- **Keeps running** when you clear Recents, and asks you to turn off battery optimization (with tips for Xiaomi, Samsung, Oppo, Vivo and Huawei).
- Works on Hutch SIM data and on Wi-Fi from a Hutch router or MiFi.
- No ads, no tracking, no extra libraries.

## Install

Download the APK from [Releases](../../releases/latest) and install it (allow "Install unknown apps"). Android 7.0+.

## Build

```sh
./gradlew assembleRelease
```

Release signing reads `keystore.properties` (not committed) or the `ANDROID_KEYSTORE_*` environment variables. Without either, the build produces an unsigned release.

## Disclaimer

For educational purposes. You are responsible for complying with your ISP's terms of service.

---

Developed by **Mujahideen Razick** · [github.com/mecdeen](https://github.com/mecdeen)
