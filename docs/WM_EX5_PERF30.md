# WePlay perf30: repeated wheel-key presses

Perf29 retained up to 32 key identities using only key code, downTime, device and
scan code. An OEM release-only key that reused a positive downTime was consequently
accepted once and suppressed indefinitely. Zero timestamps had the same problem.
This is a code-level reproduction; the car's event timestamps have not been read
for this release because the owner requested no ADB connection.

Replace the historical press set with bounded event deduplication:

- Include both downTime and eventTime in duplicate observations.
- Use a local monotonic 200 ms lifetime for identical observations; duplicate
  arrivals do not extend it. Distinct event timestamps are never time-debounced.
- Track active DOWN/UP pairs independently, including long holds and reordered
  window/factory delivery, so one physical press produces one command.
- Completely timestamp-free, identical presses within 200 ms cannot be distinguished
  from duplicate delivery and are coalesced; subsequent presses work again.

Regression tests cover repeated constant downTime, all-zero timestamps, rapid
distinct releases, long holds, reordered delivery, and cross-channel duplicates.
Audio, wireless protection, microphone, foreground picture refresh and defaults
remain those of perf29. No ADB access or car installation is part of this release.
