package com.strapdata.strapkop.dns;

import com.google.common.base.Strings;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public abstract class DnsUpdater {

    private static final Logger logger = LoggerFactory.getLogger(DnsUpdater.class);

    final DnsConfiguration dnsConfiguration;

    public DnsUpdater(DnsConfiguration dnsConfiguration) {
        this.dnsConfiguration = dnsConfiguration;
    }

    public final Completable updateDnsARecord(String name, String externalIp) {
        if (dnsConfiguration.enabled) {
            return innerUpdateDnsARecord(name, externalIp);
        } else {
            return Completable.complete();
        }
    }

    public abstract Completable innerUpdateDnsARecord(String name, String externalIp);

    public final Completable deleteDnsARecord(String name) {
        if (dnsConfiguration.enabled) {
            return innerDeleteDnsARecord(name);
        } else {
            return Completable.complete();
        }
    }

    public abstract Completable innerDeleteDnsARecord(String name);

    /**
     * Add DNS seed name
     * @param rack
     */
    public void onStart(String rack) {
        if (dnsConfiguration.enabled) {
            String podName = System.getenv("POD_NAME");
            try {
                String publicIp = readFirstLine("/nodeinfo/public-ip", Charset.forName("UTF-8"));
                if (dnsConfiguration.zone != null && !Strings.isNullOrEmpty(publicIp) && podName.endsWith("-0")) {
                    String hostname = buildHostname(System.getenv("CASSANDRA_RACK"));
                    Throwable t = updateDnsARecord(hostname, publicIp).blockingGet();
                    if (t != null)
                        throw t;
                    logger.info("Dns updated on start pod={} {}.{} = {}", podName, hostname, dnsConfiguration.zone, publicIp);
                }
            } catch (Throwable e) {
                logger.error("Failed to update DNS seed public ip:" + e.getMessage(), e);
            }
        }
    }

    /**
     * Delete DNS seed name
     * @param rack
     */
    public void onStop(String rack) {
        try {
            String hostname = buildHostname(rack);
            deleteDnsARecord(hostname).blockingGet();
            logger.info("Dns delete on stop {}.{}", hostname, dnsConfiguration.zone);
        } catch (Throwable e) {
            logger.error("Failed to update DNS seed public ip:" + e.getMessage(), e);
        }
    }

    public String buildHostname(String rack) {
        String seedHostId = System.getenv("SEED_HOST_ID");
        String hostnamePrefix = System.getenv("DNS_HOSTNAME_PREFIX");
        return (!Strings.isNullOrEmpty(hostnamePrefix)) ?
                hostnamePrefix + "-" +rack :
                seedHostId;
    }

    static String readFirstLine(String path, Charset encoding) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path), encoding);
        return lines.size() > 0 ? lines.get(0) : null;
    }
}
