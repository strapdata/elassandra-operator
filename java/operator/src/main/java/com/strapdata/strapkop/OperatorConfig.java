package com.strapdata.strapkop;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Property;
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

    TestSuiteConfig test = new TestSuiteConfig();

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
