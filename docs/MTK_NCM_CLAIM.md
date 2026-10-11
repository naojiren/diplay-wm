# Android 6 MTK NCM claim investigation

Baseline: `c8884adcc75bfda3c134db63877bd6c6f83beb74` (`main` and `v0.2.7` at investigation time).
Branch: `pr-mtk-api23-ncm-claim-probe`. This is a physical-retest candidate, not a verified CarPlay fix.

## Evidence and ranked hypotheses

The AC8227L/API23/ARMv7 test reaches iPhone VID `05ac`, PID `12a8`, permission, configuration 6,
and parsed NCM control `3/0`, data `4/1`, status `87`, bulk IN `88`, OUT `06`. It then fails to
claim interface 3, including after RST and removal of the previous phone-link app.

1. **Kernel networking ownership with a failed framework detach/reclaim** is plausible: NCM
   control class/subclass `02/0d` can match the generic `cdc_ncm` driver, which also claims the data
   interface. Binding on this vendor kernel remains unproven until `GETDRIVER` reports its name.
2. **Stale/current configuration mismatch or disconnected device state** remains possible:
   descriptors alone do not prove the active kernel configuration. The existing USBMUX opening
   code logs a failed `setConfiguration` but continues. The failed-claim fallback now reads
   `GET_CONFIGURATION` and stops on mismatch rather than detaching against stale descriptors.
3. **Vendor ioctl/SELinux denial, or a framework/vendor implementation difference** cannot be
   distinguished from a boolean. Native errno is needed. Permission to open a USB device does
   not establish permission/support for every ioctl.
4. **Another userspace owner or a detach-to-claim race** remains possible but has weaker evidence
   after RST and app removal. The patch refuses to detach `usbfs` or unknown driver names.

These are hypotheses ranked by the supplied evidence, not a diagnosis of the vendor kernel.

## API23 and kernel findings

[AOSP Android 6 JNI](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-6.0.1_r1/core/jni/android_hardware_UsbDeviceConnection.cpp)
first invokes `USBDEVFS_CLAIMINTERFACE`. Only failure with `EBUSY` and `force=true` invokes the
wrapped `USBDEVFS_IOCTL/USBDEVFS_DISCONNECT`, followed by another claim. The detach result is
discarded, and Java receives only the final boolean. `releaseInterface` releases and requests
driver reconnect. The [libusbhost implementation](https://android.googlesource.com/platform/system/core/+/android-6.0.1_r3/libusbhost/usbhost.c)
provides those ioctl wrappers. Repeating `force=true` therefore does not add a new mechanism.

The [API23 Java API](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-6.0.1_r1/core/java/android/hardware/usb/UsbDeviceConnection.java)
publicly exposes `getFileDescriptor()` for native use. The helper borrows that fd synchronously
before NCM workers start; it never opens a device node, duplicates/closes the fd, requests root,
or changes SELinux policy. No MFi/authentication flow changes.

[Linux v3.18 usbfs](https://raw.githubusercontent.com/torvalds/linux/v3.18/drivers/usb/core/devio.c)
implements `USBDEVFS_DISCONNECT_CLAIM` under the USB device lock. `IF_DRIVER` checks the current
driver name before detaching, then claims for the same open-file state. This removes the
two-ioctl interval and uses a different ioctl entry point, but still uses the same underlying
driver-release routine. It cannot repair arbitrary vendor driver bugs. This request is absent
in [mainline v3.4](https://raw.githubusercontent.com/torvalds/linux/v3.4/drivers/usb/core/devio.c);
a vendor backport cannot be assumed. An unsupported request or security denial is a stop condition.
The mainline operation does not itself require a root capability; actual vendor policy is unknown.
The atomic request structure has no pointer fields, including on ARMv7.

[Linux v3.18 CDC-NCM](https://raw.githubusercontent.com/torvalds/linux/v3.18/drivers/net/usb/cdc_ncm.c)
matches the control interface and claims the separate data interface. This bridge uses both
bulk data and the control status endpoint, so it must own both. Leaving control kernel-bound
would allow competing kernel configuration/status activity. Control-first, data-second, then
data alt 1 is retained. Claim is by interface number; alt selection is a separate later step.
Changing ordering/alt settings alone does not explain a failed first control claim.

The existing controller already closes/reopens around re-enumeration and retries failed sessions.
No new stale-fd retry loop, USB reset, configuration reselection, or delay is added. One direct
non-detaching native claim after failure can resolve a short-lived transition and reveal errno;
actual `ENODEV` or configuration mismatch requires fresh discovery through the existing path.

## Compatibility mechanism and safety

The default remains framework `claimInterface(force=true)` for control and data, then the same
`setInterface(data)`. Only failure on API23 + ARMv7 + a MTK/AC8227L board/hardware signature + Apple
VID + CDC-NCM descriptors permits lazy loading of a tiny JNI library in the existing ndk-build.
There is no new native dependency. Configuration numbers and interface IDs are descriptor-driven.

After checking the active configuration, one native `CLAIMINTERFACE` is attempted. A success is
tracked for cleanup and verified by framework `claimInterface(force=false)` on the same fd.
Only `EBUSY` with `GETDRIVER` naming `cdc_ncm` or `cdc_ether` permits one name-guarded atomic
`DISCONNECT_CLAIM`. A successful ioctl is likewise tracked before verification. Both interfaces
must be owned and `setInterface` must succeed before the bridge starts.

Claims unwind in reverse order on any later error; the connection is then closed. After an atomic
error the helper probes and requests reconnect only for unbound members of the NCM pair, because
kernel detachment can occur before a subsequent claim fails. After a successful native claim,
the same best-effort restoration supplements framework release on open failure or bridge close.
An already bound interface is untouched. Restore errno is logged. Rebinding cannot be guaranteed
when a vendor kernel or policy refuses it; unplug/replug may be required. No USB reset is used.

Modern Android, other manufacturers, and every successful framework path never invoke JNI or
the additional configuration request. Missing JNI, unknown drivers, other usbfs owners, denied
ioctls and unsupported kernels fail closed. Decision tests cannot validate kernel behavior.

## Physical retest and direction criteria

Install the standalone debug candidate beside the release package (`com.shihab.diplay.hudtest`).
Stop other phone-link apps, RST the unit, connect the same phone/cable, and export the debug log.
Inspect `legacyEligible`, API/manufacturer/board/hardware, configuration/control/data descriptors,
each claim result, active configuration, driver name, native errno, verification and `setInterface`.

* **GOOD DIRECTION (candidate only):** a supported, unprivileged atomic operation establishes
  and verifies ownership, then data alt 1 succeeds. Continue through the unchanged MFi flow.
* **BAD DIRECTION:** on the actual unit the needed ioctl is unsupported/denied, the kernel owner
  cannot safely be released, or ownership verification still fails. Do not broaden this into
  root, a kernel patch, unknown-driver detach, or a general USB rewrite to call it a success.

Useful errno values: `1 EPERM`, `13 EACCES`, `16 EBUSY`, `19 ENODEV`, `22 EINVAL`, `25 ENOTTY`,
`61 ENODATA`. An errno is evidence for that particular operation, not proof of a specific cause.

## Local validation (2026-10-06)

* `:shared:testDebugUnitTest`: 274 passed, including 12 decision tests and 8 framework transaction
  tests across API23/API28. `:common:testDebugUnitTest`: 72 passed. `:mobile:testDebugUnitTest`:
  `NO-SOURCE` (the module has no test sources). No failed/skipped tests.
* `:mobile:lintDebug`: success, zero errors, 19 warnings outside the new NCM files.
* `:mobile:assembleDebug` and `:mobile:assembleStandaloneDebug`: success. The source-only APK was
  verified identity-free. The standalone candidate uses the two existing local runtime assets
  from the tester-provided APK; byte equality was verified without printing their contents.
* APK manifest: minSdk 19, targetSdk 37, all four existing ABIs. JNI compiled with NDK r25c,
  `APP_PLATFORM=android-19`; the ARMv7 ELF Android note records API19. Kotlin native signatures
  match the compiled JNI exports. No dependency or SDK-version changes.
* `scripts/check_public_tree.py` and `git diff --check`: passed. APKs/assets remain outside Git.

The full Gradle command was
`./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --no-configuration-cache -Dhttps.protocols=TLSv1.2 -I /local/path/test-network.gradle`.
The local init script sets test JVM `robolectric.dependency.repo.url=https://repo.maven.apache.org/maven2`,
`https.protocols=TLSv1.2`, and a workspace `maven.repo.local`; it works around local TLS/download
failures without changing repository dependencies. Standalone packaging used
`DIPLAY_AUTH_ASSETS_DIR=/local/ignored/runtime-assets ./gradlew :mobile:assembleStandaloneDebug --no-configuration-cache -Dhttps.protocols=TLSv1.2 --stacktrace`.

No new AC8227L physical test has occurred. **GOOD DIRECTION as a narrowly gated candidate only**;
the kernel support, permission, actual owner, detach/claim, restoration and subsequent CarPlay
session must be checked on the unit. Apply the BAD DIRECTION criteria above without broadening
the workaround if the required unprivileged mechanism fails.
