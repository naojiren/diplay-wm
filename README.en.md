# DiPlay WM EX5 optimized CarPlay receiver · diplay-wm

[简体中文](README.md) · [English](README.en.md)

A DiPlay Legacy Android variant adapted for a 2019 WM Motor EX5 head unit running Android 6.0.1. It supports the center display and instrument-cluster map, with existing audio, USB, wireless and low-latency changes preserved.

[Download the latest APK](https://github.com/naojiren/diplay-wm/releases/latest) · [Releases](https://github.com/naojiren/diplay-wm/releases) · [Issues](https://github.com/naojiren/diplay-wm/issues)

Install on the head unit, not the iPhone. The current release is **perf19 ARMv7 audio fix**, version code **63**, package **com.shihab.diplay.wmex5**. It fixes voice input/output switching problems. The owner confirmed working ChatGPT voice conversations and Siri map navigation on the 2019 WM EX5 / Android 6.0.1. Previous perf18 APKs remain in historical releases.

See the [perf19 release notes](docs/WM_EX5_PERF19.md) for the changes and validation scope.

## Defaults and features

- Center display: 50% resolution, 18 fps. Instrument map: enabled, 100% resolution, 18 fps, aspect-preserving centering, 24% left and 5% bottom margins.
- Both screens offer 12/18/24/30 fps; the center display retains additional higher values. The WM menus exclude 4/8 fps.
- Wi-Fi Direct defaults to fixed 5 GHz channel 44, with channels 36/157 and 2.4 GHz 1/6 available. Same-LAN mode remains available and reads the head unit's current Wi-Fi without requiring a password. WM wireless uses IPv4.
- Bluetooth is enabled when the app opens. Optional boot launch and connection on open are available under Settings → Connection → Automatic connection. Select Wireless as the default connection mode and Wi-Fi Direct in connection setup. Cold-boot behavior depends on the head unit's BOOT_COMPLETED delivery; suspend/resume requires separate verification.
- Head-unit microphone capture is restored. A bundled software Opus encoder is used when the system encoder is unavailable, and Siri playback uses a compatible audio usage on Android 6. The renamed Disable head-unit microphone experiment is off by default; enabling it cannot force the iPhone microphone and may prevent voice input.

Upgrading perf18 to perf19 restores microphone capture once while preserving pairing, display and network settings. Fresh installs keep the defaults above; later manual choices persist. Updating earlier WM packages signed with the same key preserves data. Self-built APKs may use a different signing key.

## Compatibility and validation

Minimum SDK 19 (Android 4.4); the primary physical target is the 2019 EX5 / Android 6.0.1 / TI DRA74x with a 32-bit ARMv7 system. The perf19 APK includes only ARMv7 and is about 12.75 MB. Removing unused architectures reduces download and storage size; it does not itself reduce video latency. Other head units remain unverified.

The perf19 build completed, was installed as an update and received owner confirmation that ChatGPT voice and Siri navigation work. Remaining automated checks were skipped at the owner's request; full CI success is not claimed for this release. The 2,115 tests and runtime checks in the [perf18 record](docs/WM_EX5_PERF18.md) are historical. See [perf19 details](docs/WM_EX5_PERF19.md).

USB depends on firmware driver compatibility and the Android VPN authorization component. Wi-Fi Direct can disconnect the head unit from ordinary Wi-Fi. For Same LAN, connect both devices to the same router; if setup stalls, reconnect the iPhone's Wi-Fi.

## Build and provenance

See [build instructions](docs/BUILD.md). Public source excludes runtime identity inputs, APK signing keys and user pairing data. A source-only ARMv7 build is available with `./gradlew :mobile:assembleDebug`; a standalone build requires explicit authentication input. Add `-PwmUniversalAbis=true` for a four-architecture build or x86 emulator instrumentation.

Based on [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android), [DiPlay](https://github.com/shihabal3amri/DiPlay) and [xcertplay](https://github.com/shilapi/xcertplay). GPL-3.0 receiver and AGPL-3.0-derived UI notices, as well as separate dependency/asset terms, are preserved in [LICENSE](LICENSE) and [third-party notices](docs/THIRD_PARTY_NOTICES.md).

This is not an Apple-certified product. The APK contains the existing experimental accessory identity described in [SECURITY.md](SECURITY.md); it is extractable and future acceptance is not guaranteed. The APK signing private key is not included. The initial public snapshot came from local commit `55e651c1bf987acde4c5fa6a8034146830689156`; perf19 adds audio fixes while preserving WM display and latency adaptations. Software Opus provenance is recorded in [WM_EX5_OPUS_SOURCE.json](docs/WM_EX5_OPUS_SOURCE.json).
