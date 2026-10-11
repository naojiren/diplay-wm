# WM EX5 perf4

User-requested lower video load: the EX5 package defaults to 40% resolution and 8 fps. Both frame-rate menus now include 4 and 8 fps, retaining 12/18/30/60 and legacy intermediate rates. Sanitization and iPhone maxFPS advertisement preserve 4/8 exactly; the existing EX5 instrument stream cap follows the selected lower frame rate.

The perf4 startup migration applies resolution/fps once even when perf3 has already initialized. Later manual choices survive reopening. On a perf3 upgrade, other driver settings (including instrument enable/margins, codec, audio and Wi-Fi mode/channel) are preserved. Fresh/older installs still receive the original EX5 startup profile and Bluetooth behavior.

Built in a separate Ubuntu directory /home/ub/codex-builds/diplay-wm-ex5-perf4-20261010. Audio implementation, low-latency rendering, WM instrument routing, fixed 36/1 radio selection, same-Wi-Fi mode and USB missing-VPN crash handling are inherited from perf3 without code changes.

VersionCode 42, version 0.2.17-legacy.1-wm-ex5-perf4; package/signing identity unchanged for an overwrite upgrade. Vehicle responsiveness and the iPhone's actual video rate require physical testing.

Validation completed: full repository CI, standalone and AndroidTest builds passed with 2024 unit tests (1095 shared, 925 common, 4 home), zero failures/errors/skips; lint has zero errors. API 23 installed-APK tests passed twice, including a real perf3 overwrite upgrade to 40%/8 fps with old data/margins retained, low-rate persistence, authentication, real AudioTrack writes and the missing-system-VPN crash condition. v1/v2 and API 19–23 signatures verified; all 20 native libraries across four ABIs retained, audio baseline unchanged across 26 source files. Previous Ubuntu perf3 APK hash remains unchanged. APK SHA256: cec8e0ae2525be782aae4f61ee559aaa89f3434a3a02d7c557d53d81f564b774.
