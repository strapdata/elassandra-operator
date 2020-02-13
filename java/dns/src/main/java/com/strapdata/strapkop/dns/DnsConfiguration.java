package com.strapdata.strapkop.dns;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;

@ConfigurationProperties("dns")
@Getter
public class DnsConfiguration {

    boolean enabled = false;

    /**
     * DNS domain name when deploying ingress for plugins and registering DNS record for seed nodes.
     */
    String zone;

    /**
     * DNS ttl when registering DNS record for seed nodes
     */
    int ttl;

    /**
     * Azure secret containing a service principal to update DNS.
     */
    String azureSecretName;
}
