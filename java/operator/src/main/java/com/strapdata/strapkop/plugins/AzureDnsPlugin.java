package com.strapdata.strapkop.plugins;

import com.strapdata.dns.AzureDnsUpdater;

import javax.inject.Singleton;
import java.io.IOException;

@Singleton
public class AzureDnsPlugin extends DnsPlugin {

    public AzureDnsPlugin() throws IOException {
        super(new AzureDnsUpdater());
    }

}
