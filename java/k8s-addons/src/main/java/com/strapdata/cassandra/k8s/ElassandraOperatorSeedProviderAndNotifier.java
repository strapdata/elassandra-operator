package com.strapdata.cassandra.k8s;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.gms.*;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.FBUtilities;
import org.jctools.queues.MpscArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Catch gossip events and push it asynchronously to the Elassandra operator.
 */
public class ElassandraOperatorSeedProviderAndNotifier extends ElassandraOperatorSeedProvider implements IEndpointStateChangeSubscriber {

    private static final Logger logger = LoggerFactory.getLogger(ElassandraOperatorSeedProviderAndNotifier.class);

    public static final String STATUS_NOTIFIER_URL = "cassandra.status_notifier_url";
    public static int MAX_QUEUE_SIZE = 64;

    String urlFormat;
    String localDc;
    final ConcurrentMap<InetAddress, String> endpointStatus;
    final MpscArrayQueue<InetAddress> queue;
    final ExecutorService executorService;

    public ElassandraOperatorSeedProviderAndNotifier(final Map<String, String> args) {
        super(args);
        this.localDc = DatabaseDescriptor.getEndpointSnitch().getDatacenter(FBUtilities.getBroadcastAddress());

        // baseURL = http://operator/namespace
        String baseUrl = System.getProperty(STATUS_NOTIFIER_URL);
        if (baseUrl == null) {
            this.endpointStatus = null;
            this.queue = null;
            this.executorService = null;
        } else {
            if (!baseUrl.endsWith("/"))
                baseUrl += "/";
            this.urlFormat = baseUrl + "%s/%s";
            logger.info("Status notifier url format={}", urlFormat);

            this.endpointStatus = new ConcurrentHashMap<>();
            this.queue = new MpscArrayQueue<>(MAX_QUEUE_SIZE);
            this.executorService = Executors.newFixedThreadPool(1);
            this.executorService.submit(new Consumer());

            register();
        }
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
        if (epState.getApplicationState(ApplicationState.STATUS) != null) {
            String status = epState.getApplicationState(ApplicationState.STATUS).value;
            if (status != null)
                publish(endpoint, status);
        }
    }

    @Override
    public void beforeChange(InetAddress endpoint, EndpointState currentState, ApplicationState newStateKey, VersionedValue newValue) {

    }

    @Override
    public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value) {

    }

    @Override
    public void onAlive(InetAddress endpoint, EndpointState state) {
        if (state.getApplicationState(ApplicationState.STATUS) != null) {
            String status = state.getApplicationState(ApplicationState.STATUS).value;
            if (status != null)
                publish(endpoint, status);
        }
    }

    @Override
    public void onDead(InetAddress endpoint, EndpointState state) {
        if (state.getApplicationState(ApplicationState.STATUS) != null) {
            String status = state.getApplicationState(ApplicationState.STATUS).value;
            if (status != null)
                publish(endpoint, status);
        }
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
        if (state.getApplicationState(ApplicationState.STATUS) != null) {
            String status = state.getApplicationState(ApplicationState.STATUS).value;
            if (status != null)
                publish(endpoint, status);
        }
    }

    void publish(InetAddress endpoint, String status) {
        if (this.localDc.equals(DatabaseDescriptor.getEndpointSnitch().getDatacenter(endpoint))) {
            String prevStatus = endpointStatus.put(endpoint, status);
            if (!Objects.equals(status, prevStatus)) {
                queue.offerIfBelowThreshold(endpoint, MAX_QUEUE_SIZE);
            }
        }
    }


    class Consumer implements Runnable {

        /**
         * When an object implementing interface <code>Runnable</code> is used
         * to create a thread, starting the thread causes the object's
         * <code>run</code> method to be called in that separately executing
         * thread.
         * <p>
         * The general contract of the method <code>run</code> is that it may
         * take any action whatsoever.
         *
         * @see Thread#run()
         */
        @Override
        public void run() {
            while(true) {
                try {
                    InetAddress inetAddress = queue.poll();
                    sendStatus(inetAddress, endpointStatus.get(inetAddress));
                } catch (IOException | ConfigurationException e) {
                    logger.warn("Failed to notify status", e);
                }
            }
        }

        void sendStatus(InetAddress endpoint, String status) throws IOException, ConfigurationException
        {
            URL url = null;
            try {
                url = new URL(String.format(Locale.ROOT, urlFormat, endpoint.getHostAddress(), status));
            } catch(Exception e) {
                logger.error("init failed:", e);
                return;
            }

            // Populate the region and zone by introspection, fail if 404 on metadata
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            DataInputStream d = null;
            try
            {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Metadata-Flavor", "elassandra-operator-notifier");
                if (conn.getResponseCode() != 200)
                    throw new ConfigurationException("ElassandraOperatorStatusNotifier was unable to execute the API call code="+conn.getResponseCode()+" reason="+conn.getResponseMessage());
                logger.debug("Status sent for endpoint={} status={}", endpoint, status);
            }
            finally
            {
                FileUtils.close(d);
                conn.disconnect();
            }
        }
    }


}
