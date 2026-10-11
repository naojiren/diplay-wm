# WM EX5 perf2

This build keeps the WM EX5 test application ID and signing identity. It selectively ports upstream fixes through `e1cb5aa35567d34024d37f2f36883c959feb93b1` (v0.2.26); it is not a full replacement with the stock v0.2.26 application.

## Included

- Microphone format negotiation from `78a8181`: advertise PCM alone when the Android codec list has no Opus encoder. Keep permission failures contained and add communication-mode/focus diagnostics.
- USB read ceiling and retry ladder from `2890996`; USB permission, native linkage and re-enumeration polling fixes from `9a21d4c` / `011cecd`; live NCM OUT halt recovery and failure detection from `28578a3`; pre-API-26 timeout cancellation from `7010bdf`.
- Cap-sized legacy read buffers qualify for the retry ladder without a larger direct allocation, while every API < 28 queued transfer stays at or below 16 KiB. A legacy wait already pending when its request was cancelled may finish with null; that one completion is discarded without poisoning the next read. Normal null completions and modern cancellation failures still fail.
- Crash-log copies from `5854866`: private and app-specific external directories, plus a best-effort public storage copy on API <= 28 when writable. Public storage permission is optional; no connection permission prompt is added for it. Wrapped transport exceptions include bounded cause and top-frame details in session logs.
- WM radio selector: fixed 5 GHz channel 36 by default, or fixed 2.4 GHz channel 1. Separate persistent preference, next-connection application and reconnect notice. Both options require channel-setting acceptance, never automatic fallback, and reject a known frequency mismatch. An unreadable actual frequency remains unknown in diagnostics.

## Preserved

WM instrument Presentation / TYPE_TOAST, safe-area margins and routing options; authentication asset handling; SurfaceView, disabled smoothing delay, video backlog limits and stale-output policy; touch MOVE coalescing with edge-event ordering. Radio selection remains independent of the low-latency rollback switch. Ordinary package preferences retain their previous behavior.

## Verification

Run the repository's full CI command plus standalone APK and instrumentation builds. Targeted regressions cover encoder/decoder distinction, PCM negotiation, API 23 USB queue fallback, live OUT failures, late timeout completions, both fixed channels and preference persistence. API 23 instrumentation exercises installed authentication, real audio playback, the channel dialog, PCM microphone RTP, log copies and connection UI. Actual iPhone interoperability, WM radio frequency and instrument display behavior require the car.
