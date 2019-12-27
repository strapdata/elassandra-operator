package com.strapdata.dns;

import com.google.common.base.Strings;
import io.micronaut.discovery.event.ServiceStartedEvent;
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

    public abstract Completable updateDnsARecord(String name, String externalIp);

    public abstract Completable deleteDnsARecord(String name);

    public void onStart(final ServiceStartedEvent event) {
        String podName = System.getenv("POD_NAME");
        String seedHostId = System.getenv("SEED_HOST_ID");
        logger.debug("POD_NAME={} SEED_HOST_ID={} DNS_DOMAIN={}", podName, seedHostId, dnsConfiguration.domain);
        try {
            String publicIp = readFirstLine("/nodeinfo/public-ip", Charset.forName("UTF-8"));
            if (dnsConfiguration.domain != null && !Strings.isNullOrEmpty(publicIp) && seedHostId != null && podName.endsWith("-0")) {
                Throwable t = updateDnsARecord(seedHostId, publicIp).blockingGet();
                if (t != null)
                    throw t;
                logger.info("Dns updated at startup pod={} {}.{} = {}", podName, seedHostId, dnsConfiguration.domain, publicIp);
            }
        } catch (Throwable e) {
            logger.error("Failed to update DNS seed public ip:" + e.getMessage(), e);
        }
    }


    static String readFirstLine(String path, Charset encoding) throws IOException
    {
        List<String> lines = Files.readAllLines(Paths.get(path), encoding);
        return lines.size() > 0 ? lines.get(0) : null;
    }
}
