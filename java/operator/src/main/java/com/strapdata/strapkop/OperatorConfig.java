package com.strapdata.strapkop;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * This class holds a type-safe representation of the configuration gathered from props file (application.yaml)
 */
@ConfigurationProperties("operator")
@Getter
public class OperatorConfig {

    @Nullable
    String namespace;

    @Nullable
    int k8sWatchPeriodInSec = 180;

    @Nullable
    int elassandraNodeWatchPeriodInSec = 60;

    DnsConfig dns = new DnsConfig();

    TestSuiteConfig test = new TestSuiteConfig();

    TasksConfig tasks = new TasksConfig();

    @Getter
    @ConfigurationProperties("tasks")
    public static class TasksConfig {
        private static final Pattern pattern = Pattern.compile("([\\d])+[DdHhMm]");
        /**
         * define the retention period of terminated tasks
         * format : xxx[DHM]
         * where : xxx in a integer
         * D means Days
         * H means Hours
         * M means Minutes
         */
        String retentionPeriod = "15D";

        public final int convertRetentionPeriodInMillis() {
            if (pattern.matcher(retentionPeriod).matches()) {
                return (int)Duration.parse(
                        retentionPeriod.toUpperCase().endsWith("D") ?
                        "P"+retentionPeriod : "PT"+retentionPeriod).toMillis();
            }
            throw new StrapkopException("Invalid RetentionPeriod '" + retentionPeriod +"'");
        }
    }

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
