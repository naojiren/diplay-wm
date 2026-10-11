# EX5 instrument video defaults

The perf5 build adds independent EX5 instrument video settings, defaulting to
40 percent of both video dimensions and 4 fps. With a 1920x480 presentation
display and no driver margins, the requested stream and decoder surface buffer
are 768x192. Instrument fps remains capped by the main display preference.
The main display still defaults to 40 percent and 8 fps.

The aligned video is fitted proportionally and centered inside the existing
driver margins. A second placement option shows a smaller, native-sized image
centered in that area. The instrument test pattern uses the same geometry.
The generic cluster map-size control is hidden while EX5 routing is enabled,
because this profile has its own resolution control. Marker placement remains
editable through the existing controls.

Opening perf5 once initializes only the new instrument settings. Existing main
display, instrument margins, display selection, Wi-Fi and audio preferences
remain intact after a perf4 upgrade. Later manual changes are not reset.
Settings changes apply on the next CarPlay connection.

Audio, fixed channel selection, same-Wi-Fi mode, Bluetooth startup, low-latency
video and USB consent guards retain the perf4 behavior. Actual iPhone stream
rate and real-car responsiveness require vehicle testing.
