package com.strapdata.cassandra.driver;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.policies.AddressTranslator;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scan DNS names to build a map of pub IP -> Priv name
 * Private name: elassandra-clusterName-datacenterName-rackIndex-podIndex.serviceName.namespace.svc.cluster.local
 * Public name: cassandra-[externalDns.root]-rackIndex-podIndex.[externalDns.domain]
 *
 * CASSANDRA_TRANSLATOR_INTERNAL=
 * CASSANDRA_TRANSLATOR_EXTERNAL=
 */
public class ElassandraOperatorAddressTranslator implements AddressTranslator {

    Logger logger = Logger.getLogger(ElassandraOperatorAddressTranslator.class.getName());

    Map<InetAddress, String> pubInetToPrvName = new ConcurrentHashMap<>();

    public static final Pattern fqdnPattern = Pattern.compile("(.*)\\-({\\d}+)\\-({\\d}+)\\.(.*)");

    final Matcher internalMatcher;
    final Matcher externalMatcher;

    public ElassandraOperatorAddressTranslator() {
        String CASSANDRA_TRANSLATOR_INTERNAL = System.getenv("CASSANDRA_TRANSLATOR_INTERNAL");
        String CASSANDRA_TRANSLATOR_EXTERNAL = System.getenv("CASSANDRA_TRANSLATOR_EXTERNAL");

        if (CASSANDRA_TRANSLATOR_INTERNAL == null) {
            throw new IllegalArgumentException("ElassandraOperatorAddressTranslator not configured, please configure environment variables CASSANDRA_TRANSLATOR_INTERNAL.");
        }
        if (CASSANDRA_TRANSLATOR_EXTERNAL == null) {
            throw new IllegalArgumentException("ElassandraOperatorAddressTranslator not configured, please configure environment variables CASSANDRA_TRANSLATOR_EXTERNAL.");
        }

        this.internalMatcher = fqdnPattern.matcher(CASSANDRA_TRANSLATOR_INTERNAL);
        this.externalMatcher = fqdnPattern.matcher(CASSANDRA_TRANSLATOR_EXTERNAL);

        if (!internalMatcher.matches()) {
            throw new IllegalArgumentException("CASSANDRA_TRANSLATOR_INTERNAL="+CASSANDRA_TRANSLATOR_INTERNAL+" does not match the expected format");
        }
        if (!externalMatcher.matches()) {
            throw new IllegalArgumentException("CASSANDRA_TRANSLATOR_EXTERNAL="+CASSANDRA_TRANSLATOR_EXTERNAL+" does not match the expected format");
        }
    }

    /**
     * Scan available internal DNS names to figure out what public names are,
     * and resolve public IP to populate the map.
     */
    public void scan() {
        int rack = 0;
        int pod = 0;
        while (true) {
            String publicName = externalMatcher.group(1) + "-" + rack + "-" + externalMatcher.group(4);
            InetAddress publicIp = resolve(publicName);
            if (publicIp != null) {
                String privateName = internalMatcher.group(1) + "-" + rack + "-" + internalMatcher.group(4);
                pubInetToPrvName.put(publicIp, privateName);
                logger.fine("Adding pubIp="+publicIp+" prvName="+ privateName);
            } else {
                if (pod == 0) {
                    // No node in this rack => stop
                    break;
                }
                rack++;
                pod = 0;
            }
            pod++;
        }
        logger.finest("scan="+pubInetToPrvName);
    }

    InetAddress resolve(String name) {
        try {
            return InetAddress.getByName(name);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * Initializes this address translator.
     *
     * @param cluster the {@code Cluster} instance for which the translator is created.
     */
    @Override
    public void init(Cluster cluster) {
        scan();
    }

    /**
     * Translates a Cassandra {@code rpc_address} to another address if necessary.
     *
     * @param address the address of a node as returned by Cassandra. Note that if the {@code
     *                rpc_address} of a node has been configured to {@code 0.0.0.0} server side, then the
     *                provided address will be the node {@code listen_address}, *not* {@code 0.0.0.0}. Also note
     *                that the port for {@code InetSocketAddress} will always be the one set at Cluster
     *                construction time (9042 by default).
     * @return the address the driver should actually use to connect to the node designated by {@code
     * address}. If the return is {@code null}, then {@code address} will be used by the driver
     * (it is thus equivalent to returning {@code address} directly)
     */
    @Override
    public InetSocketAddress translate(InetSocketAddress address) {
        String internalName = pubInetToPrvName.get(address.getAddress());
        if (internalName == null) {
            scan(); // rescan to catch modification
            internalName = pubInetToPrvName.get(address.getAddress());
        }
        if (internalName != null) {
            InetAddress internalIp = resolve(internalName);
            if (internalIp != null) {
                logger.finer("pubIp="+address.getAddress()+" resolved="+internalIp.getHostAddress());
                return new InetSocketAddress(internalIp, address.getPort());
            }
        }
        logger.warning("pubIp="+address.getAddress()+" not resolved to an internal ip.");
        return address;
    }

    /**
     * Called at {@link Cluster} shutdown.
     */
    @Override
    public void close() {

    }

    public static void main(String[] args) {
        try {
            ElassandraOperatorAddressTranslator elassandraOperatorAddressTranslator = new ElassandraOperatorAddressTranslator();
            elassandraOperatorAddressTranslator.scan();
            System.out.println(elassandraOperatorAddressTranslator.pubInetToPrvName);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
}
