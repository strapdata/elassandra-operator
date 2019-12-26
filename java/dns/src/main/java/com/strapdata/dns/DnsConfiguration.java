package com.strapdata.dns;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;

@ConfigurationProperties("dns")
@Getter
public class DnsConfiguration {


    /**
     * DNS domain name when deploying ingress for plugins and registering DNS record for seed nodes.
     */
    String domain;

    /**
     * DNS ttl when registering DNS record for seed nodes
     */
    int ttl;
}
