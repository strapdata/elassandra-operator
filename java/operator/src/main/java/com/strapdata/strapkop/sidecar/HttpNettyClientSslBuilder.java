/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.sidecar;

import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.client.ssl.NettyClientSslBuilder;
import io.micronaut.http.ssl.SslConfiguration;
import io.netty.handler.ssl.SslContext;

import java.util.Optional;

@SuppressWarnings("all")
public class HttpNettyClientSslBuilder extends NettyClientSslBuilder {
    Optional<SslContext> sslContextOptional;

    /**
     * @param resourceResolver The resource resolver
     */
    public HttpNettyClientSslBuilder(ResourceResolver resourceResolver, SslContext sslContext) {
        super(resourceResolver);
        this.sslContextOptional = Optional.ofNullable(sslContext);
    }

    public Optional<SslContext> build(SslConfiguration ssl) {
        return this.sslContextOptional;
    }
}
