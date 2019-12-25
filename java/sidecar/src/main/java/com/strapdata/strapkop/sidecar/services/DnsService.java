package com.strapdata.strapkop.sidecar.services;


import com.google.common.base.Strings;
import com.microsoft.azure.AzureEnvironment;
import com.microsoft.azure.credentials.ApplicationTokenCredentials;
import com.microsoft.azure.credentials.AzureTokenCredentials;
import com.microsoft.azure.management.Azure;
import com.microsoft.rest.LogLevel;
import com.strapdata.strapkop.sidecar.config.DnsConfiguration;
import hu.akarnokd.rxjava.interop.RxJavaInterop;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceStartedEvent;
import io.micronaut.scheduling.annotation.Async;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

/**
 * Manage public DNS records on Azure
 */
@Singleton
public class DnsService implements ApplicationEventListener<ServiceStartedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(DnsService.class);

    @Inject
    DnsConfiguration dnsConfiguration;

    @Inject
    final Azure azure;

    public DnsService(DnsConfiguration dnsConfiguration) throws IOException {

        AzureEnvironment environment = new AzureEnvironment(new HashMap<String, String>());
        environment.endpoints().putAll(AzureEnvironment.AZURE.endpoints());

        AzureTokenCredentials credentials = new ApplicationTokenCredentials(
                System.getenv("AZURE_CLIENT_ID"),
                System.getenv( "AZURE_TENANT_ID"),
                System.getenv( "AZURE_CLIENT_SECRET"),
                environment).withDefaultSubscriptionId(System.getenv("AZURE_SUBSCRIPTION_ID"));

        this.azure = Azure.configure()
                .withLogLevel(LogLevel.BASIC)
                .authenticate(credentials)
                .withDefaultSubscription();

        // Print selected subscription
        logger.info("Selected subscription: " + azure.subscriptionId());
        logger.info("Selected clientId: " + System.getenv("AZURE_CLIENT_ID"));
    }

    /**
     * Add public DNS records for traefik
     * @param name
     * @param externalIp
     * @return
     */
    public Completable updateDnsARecord(String name, String externalIp) {
        return RxJavaInterop.toV2Observable(azure.dnsZones().getByResourceGroupAsync(dnsConfiguration.resourceGroup, dnsConfiguration.domain)
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

    @Async
    @Override
    public void onApplicationEvent(final ServiceStartedEvent event) {
        String podName = System.getenv("POD_NAME");
        String seedHostId = System.getenv("SEED_HOST_ID");
        try {
            String publicIp = readFile("/nodeinfo/public-ip", Charset.forName("UTF-8"));
            if (!Strings.isNullOrEmpty(publicIp) && seedHostId != null && podName.endsWith("-0")) {
                updateDnsARecord(seedHostId, publicIp).blockingGet();
                logger.info("Dns updated at startup from pod={} {}.{} = {}", podName, seedHostId, dnsConfiguration.domain, publicIp);
            }
        } catch (IOException e) {
            logger.warn("Failed to update DNS seed public ip", e);
        }
    }

    static String readFile(String path, Charset encoding) throws IOException
    {
        byte[] encoded = Files.readAllBytes(Paths.get(path));
        return new String(encoded, encoding);
    }
}

