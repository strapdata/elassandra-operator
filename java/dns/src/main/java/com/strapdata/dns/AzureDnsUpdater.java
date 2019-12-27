package com.strapdata.dns;


import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.credentials.AzureTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.rest.LogLevel;
import hu.akarnokd.rxjava.interop.RxJavaInterop;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;

/**
 * Manage public DNS records on Azure
 */
@Singleton
public class AzureDnsUpdater extends DnsUpdater {

    private static final Logger logger = LoggerFactory.getLogger(AzureDnsUpdater.class);

    Azure azure;
    String resourceGroup;

    public AzureDnsUpdater(DnsConfiguration dnsConfiguration) throws IOException {
        this(dnsConfiguration,
                System.getenv("DNS_AZURE_CLIENT_ID"),
                System.getenv("DNS_AZURE_TENANT_ID"),
                System.getenv("DNS_AZURE_CLIENT_SECRET"),
                System.getenv("DNS_AZURE_SUBSCRIPTION_ID"),
                System.getenv("DNS_AZURE_RESOURCE_GROUP")
        );
    }

    public AzureDnsUpdater(DnsConfiguration dnsConfiguration, String clientId, String tenantId, String clientSecret, String subscriptionId, String resourceGroup) throws IOException {
        super(dnsConfiguration);
        this.resourceGroup = resourceGroup;

        try {
            AzureEnvironment environment = new AzureEnvironment(new HashMap<String, String>());
            environment.endpoints().putAll(AzureEnvironment.AZURE.endpoints());

            AzureTokenCredentials credentials = new ApplicationTokenCredentials(
                    clientId,
                    tenantId,
                    clientSecret,
                    environment).withDefaultSubscriptionId(subscriptionId);

            this.azure = Azure.configure()
                    .withLogLevel(LogLevel.BASIC)
                    .authenticate(credentials)
                    .withDefaultSubscription();

            // Print selected subscription
            logger.info("Selected clientId: " + clientId);
            logger.info("Selected tenantId: " + tenantId);
            logger.info("Selected clientSecret: " + clientSecret);
            logger.info("Selected subscription: " + azure.subscriptionId());
            logger.info("Selected resourceGroup: " + resourceGroup);

        } catch(Exception e) {
            logger.error("Azure authentication failed with clientId={} tenanId={} clientSecret={} subcriptionId={} resourceGroup={}",
                    clientId, tenantId, clientSecret, subscriptionId, resourceGroup);
            this.azure = null;
        }
    }

    /**
     * Add public DNS records for traefik
     * @param name
     * @param externalIp
     * @return
     */
    public Completable updateDnsARecord(String name, String externalIp) {
        if (azure == null)
            throw new IllegalStateException("Azure authentication failed");
        return RxJavaInterop.toV2Observable(azure.dnsZones().getByResourceGroupAsync(this.resourceGroup, dnsConfiguration.domain)
                .flatMap(zone -> {
                    logger.debug("creating DNS records {} = {}", name, externalIp);
                    return zone.update()
                            // A name = externalIP
                            .defineARecordSet(name)
                            .withIPv4Address(externalIp)
                            .withTimeToLive(dnsConfiguration.ttl).attach()
                            .applyAsync();
                })).ignoreElements();
    }

    /**
     * Delete public DNS records for traefik.
     * @param name
     * @return
     */
    public Completable deleteDnsARecord(String name) {
        logger.debug("deleting DNS record name={}", name);
        if (azure == null)
            throw new IllegalStateException("Azure authentication failed");
        return RxJavaInterop.toV2Observable(azure.dnsZones().getByResourceGroupAsync(resourceGroup, dnsConfiguration.domain)
                .flatMap(zone -> {
                    logger.debug("deleting DNS records {}", name);
                    return zone.update()
                            .withoutARecordSet(name)
                            .applyAsync();
                })).ignoreElements();
    }

}

