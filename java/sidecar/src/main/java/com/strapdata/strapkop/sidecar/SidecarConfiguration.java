package com.strapdata.strapkop.sidecar;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;

@ConfigurationProperties("sidecar")
public class SidecarConfiguration {

    DnsConfig dns = new DnsConfig();

    @Getter
    @ConfigurationProperties("dns")
    public static class DnsConfig {

        boolean enabled;

        String zone;

        int ttl;

        String azureSecretName;
    }

}

