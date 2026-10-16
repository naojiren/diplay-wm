# WePlay perf29: foreground picture and media keys

Based on perf28, whose audio continuity the owner confirmed on the car.

- Refresh the main video stream when the low-latency surface is recreated and
  when the window returns from the background. Static CarPlay pages no longer
  depend on the next user gesture to request a picture.
- Route foreground media-key events through the same press history as Android
  MediaSession and the factory listener. One physical press is delivered once.
- Accept release-only play/pause, play and pause keys in addition to next/previous.
  Optional factory playback subscriptions cannot disable existing arrow handling.
- Keep the factory listener while the activity remains visible, including temporary
  pauses caused by overlays; unregister when it stops or is destroyed.

Audio, microphone, wireless guard, icon, and user defaults are unchanged.
Physical foreground restoration and wheel-key behavior require owner testing.
