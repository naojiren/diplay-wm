# WM EX5 perf3

Audio negotiation, media playback and microphone code restored exactly to the user-confirmed audio-good perf1-ch36 commit 7034141. USB transport and diagnostic fixes retained separately. No new Opus capability negotiation.

Defaults on the first EX5 launch after upgrading: H.264, 45% resolution, 18 fps, WM instrument profile enabled. Later manual changes remain saved. Existing instrument margins/display selection, audio settings, identity and saved Wi-Fi details are retained. 12/18/30/60 fps are available in display settings; the in-session display menu retains older intermediate rates. The EX5 cluster stream is capped to the selected frame rate when below its existing 30 fps cap.

Bluetooth is enabled at activity start on API 19–30; failed vendor requests are reported without crashing. Fixed Wi-Fi Direct channel defaults to 36 (5180 MHz), optional channel 1 (2412 MHz). Existing Wi-Fi remains selectable and its router controls the actual channel.

User crash.log repeatedly shows ActivityNotFoundException for com.android.vpndialogs.ConfirmDialog during USB startup. Missing/rejected VPN authorization is now handled with a clear message and returns to the app; it is never treated as granted permission. This firmware still needs its VPN authorization system component for the current USB NCM/VPN implementation. No silent bypass or claim of physical USB connectivity.

Ubuntu modified source and final APK preserved in /home/ub/codex-builds/snapshots/audio-audit-20261010 and the local ignored .private/ubuntu-audit-20261010 directory. New builds use a separate directory diplay-wm-ex5-perf3-20261010-validated.

Validation: full repository CI, standalone and AndroidTest builds passed; 2022 unit tests (shared 1095, common 923, home 4), zero failures/errors/skips. Lint: zero errors, existing warnings 22/5/2. Installed APK on API 23 passed authentication reload/six signatures/tamper rejection, real AudioTrack PCM writes/focus, upgrade defaults and preserved margins, same-Wi-Fi option, low-latency SurfaceView and USB page launch. A second run disabled the emulator VPN authorization package and revoked VPN consent, reproducing the firmware condition without a crash; permission remained ungranted and the explanatory UI appeared. Physical iPhone/car audio, Bluetooth, radio frequency and dual-display responsiveness still require vehicle testing.

Delivered versionCode 41, same package/signing identity, minSdk 19, four ABIs, v1/v2 signatures verified including API 19–23. APK SHA256: f531727dc7d22d8a241033d153bec76ac7fdc4ba8754d928f5851c5747000983.
