package com.strapdata.cassandra.k8s;

import io.vavr.Tuple2;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.gms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URL;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Catch gossip events and push it asynchronously to strapkop.
 */
public class StateChangeNotifier implements IEndpointStateChangeSubscriber {

    private static final Logger logger = LoggerFactory.getLogger(StateChangeNotifier.class);

    public static final StateChangeNotifier instance = new StateChangeNotifier();

    public final Queue<Tuple2<UUID, String>> queue = new ArrayBlockingQueue<>(64, true);
    public final UUID myHostId;
    public final URL url;

    public StateChangeNotifier() {
        register();
        this.myHostId = SystemKeyspace.getLocalHostId();

        URL url = null;
        try {
            url = new URL(System.getProperty("strapkop.endpoint"));
        } catch(Exception e) {
            logger.error("init failed:", e);
        }
        this.url = url;
    }

    public void register() {
        Gossiper.instance.register(this);
    }

    public void unregister() {
        Gossiper.instance.unregister(this);
    }

    /**
     * Use to inform interested parties about the change in the state
     * for specified endpoint
     *
     * @param endpoint endpoint for which the state change occurred.
     * @param epState  state that actually changed for the above endpoint.
     */
    @Override
    public void onJoin(InetAddress endpoint, EndpointState epState) {

    }

    @Override
    public void beforeChange(InetAddress endpoint, EndpointState currentState, ApplicationState newStateKey, VersionedValue newValue) {

    }

    @Override
    public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value) {

    }

    @Override
    public void onAlive(InetAddress endpoint, EndpointState state) {

    }

    @Override
    public void onDead(InetAddress endpoint, EndpointState state) {

    }

    @Override
    public void onRemove(InetAddress endpoint) {

    }

    /**
     * Called whenever a node is restarted.
     * Note that there is no guarantee when that happens that the node was
     * previously marked down. It will have only if {@code state.isAlive() == false}
     * as {@code state} is from before the restarted node is marked up.
     *
     * @param endpoint
     * @param state
     */
    @Override
    public void onRestart(InetAddress endpoint, EndpointState state) {

    }
}
