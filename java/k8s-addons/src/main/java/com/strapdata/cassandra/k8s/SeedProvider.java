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
public class SeedProvider implements org.apache.cassandra.locator.SeedProvider {
    private static final Logger logger = LoggerFactory.getLogger(SeedProvider.class);

    /**
     * List of comma separated hostnames or ip address.
     */
    private final String[] seeds;

    /**
     * List of URL to get current seeds (broadcast_address) from remote strapkop.
     */
    private final String[] seeders;

    private Set<InetAddress> nodeInfoSeeds;

    public SeedProvider(final Map<String, String> args) {
        seeds = args.get("seeds") == null ? new String[0] : args.get("seeds").split(", ");
        seeders = args.get("seeders") == null ? new String[0] : args.get("seeders").split(", ");

        logger.info("seeds={} seeders={}", Arrays.toString(seeds), Arrays.toString(seeders));

        // hugly hack to set the JVM truststore to the cassandra one, the seeder url can pass PKIX validation.
        logger.info("Setting javax.net.ssl.trustStore={}", DatabaseDescriptor.getClientEncryptionOptions().keystore);
        System.setProperty("javax.net.ssl.trustStore",  DatabaseDescriptor.getClientEncryptionOptions().keystore);
        System.setProperty("javax.net.ssl.trustStorePassword", DatabaseDescriptor.getClientEncryptionOptions().keystore_password);

        // lookup seeds pod DNS name to node IP because seeds must be broadcast_addresses
        String seedsIps = System.getenv("SEEDS_IP");
        if (seedsIps != null && !seedsIps.isEmpty()) {
            nodeInfoSeeds = new HashSet<>();
            for(String s : seedsIps.split(" ")) {
                nodeInfoSeeds.add(InetAddresses.forString(s.trim()));
            }
        }
        logger.info("nodeinfo SEEDS_IP="+nodeInfoSeeds);
    }

    @Override
    public List<InetAddress> getSeeds() {
    
        final List<InetAddress> seedAddresses = new ArrayList<>();

        if (nodeInfoSeeds != null) {
            // add /nodeinfo/seeds-ip
            seedAddresses.addAll(nodeInfoSeeds);
        } else if (seeds != null) {
            // fallbacks to the provided seeds
            for (String s : seeds) {
                try {
                    Collections.addAll(seedAddresses, InetAddress.getAllByName(s.trim()));
                } catch (final UnknownHostException e) {
                    logger.warn("Unable to resolve k8s service {}.", s, e);
                }
            }
        }

        if (seeders != null) {
            for (String url : seeders) {
                try {
                    logger.debug("fetching remote seeds from={}", url);
                    seedAddresses.addAll(seederCall(url));
                } catch (final UnknownHostException e) {
                    logger.warn("Unable to resolve k8s service=" + url, e);
                } catch (final Exception e) {
                    logger.warn("Unable to fetch seeds from url=" + url, e);
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
            conn.setRequestProperty("Metadata-Flavor", "Strapdata");
            if (conn.getResponseCode() != 200)
                throw new ConfigurationException("StrapkopSnitchProvider was unable to execute the API call code="+conn.getResponseCode()+" reason="+conn.getResponseMessage());

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
