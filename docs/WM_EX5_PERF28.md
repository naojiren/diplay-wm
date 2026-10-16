# WePlay perf28: verified local-ADB radio lease

Fixes perf27's confirmed failure on CQ1301 Android 6: the real app has an empty capability bounding set. Its su subprocess cannot perform Broadcom driver ioctls, even though a root ADB test succeeds. Failed queries are now reported with errno and permission status, separately from unsupported chip identifiers.

The narrowly scoped WM radio guard uses the existing, already authorized loopback LocalAdb client, without prompting or changing ADB security. It checks the native probe for root driver access, starts a detached native watchdog, and requires both a startup acknowledgement and verified driver state. Existing high-performance Wi-Fi and CPU locks remain the fallback when local ADB is unavailable; retry is bounded to once per 30 seconds.

The app writes a private per-session heartbeat every two seconds. The watchdog holds the existing exclusive recovery journal and restores the original settings when the heartbeat file disappears, the app PID ends, or heartbeats stop for 20 seconds. Fork/setsid ensures the watchdog survives the initiating ADB shell closing. Session close removes its lease and waits for the restoration report. No shell credentials or new privileged network listener are added.

The policy remains scan suppression plus verified pm2_sleep_ret >= 2000ms while connected. PM_OFF is attempted once and never claimed if readback stays PM_FAST. No firmware, SDIO bus frequency, saved Wi-Fi channel, audio codec, 300ms media-buffer default, microphone, or cluster settings are changed.

Validation includes the required Gradle unit/lint/build checks, native policy regression, detached watchdog lifecycle checks on the car, and a dedicated instrumentation mode exercising activation and restoration from the actual app permissions. The real-car listening test is still required: correcting the activation defect does not prove all SDIO timeouts or RTP loss are eliminated.
