package com.strapdata.cassandra.k8s;

import com.google.common.net.InetAddresses;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.EncryptionOptions;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.security.SSLFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
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
     * Internal elassandra operator URL accessible in the kubernetes cluster;
     */
    private String internalSeeder = null;

    /**
     * External elassandra operator URL accessible from remote kubernetes clusters;
     */
    private String externalSeeder = null;

    /**
     * List of URL to get current seeds (broadcast_address) from remote strapkop.
     */
    private final String[] remoteSeeders;

    private Set<InetAddress> nodeInfoSeeds;

    private EncryptionOptions encryptionOptions;

    public ElassandraOperatorSeedProvider(final Map<String, String> args) {
        seeds = getParameter(args, "seeds", "SEEDS");
        remoteSeeds = getParameter(args, "remote_seeds", "REMOTE_SEEDS");
        remoteSeeders = getParameter(args, "remote_seeders", "REMOTE_SEEDERS");
        internalSeeder = getSingleParameter(args, "internal_seeders", "INTERNAL_SEEDERS", null);
        externalSeeder = getSingleParameter(args, "external_seeders", "EXTERNAL_SEEDERS", null);

        this.encryptionOptions = new EncryptionOptions.ClientEncryptionOptions();
        EncryptionOptions.ClientEncryptionOptions cassandraEncryptionOptions = DatabaseDescriptor.getClientEncryptionOptions();
        this.encryptionOptions.keystore = getSingleParameter(args, "keystore", "SEEDER_KEYSTORE", cassandraEncryptionOptions.keystore);
        this.encryptionOptions.keystore_password = getSingleParameter(args, "keystore_password", "SEEDER_KEYSTORE_PASSWORD", cassandraEncryptionOptions.keystore_password);
        this.encryptionOptions.truststore = getSingleParameter(args, "truststore", "SEEDER_TRUSTSTORE", cassandraEncryptionOptions.truststore);
        this.encryptionOptions.truststore_password = getSingleParameter(args, "truststore_password", "SEEDER_TRUSTSTORE_PASSWORD", cassandraEncryptionOptions.truststore_password);
        this.encryptionOptions.store_type = getSingleParameter(args, "store_type", "SEEDER_STORE_TYPE", cassandraEncryptionOptions.store_type);
        this.encryptionOptions.algorithm = getSingleParameter(args, "algorithm", "SEEDER_ALGORITHM", cassandraEncryptionOptions.algorithm);
        this.encryptionOptions.protocol = getSingleParameter(args, "protocol", "SEEDER_PROTOCOL", cassandraEncryptionOptions.protocol);

        try {
            // disable DNS negative caching
            java.security.Security.setProperty("networkaddress.cache.negative.ttl" , "0");
        } catch(SecurityException e) {
            logger.warn("Failed to disable DNS negative caching", e);
        }
    }

    public String[] getParameter(final Map<String, String> args, String paramName, String envVarName) {
        if (args.get(paramName) != null) {
            return args.get(paramName).trim().split(", ");
        }
        if (System.getenv(envVarName) != null) {
            return System.getenv(envVarName).trim().split(", ");
        }
        return new String[0];
    }

    public String getSingleParameter(final Map<String, String> args, String paramName, String envVarName, String defaultValue) {
        if (args.get(paramName) != null) {
            return args.get(paramName);
        }
        if (System.getenv(envVarName) != null) {
            return System.getenv(envVarName);
        }
        if (defaultValue != null) {
            return defaultValue;
        }
        return null;
    }

    @Override
    public List<InetAddress> getSeeds() {

        final List<InetAddress> seedAddresses = new ArrayList<>();

        logger.info("seeds={} remote_seeds={} remote_seeders={}", Arrays.toString(seeds), Arrays.toString(remoteSeeds), Arrays.toString(remoteSeeders));

        String replacePodName = System.getenv("REPLACE_POD_NAME");
        for (String s : seeds) {
            // filter out seed that are replaced (required with cassandra.replace_address_first_boot)
            if (replacePodName == null || !s.startsWith(replacePodName)) {
                try {
                    Collections.addAll(seedAddresses, InetAddress.getAllByName(s.trim()));
                } catch (final UnknownHostException e) {
                    logger.warn("Unable to resolve k8s service {}.", s, e);
                }
            }
        }

        String podName = System.getenv("POD_NAME");
        if (seeds.length == 0 && podName != null && podName.endsWith("-0")) {
            logger.debug("Add broadcast_address={}", DatabaseDescriptor.getBroadcastAddress());
            seedAddresses.add(DatabaseDescriptor.getBroadcastAddress());
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
                    String remoteSeeder = url.trim();
                    String localSeeder = remoteSeeder.equals(internalSeeder) ? internalSeeder : remoteSeeder;
                    logger.debug("remoteSeeder=[{}] localSeeder=[{}]", remoteSeeder, localSeeder);
                    seedAddresses.addAll(seederCall(url.trim(), this.encryptionOptions, localSeeder));
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

    public static List<InetAddress> seederCall(String url, String localSeeder) throws IOException, ConfigurationException {
        return seederCall(url, null);
    }

    public static List<InetAddress> seederCall(String url, EncryptionOptions encryptionOptions, String localSeeder) throws IOException, ConfigurationException
    {
        // Populate the region and zone by introspection, fail if 404 on metadata
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        if(conn instanceof HttpsURLConnection && encryptionOptions != null) {
            try {
                SSLContext sslCtx = SSLFactory.createSSLContext(encryptionOptions, true);
                SSLSocketFactory sslSF = sslCtx.getSocketFactory();
                ((HttpsURLConnection) conn).setSSLSocketFactory(sslSF);
            } catch(Exception e) {
                logger.error("Failed to build seeder SSLContext:", e);
            }
        }
        DataInputStream d = null;
        try
        {
            conn.setRequestMethod("POST");
            conn.addRequestProperty("Content-Type", "text/plain");
            conn.addRequestProperty("Metadata-Flavor", "elassandra-operator-seed-provider");
            conn.setDoOutput(true);
            PrintWriter writer = new PrintWriter(conn.getOutputStream());
            writer.println(localSeeder);
            writer.flush();
            writer.close();

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
