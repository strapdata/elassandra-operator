package com.strapdata.strapkop.sidecar.services;


import com.strapdata.strapkop.dns.AzureDnsUpdater;
import com.strapdata.strapkop.dns.DnsConfiguration;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceStartedEvent;

import javax.inject.Singleton;
import java.io.IOException;

/**
 * Manage public DNS records on Azure
 */
@Singleton
public class DnsService extends AzureDnsUpdater implements ApplicationEventListener<ServiceStartedEvent> {

    public DnsService(DnsConfiguration dnsConfiguration) throws IOException {
        super(dnsConfiguration);
    }

    // trigger dns update on startup
    public void onApplicationEvent(final ServiceStartedEvent event) {
        onStart(System.getenv("CASSANDRA_RACK"));
    }
}

