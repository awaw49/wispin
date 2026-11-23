package com.iap2loc.android.transport;

import java.io.Closeable;
import java.io.IOException;

/** A byte duplex that iAP2 can run over: Bluetooth RFCOMM or a TCP tunnel. */
public interface ITransport extends Closeable {
    String describe();

    /** Blocking read; returns -1/0 when the peer closed. */
    int read(byte[] buffer, int offset, int count) throws IOException;

    void write(byte[] buffer, int offset, int count) throws IOException;

    @Override
    void close() throws IOException;
}
