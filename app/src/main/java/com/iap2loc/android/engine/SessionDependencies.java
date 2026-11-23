package com.iap2loc.android.engine;

import com.iap2loc.android.protocol.LocationTarget;
import com.iap2loc.android.protocol.MfiSigner;
import com.iap2loc.android.transport.ITransport;

/** Platform resources are injected; the session lifecycle itself is plain Java. */
public final class SessionDependencies {
    public interface IdentityLoader { MfiSigner load() throws Exception; }
    public interface ConnectionFactory {
        Connection open(RunRequest request, CancellationScope scope) throws Exception;
    }
    public static final class Connection {
        public final ITransport transport;
        public final byte[] adapter;
        public Connection(ITransport transport, byte[] adapter) {
            this.transport = transport;
            this.adapter = adapter == null ? null : adapter.clone();
        }
    }
    public final LocationTarget target;
    public final IdentityLoader identity;
    public final ConnectionFactory connections;

    public SessionDependencies(LocationTarget target, IdentityLoader identity,
                               ConnectionFactory connections) {
        this.target = target;
        this.identity = identity;
        this.connections = connections;
    }
}
