package com.strapdata.strapkop.sidecar.services;


import com.strapdata.dns.AzureDnsUpdater;
import com.strapdata.strapkop.sidecar.SidecarConfiguration;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceStartedEvent;

import javax.inject.Singleton;
import java.io.IOException;

/**
 * Manage public DNS records on Azure
 */
@Singleton
public class DnsService extends AzureDnsUpdater implements ApplicationEventListener<ServiceStartedEvent> {

    public DnsService(SidecarConfiguration sidecarConfiguration) throws IOException {
        super(sidecarConfiguration.dnsDomain, sidecarConfiguration.dnsTtl);
    }

}

