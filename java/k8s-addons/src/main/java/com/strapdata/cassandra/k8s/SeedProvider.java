package com.strapdata.cassandra.k8s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SeedProvider implements org.apache.cassandra.locator.SeedProvider {
    private static final Logger logger = LoggerFactory.getLogger(SeedProvider.class);

    private final String services;

    public SeedProvider(final Map<String, String> args) {
        services = args.get("services");
        if (services == null)
            throw new IllegalStateException(String.format("%s requires \"services\" argument.", SeedProvider.class));
    }

    @Override
    public List<InetAddress> getSeeds() {
    
        final List<InetAddress> seedAddresses = new ArrayList<>();

        for (String s : services.split(",")) {
            try {
                Collections.addAll(seedAddresses, InetAddress.getAllByName(s.trim()));
            } catch (final UnknownHostException e) {
                logger.warn("Unable to resolve k8s service {}.", s, e);
            }
        }

        logger.info("Discovered {} seed nodes: {}", seedAddresses.size(), seedAddresses);

        return seedAddresses;
    }
}
