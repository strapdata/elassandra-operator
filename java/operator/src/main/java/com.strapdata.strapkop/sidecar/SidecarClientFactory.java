package com.strapdata.strapkop.sidecar;

import io.kubernetes.client.models.V1Pod;

import javax.inject.Singleton;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

@Singleton
public class SidecarClientFactory {
    
    public SidecarClient clientForAddress(final InetAddress address) throws MalformedURLException {
        return clientForHost(address.getHostAddress());
    }
    
    public SidecarClient clientForHost(final String host) throws MalformedURLException {
        return new SidecarClient(new URL("http://" + host + ":8080"));
    }
    
    public SidecarClient clientForPod(final V1Pod pod) throws UnknownHostException, MalformedURLException {
        return clientForAddress(InetAddress.getByName(pod.getStatus().getPodIP()));
    }
    
    // TODO: this is a temporary fix
    public SidecarClient clientForPodNullable(final V1Pod pod) {
        try {
            return clientForPod(pod);
        } catch (MalformedURLException | UnknownHostException e) {
            e.printStackTrace();
            return null;
        }
    }
}
