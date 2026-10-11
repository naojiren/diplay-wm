# DiPlay WM EX5 optimized CarPlay receiver · diplay-wm

[简体中文](README.md) · [English](README.en.md)

A DiPlay Legacy Android variant adapted for a 2019 WM Motor EX5 head unit running Android 6.0.1. It supports the center display and instrument-cluster map, with existing audio, USB, wireless and low-latency changes preserved.

[Download the latest APK](https://github.com/naojiren/diplay-wm/releases/latest) · [Releases](https://github.com/naojiren/diplay-wm/releases) · [Issues](https://github.com/naojiren/diplay-wm/issues)

Install on the head unit, not the iPhone. The current release is the original tested **perf18** APK, version code **62**, package **com.shihab.diplay.wmex5**. It has not been slimmed or rebuilt for this publication.

## Defaults and features

- Center display: 50% resolution, 18 fps. Instrument map: enabled, 100% resolution, 18 fps, aspect-preserving centering, 24% left and 5% bottom margins.
- Both screens offer 12/18/24/30 fps; the center display retains additional higher values. The WM menus exclude 4/8 fps.
- Wi-Fi Direct defaults to fixed 5 GHz channel 44, with channels 36/157 and 2.4 GHz 1/6 available. Same-LAN mode remains available and reads the head unit's current Wi-Fi without requiring a password. WM wireless uses IPv4.
- Bluetooth is enabled when the app opens. Optional boot launch and connection on open are available under Settings → Connection → Automatic connection. Select Wireless as the default connection mode and Wi-Fi Direct in connection setup. Cold-boot behavior depends on the head unit's BOOT_COMPLETED delivery; suspend/resume requires separate verification.
- Existing audio output is retained. The phone-microphone experiment disables head-unit capture without removing normal microphone capability negotiation. It cannot force the iPhone to keep its built-in microphone selected. Disable the experiment and reconnect if Siri loses input or the Bluetooth phone UI appears.

The upgrade applies the perf18 frame-rate, transport, channel and microphone defaults once; later manual choices persist. Updating earlier WM packages signed with the same key preserves data. Self-built APKs may use a different signing key.

## Compatibility and validation

Minimum SDK 19 (Android 4.4); the primary physical target is the 2019 EX5 / Android 6.0.1 / TI DRA74x. Other head units remain unverified. The original release includes ARMv7, ARM64, x86 and x86_64; the EX5 uses ARMv7.

The release passed 2,115 unit tests, three application lint checks and Android 6 dual-display/audio runtime checks. The owner reported successful testing and improved microphone response. This does not independently establish which iPhone input device was selected. See [release details](docs/WM_EX5_PERF18.md).

USB depends on firmware driver compatibility and the Android VPN authorization component. Wi-Fi Direct can disconnect the head unit from ordinary Wi-Fi. For Same LAN, connect both devices to the same router; if setup stalls, reconnect the iPhone's Wi-Fi.

## Build and provenance

See [build instructions](docs/BUILD.md). Public source excludes runtime identity inputs, APK signing keys and user pairing data. A source-only build is available with `./gradlew :mobile:assembleDebug`; a standalone build requires explicit authentication input.

Based on [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android), [DiPlay](https://github.com/shihabal3amri/DiPlay) and [xcertplay](https://github.com/shilapi/xcertplay). GPL-3.0 receiver and AGPL-3.0-derived UI notices, as well as separate dependency/asset terms, are preserved in [LICENSE](LICENSE) and [third-party notices](docs/THIRD_PARTY_NOTICES.md).

This is not an Apple-certified product. The APK contains the existing experimental accessory identity described in [SECURITY.md](SECURITY.md); it is extractable and future acceptance is not guaranteed. The APK signing private key is not included. Runtime source matches tested local commit `55e651c1bf987acde4c5fa6a8034146830689156`; publication adds documentation and CI only.
