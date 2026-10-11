package javax.jmdns.impl;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import org.junit.Test;
import static org.junit.Assert.*;

public class SharedMdnsSocketTest {
    @Test public void sharesTheExistingReusableDiscoveryPort() throws Exception {
        try (MulticastSocket existing = new MulticastSocket((java.net.SocketAddress) null)) {
            existing.setReuseAddress(true);
            existing.bind(new InetSocketAddress(0));
            try (SharedMdnsSocket accessory = new SharedMdnsSocket(new InetSocketAddress(existing.getLocalPort()))) {
                assertTrue(accessory.getReuseAddress());
                assertEquals(existing.getLocalPort(), accessory.getLocalPort());
                assertFalse(existing.isClosed());
            }
            assertFalse(existing.isClosed());
        }
    }

    @Test public void releasesThePortAfterBothParticipantsClose() throws Exception {
        int port;
        try (SharedMdnsSocket first = new SharedMdnsSocket(new InetSocketAddress(0))) {
            port = first.getLocalPort();
            try (SharedMdnsSocket second = new SharedMdnsSocket(new InetSocketAddress(port))) {
                assertEquals(port, second.getLocalPort());
            }
        }
        try (DatagramSocket exclusive = new DatagramSocket((java.net.SocketAddress) null)) {
            exclusive.setReuseAddress(false);
            exclusive.bind(new InetSocketAddress(port));
            assertEquals(port, exclusive.getLocalPort());
        }
    }

    private static class FailingSocket extends SharedMdnsSocket {
        static FailingSocket failed;
        FailingSocket() throws IOException { super(new InetSocketAddress(0)); }
        @Override public void bind(java.net.SocketAddress address) throws java.net.SocketException {
            failed = this;
            throw new java.net.SocketException("simulated bind failure");
        }
    }

    @Test public void closesTheNewSocketWhenBindingFails() {
        FailingSocket.failed = null;
        assertThrows(IOException.class, FailingSocket::new);
        assertNotNull(FailingSocket.failed);
        assertTrue(FailingSocket.failed.isClosed());
    }
}
