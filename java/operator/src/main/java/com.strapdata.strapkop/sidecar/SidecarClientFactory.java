package com.strapdata.strapkop.sidecar;

import com.google.common.net.InetAddresses;
import io.kubernetes.client.models.V1Pod;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.http.client.LoadBalancer;

import javax.inject.Singleton;
import javax.swing.text.html.Option;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Optional;

@Factory
@Singleton
public class SidecarClientFactory {

    ApplicationContext context;

    public SidecarClientFactory(ApplicationContext context) {
        this.context = context;
    }

    public SidecarClient clientForAddress(final InetAddress address) throws MalformedURLException {
        return context.createBean(SidecarClient.class,  LoadBalancer.fixed(new URL("https://"+address.getHostAddress()+":4567")));
    }

    public SidecarClient clientForPod(final V1Pod pod) throws MalformedURLException {
        return clientForAddress(InetAddresses.forString(pod.getStatus().getPodIP()));
    }
    
    // TODO: this is a temporary fix
    public SidecarClient clientForPodNullable(final V1Pod pod) {
        try {
            return clientForAddress(InetAddresses.forString(pod.getStatus().getPodIP()));
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return null;
        }
    }
}
