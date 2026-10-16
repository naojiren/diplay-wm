# WePlay WM EX5 CarPlay receiver · diplay-wm

[简体中文](README.md) · [English](README.en.md)

A DiPlay Legacy Android variant adapted for a 2019 WM Motor EX5 head unit running Android 6.0.1. It supports the center display and instrument-cluster map, with existing audio, USB, wireless and low-latency changes preserved.

[Download the perf30 test APK](https://github.com/naojiren/diplay-wm/releases/tag/v0.2.17-wm-perf30-key-repeat) · [Releases](https://github.com/naojiren/diplay-wm/releases) · [Issues](https://github.com/naojiren/diplay-wm/issues)

Install on the head unit, not the iPhone. The current source and test APK are **WePlay perf30 ARMv7**, version code **74**, package **com.shihab.diplay.wmex5**, with the owner's selected icon 5.

Perf30 fixes wheel media keys that could respond once and then remain blocked when the head unit reuses event timestamps. It retains perf29's foreground picture refresh and the perf28 audio and wireless changes that the owner found free of audio stutter.

[Download perf30](https://github.com/naojiren/diplay-wm/releases/tag/v0.2.17-wm-perf30-key-repeat) · [Release notes and validation](docs/WM_EX5_PERF30.md). The full CI command and 2,196 tests passed. Perf30 has not yet been installed or verified on the vehicle; this is a prerelease.

## Defaults and features

- Center display: 50% resolution, 18 fps. Instrument map: enabled, 100% resolution, 18 fps, aspect-preserving centering, 24% left and 5% bottom margins.
- Both screens offer 12/18/24/30 fps; the center display retains additional higher values. The WM menus exclude 4/8 fps.
- Wi-Fi Direct defaults to fixed 5 GHz channel 44, with channels 36/157 and 2.4 GHz 1/6 available. Same-LAN mode remains available and reads the head unit's current Wi-Fi without requiring a password. WM wireless uses IPv4.
- Bluetooth is enabled when the app opens. Connection on open defaults to off. Optional boot launch and connection on open are available under Settings → Connection → Automatic connection. Select Wireless as the default connection mode and Wi-Fi Direct in connection setup. Cold-boot behavior depends on the head unit's BOOT_COMPLETED delivery; suspend/resume requires separate verification.
- Head-unit microphone capture is restored. A bundled software Opus encoder is used when the system encoder is unavailable, and Siri playback uses a compatible audio usage on Android 6. The renamed Disable head-unit microphone experiment is off by default; enabling it cannot force the iPhone microphone and may prevent voice input.

Updating earlier WM packages signed with the same key preserves pairing, display and network settings. Self-built APKs may use a different signing key. The existing car-marker vertical setting can be changed to Down 10% (about 46 pixels at the current cluster height); it applies after reconnection and is not a new default baked into perf30.

## Compatibility and validation

Minimum SDK 19 (Android 4.4); the primary physical target is the 2019 EX5 / Android 6.0.1 / TI DRA74x with a 32-bit ARMv7 system. The perf30 APK includes only ARMv7 and is about 12.85 MB. Removing unused architectures reduces download and storage size; it does not itself reduce video latency. Other head units remain unverified.

The perf30 APK matches the validated build artifact. The full CI command passed: 2,196 unit tests and zero lint errors, including wheel-key timestamp reuse and duplicate event coverage. Vehicle testing of perf30 is pending. See [perf30 details](docs/WM_EX5_PERF30.md).

USB depends on firmware driver compatibility and the Android VPN authorization component. Wi-Fi Direct can disconnect the head unit from ordinary Wi-Fi. For Same LAN, connect both devices to the same router; if setup stalls, reconnect the iPhone's Wi-Fi.

## Build and provenance

See [build instructions](docs/BUILD.md). Public source excludes runtime identity inputs, APK signing keys and user pairing data. A source-only ARMv7 build is available with `./gradlew :mobile:assembleDebug`; a standalone build requires explicit authentication input. Add `-PwmUniversalAbis=true` for a four-architecture build or x86 emulator instrumentation.

Based on [DiPlay Legacy Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android), [DiPlay](https://github.com/shihabal3amri/DiPlay) and [xcertplay](https://github.com/shilapi/xcertplay). GPL-3.0 receiver and AGPL-3.0-derived UI notices, as well as separate dependency/asset terms, are preserved in [LICENSE](LICENSE) and [third-party notices](docs/THIRD_PARTY_NOTICES.md).

This is not an Apple-certified product. The APK contains the existing experimental accessory identity described in [SECURITY.md](SECURITY.md); it is extractable and future acceptance is not guaranteed. The APK signing private key is not included. The initial public snapshot came from local commit `55e651c1bf987acde4c5fa6a8034146830689156`; later versions add audio and control-queue fixes while preserving WM display and latency adaptations. Software Opus provenance is recorded in [WM_EX5_OPUS_SOURCE.json](docs/WM_EX5_OPUS_SOURCE.json).
