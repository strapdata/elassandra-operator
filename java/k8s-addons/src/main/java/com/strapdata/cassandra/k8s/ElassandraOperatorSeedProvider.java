package com.strapdata.cassandra.k8s;

import com.google.common.net.InetAddresses;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.util.FileUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides Cassandra seed addresses, usually one per rack.
 */
public class ElassandraOperatorSeedProvider implements org.apache.cassandra.locator.SeedProvider {
    private static final Logger logger = LoggerFactory.getLogger(ElassandraOperatorSeedProvider.class);

    /**
     * List of comma separated hostnames or ip address.
     */
    private String[] seeds = null;


    private String[] remoteSeeds = null;

    /**
     * List of URL to get current seeds (broadcast_address) from remote strapkop.
     */
    private final String[] remoteSeeders;

    private Set<InetAddress> nodeInfoSeeds;

    public ElassandraOperatorSeedProvider(final Map<String, String> args) {
        seeds = getParameter(args, "seeds", "SEEDS");
        remoteSeeds = getParameter(args, "remote_seeds", "REMOTE_SEEDS");
        remoteSeeders = getParameter(args, "remote_seeders", "REMOTE_SEEDERS");

        // hugly hack to set the JVM truststore to the cassandra one, the seeder url can pass PKIX validation.
        logger.info("Setting javax.net.ssl.trustStore={}", DatabaseDescriptor.getClientEncryptionOptions().keystore);
        System.setProperty("javax.net.ssl.trustStore",  DatabaseDescriptor.getClientEncryptionOptions().keystore);
        System.setProperty("javax.net.ssl.trustStorePassword", DatabaseDescriptor.getClientEncryptionOptions().keystore_password);
    }

    private String[] getParameter(final Map<String, String> args, String paramName, String envVarName) {
        if (args.get(paramName) != null) {
            return args.get(paramName).trim().split(", ");
        }
        if (System.getenv(envVarName) != null) {
            return System.getenv(envVarName).trim().split(", ");
        }
        return new String[0];
    }

    @Override
    public List<InetAddress> getSeeds() {
    
        final List<InetAddress> seedAddresses = new ArrayList<>();

        if (seeds.length == 0 && remoteSeeds.length == 0 && remoteSeeders.length == 0) {
            // fallback to the local broadcast address for the first node
            seeds = new String[] { InetAddresses.toAddrString(DatabaseDescriptor.getBroadcastAddress()) };
        }

        logger.info("seeds={} remote_seeds={} remote_seeders={}", Arrays.toString(seeds), Arrays.toString(remoteSeeds), Arrays.toString(remoteSeeders));

        for (String s : seeds) {
            try {
                Collections.addAll(seedAddresses, InetAddress.getAllByName(s.trim()));
            } catch (final UnknownHostException e) {
                logger.warn("Unable to resolve k8s service {}.", s, e);
            }
        }

        for (String s : remoteSeeds) {
            try {
                Collections.addAll(seedAddresses, InetAddress.getAllByName(s.trim()));
            } catch (final UnknownHostException e) {
                logger.warn("Unable to resolve k8s service {}.", s, e);
            }
        }

        for (String url : remoteSeeders) {
            if (!url.trim().isEmpty()) {
                try {
                    logger.debug("fetching remote seeds from=[{}]", url.trim());
                    seedAddresses.addAll(seederCall(url.trim()));
                } catch (final UnknownHostException e) {
                    logger.warn("Unable to resolve k8s service=[" + url + "]", e);
                } catch (final Exception e) {
                    logger.warn("Unable to fetch seeds from url=[" + url + "]", e);
                }
            }
        }

        logger.info("Discovered {} seed nodes: {}", seedAddresses.size(), seedAddresses);
        return seedAddresses;
    }

    public static List<InetAddress> seederCall(String url) throws IOException, ConfigurationException
    {
        // Populate the region and zone by introspection, fail if 404 on metadata
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        DataInputStream d = null;
        try
        {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Metadata-Flavor", "elassandra-operator");
            if (conn.getResponseCode() != 200)
                throw new ConfigurationException("ElassandraOperatorSeedProvider was unable to execute the API call code="+conn.getResponseCode()+" reason="+conn.getResponseMessage());

            // Read the information.
            int cl = conn.getContentLength();
            byte[] b = new byte[cl];
            d = new DataInputStream((FilterInputStream) conn.getContent());
            d.readFully(b);
            String response = new String(b, StandardCharsets.UTF_8);
            logger.debug("response={}", response);

            ObjectMapper mapper = new ObjectMapper();
            List<String> seeds = mapper.readValue(response, new TypeReference<ArrayList<String>>() {});
            return seeds.stream().map(InetAddresses::forString).collect(Collectors.toList());
        }
        finally
        {
            FileUtils.close(d);
            conn.disconnect();
        }
    }
}
