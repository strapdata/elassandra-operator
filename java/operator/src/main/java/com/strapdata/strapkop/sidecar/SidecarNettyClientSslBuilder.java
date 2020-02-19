package com.strapdata.strapkop.sidecar;

import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.client.ssl.NettyClientSslBuilder;
import io.micronaut.http.ssl.SslConfiguration;
import io.netty.handler.ssl.SslContext;

import java.util.Optional;

@SuppressWarnings("all")
public class SidecarNettyClientSslBuilder extends NettyClientSslBuilder {
    Optional<SslContext> sslContextOptional;

    /**
     * @param resourceResolver The resource resolver
     */
    public SidecarNettyClientSslBuilder(ResourceResolver resourceResolver, SslContext sslContext) {
        super(resourceResolver);
        this.sslContextOptional = Optional.ofNullable(sslContext);
    }

    public Optional<SslContext> build(SslConfiguration ssl) {
        return this.sslContextOptional;
    }
}
