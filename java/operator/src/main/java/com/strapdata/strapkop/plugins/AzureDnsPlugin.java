package com.strapdata.strapkop.plugins;

import com.strapdata.dns.AzureDnsUpdater;
import com.strapdata.dns.DnsConfiguration;

import javax.inject.Singleton;
import java.io.IOException;

@Singleton
public class AzureDnsPlugin extends DnsPlugin {

    public AzureDnsPlugin(DnsConfiguration dnsConfiguration) throws IOException {
        super(new AzureDnsUpdater(dnsConfiguration));
    }

}
