/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.cassandra.driver;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.policies.AddressTranslator;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Enumeration;
import java.util.Hashtable;

/**
 * {@link AddressTranslator} implementation for a multi-region kubernetes deployment <b>where clients are
 * also deployed in the same network as kubernetes nodes or in the same kubernetes cluster</b>.

 *
 * This optimizes network costs, because CSP charges more for communication over public IPs.
 *
 * <p>
 *
 * <p>Implementation note: this class performs a reverse DNS lookup of the origin address, to find
 * the domain name of the target instance. Then it performs a forward DNS lookup of the hostname (not the domain name);
 * The hostname is then resolved to the kubernetes node IP address by the internal kubernetes DNS service.
 */
public class KubernetesDnsAddressTranslator implements AddressTranslator {

    private static final Logger logger =
            LoggerFactory.getLogger(KubernetesDnsAddressTranslator.class);

    // TODO when we switch to Netty 4.1, we can replace this with the Netty built-in DNS client
    private final DirContext ctx;

    private final String dnsDomain;

    public KubernetesDnsAddressTranslator() {
        Hashtable<Object, Object> env = new Hashtable<Object, Object>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        try {
            ctx = new InitialDirContext(env);
        } catch (NamingException e) {
            throw new DriverException("Could not create translator", e);
        }
        this.dnsDomain = System.getenv("ADDRESS_TRANSLATOR_DNS_DOMAIN");
    }

    @VisibleForTesting
    KubernetesDnsAddressTranslator(DirContext ctx) {
        this.ctx = ctx;
        this.dnsDomain = System.getenv("ADDRESS_TRANSLATOR_DNS_DOMAIN");
    }

    @Override
    public void init(Cluster cluster) {
        // nothing to do
    }

    @Override
    public InetSocketAddress translate(InetSocketAddress socketAddress) {
        InetAddress address = socketAddress.getAddress();

        // do not translate RFC1918 addresses
        if (address.isSiteLocalAddress())
            return socketAddress;

        if (dnsDomain != null && address instanceof Inet4Address) {
            // try to resolv internal IPv4 address by resolving the external address to X-X-X-X.$ADDRESS_TRANSLATOR_DNS_DOMAIN
            String dnsName = address.getHostAddress().replace(".","-") + "." + dnsDomain;
            try {
                InetAddress translatedAddress = InetAddress.getByName(dnsName);
                return new InetSocketAddress(translatedAddress, socketAddress.getPort());
            } catch(java.net.UnknownHostException e) {
                logger.warn("Cannot resolv " + dnsName + ", fallback to revers resolution");
            } catch (Exception e) {
                logger.warn("Error resolving " + address + ", returning it as-is", e);
                return socketAddress;
            }
        }

        try {
            // InetAddress#getHostName() is supposed to perform a reverse DNS lookup, but for some reason
            // it doesn't work
            // within the same EC2 region (it returns the IP address itself).
            // We use an alternate implementation:
            String domainName = lookupPtrRecord(reverse(address));
            if (domainName == null) {
                logger.warn("Found no domain name for {}, returning it as-is", address);
                return socketAddress;
            }

            String hostname = domainName.substring(0, domainName.indexOf("."));
            InetAddress translatedAddress = InetAddress.getByName(hostname);
            logger.debug("Resolved {} to {}", address, translatedAddress);
            return new InetSocketAddress(translatedAddress, socketAddress.getPort());
        } catch(javax.naming.NameNotFoundException e) {
            logger.warn("Cannot resolv " + address + ", returning it as-is");
            return socketAddress;
        } catch (Exception e) {
            logger.warn("Error resolving " + address + ", returning it as-is", e);
            return socketAddress;
        }
    }

    private String lookupPtrRecord(String reversedDomain) throws Exception {
        Attributes attrs = ctx.getAttributes(reversedDomain, new String[] {"PTR"});
        for (NamingEnumeration ae = attrs.getAll(); ae.hasMoreElements(); ) {
            Attribute attr = (Attribute) ae.next();
            for (Enumeration<?> vals = attr.getAll(); vals.hasMoreElements(); )
                return vals.nextElement().toString();
        }
        return null;
    }

    @Override
    public void close() {
        try {
            ctx.close();
        } catch (NamingException e) {
            logger.warn("Error closing translator", e);
        }
    }

    // Builds the "reversed" domain name in the ARPA domain to perform the reverse lookup
    @VisibleForTesting
    static String reverse(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return reverseIpv4(bytes);
        else return reverseIpv6(bytes);
    }

    private static String reverseIpv4(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (int i = bytes.length - 1; i >= 0; i--) {
            builder.append(bytes[i] & 0xFF).append('.');
        }
        builder.append("in-addr.arpa");
        return builder.toString();
    }

    private static String reverseIpv6(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (int i = bytes.length - 1; i >= 0; i--) {
            byte b = bytes[i];
            int lowNibble = b & 0x0F;
            int highNibble = b >> 4 & 0x0F;
            builder
                    .append(Integer.toHexString(lowNibble))
                    .append('.')
                    .append(Integer.toHexString(highNibble))
                    .append('.');
        }
        builder.append("ip6.arpa");
        return builder.toString();
    }
}