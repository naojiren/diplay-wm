# WM EX5 perf10: existing Wi-Fi and instrument defaults

Based on perf9 (`25f839616d380a0b04fe3a21309bb7a123db9f6e`), retaining perf1-ch36 audio playback and perf8 video/USB fixes.

Instrument defaults are now 100% resolution and 4 fps. Existing user preferences remain in place, including centered aspect-fit projection and display selection/margins. The attached car already had 100%/4 saved on October 11, so no preference migration is necessary.

Existing Wi-Fi sessions acquire a full high-performance Wi-Fi lock to avoid station power save while streaming; teardown releases it. Firmware permission restrictions do not prevent connecting. This does not join Wi-Fi, change the router channel, change credentials, bind the default route, or suppress genuine network-loss notifications. Loss diagnostics include station state, frequency, RSSI and link speed without SSID, password or BSSID.

ADB evidence on October 11 showed actual Wi-Fi station disconnections before DiPlay's restart, including Android gateway-neighbor failures (`CMD_IP_REACHABILITY_LOST`, `NUD_FAILED`). This also occurred before the latest DiPlay process started. The existing router was on channel 44 / 5220 MHz; application-created fixed-channel preferences do not reconfigure an existing router. Signals around -63 to -65 dBm and no sampled interface errors do not establish why the station disconnects. Kernel disconnect events alone do not distinguish the router, supplicant and OEM firmware as the original cause. Wi-Fi power saving is a mitigation to test, not a verified root cause or a guarantee of uninterrupted CarPlay.

The unrelated MMC4 timeout messages are not proof of a Wi-Fi hardware fault: the active Broadcom Wi-Fi interface is attached to MMC2 (SDIO ID 02D0:4339).

The user explicitly authorized restoring head-unit microphone input after the perf9 phone-only experiment caused the OEM Bluetooth call UI to appear when invoking Siri. Normal microphone permission handling and CarPlay input advertisement now follow perf8 again. The media sink still rejects recording when microphone permission is unavailable. This restores the standard CarPlay audio path; it does not force the iPhone microphone or disable the OEM Bluetooth phone service. A timed Siri trace and real vehicle test remain necessary before claiming that the call UI problem is resolved.

The subsequent ADB activity snapshot recorded `com.yftech.btphone/.activity.CallActivity` as the last paused activity after returning to CarPlay. This confirms the OEM call screen identity, but does not by itself prove an outgoing telephone call or the exact audio-route trigger. The buffered trace did not retain the original Siri/HFP transition.

Required validation: repository CI unit tests, lint, and all three debug apps. Also validate API 19–23 APK signatures, original signer, authentication resources and all four ABI USB symbols. Preserve the original Ubuntu source tree; build in a separate perf10 directory.
