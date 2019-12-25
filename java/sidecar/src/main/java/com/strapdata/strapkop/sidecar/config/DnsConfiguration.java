package com.strapdata.strapkop.sidecar.config;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("dns")
public class DnsConfiguration {

    public String secretName;

    public String resourceGroup = "strapcloud.com";
    public String domain = "azure.strapcloud.com";
    public int ttl = 300;

}

