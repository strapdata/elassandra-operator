package com.strapdata.strapkop.sidecar;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("sidecar")
public class SidecarConfiguration {

    public String dnsDomain;
    public int dnsTtl;

}

