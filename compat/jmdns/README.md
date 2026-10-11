# Android multicast socket compatibility

This module replaces `org.jmdns:jmdns:3.6.3` through dependency substitution.
It contains the exact Maven source JAR, recorded with hashes in `upstream.json`,
plus `SharedMdnsSocket` and one changed constructor call in `JmDNSImpl`.

Android 6 `MulticastSocket(SocketAddress)` calls `super(localAddress)` before
`setReuseAddress(true)`. This attempts an exclusive bind even when the existing
system discovery service allows sharing UDP 5353. IPv4 alone therefore still
fails with EADDRINUSE when another service has already opened that port.

`SharedMdnsSocket` constructs an unbound socket, explicitly enables reuse, and
then binds. It closes the newly created socket if binding fails. It does not
change multicast groups, network interface selection, TTL, ports, or the
wireless IPv4-only policy, and does not stop the system discovery service.

The Android 6 instrumentation keeps a reusable UDP 5353 socket open, reproduces
the original constructor failure, and then starts/stops IPv4 Bonjour three times
while that socket remains open. Host-side tests check sharing, port release and
failed-construction cleanup.
