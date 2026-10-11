/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.jmdns.impl;

import java.io.IOException;
import java.net.MulticastSocket;
import java.net.SocketAddress;

/** Android 6 enables reuse only after a bound MulticastSocket constructor returns. */
class SharedMdnsSocket extends MulticastSocket {
    SharedMdnsSocket(SocketAddress address) throws IOException {
        super((SocketAddress) null);
        try {
            setReuseAddress(true);
            bind(address);
        } catch (IOException | RuntimeException error) {
            close();
            throw error;
        }
    }
}
