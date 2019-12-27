package com.strapdata.dns;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNull;


public class AzureTests {

    @Test
    public void testUpdateRecord() throws IOException {
        DnsConfiguration dnsConfiguration = new DnsConfiguration();
        dnsConfiguration.domain = "941a7aa2-kube1-azure-northeurope.azure.strapcloud.com";
        dnsConfiguration.ttl = 60;

        AzureDnsUpdater updater = new AzureDnsUpdater(dnsConfiguration,
                "55aa320e-f341-4db8-8d3b-e28d1a41cb67",
                "566af820-2f8c-45ac-b975-647d2647b277",
                "d505e34d-d231-448f-ad53-9a5eff1fe1c1",
                "72738c1b-8ae6-4f23-8531-5796fe866f2e",
                "strapcloud.com");
        Throwable t = updater.updateDnsARecord("toto","123.123.123.123").blockingGet();
        assertNull(t);
    }
}
