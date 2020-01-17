package com.strapdata.strapkop;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;

import javax.validation.constraints.NotBlank;

/**
 * This class holds a type-safe representation of the configuration gathered from props file (application.yaml)
 */
@ConfigurationProperties("operator")
@Getter
public class OperatorConfig {
    
    @NotBlank
    String namespace;

    DnsConfig dns = new DnsConfig();

    TestSuiteConfig test = new TestSuiteConfig();

    @Getter
    @ConfigurationProperties("dns")
    public static class DnsConfig {

        boolean enabled;

        String zone;

        int ttl;

        String azureSecretName;
    }


    @Getter
    @ConfigurationProperties("test")
    public static class TestSuiteConfig {

        boolean enabled = false;

        Platform platform = Platform.LOCAL;

        public static enum Platform {
            LOCAL,
            GKE,
            AZURE
            // TODO OVH, AWS
        }
    }
}
