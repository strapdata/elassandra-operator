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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strapdata.strapkop.cql.CqlRole;
import com.strapdata.strapkop.model.sidecar.BackupResponse;
import com.strapdata.strapkop.model.sidecar.StatusResponse;
import io.micronaut.core.annotation.AnnotationMetadataResolver;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.DefaultHttpClient;
import io.micronaut.http.client.HttpClientConfiguration;
import io.micronaut.http.client.LoadBalancer;
import io.micronaut.http.client.RxHttpClient;
import io.micronaut.http.codec.MediaTypeCodecRegistry;
import io.micronaut.jackson.ObjectMapperFactory;
import io.micronaut.jackson.codec.JsonMediaTypeCodec;
import io.micronaut.jackson.codec.JsonStreamMediaTypeCodec;
import io.micronaut.runtime.ApplicationConfiguration;
import io.netty.channel.MultithreadEventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.reactivex.Completable;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;

import static io.micronaut.http.HttpRequest.GET;
import static io.micronaut.http.HttpRequest.POST;

/**
 * Currently @Client annotation advice that generates the client code from an interface is totally static and cannot
 * be used to configure client with dynamic urls. See {@link io.micronaut.http.client.interceptor.HttpClientIntroductionAdvice}
 */
public class HttpClient {

    static final Logger logger = LoggerFactory.getLogger(HttpClient.class);

    private RxHttpClient httpClient;
    private CqlRole cqlRole;

    public HttpClient(URL url,
                      HttpClientConfiguration httpClientConfiguration,
                      SslContext sslContext,
                      CqlRole cqlRole) {
        this.httpClient = new DefaultHttpClient(LoadBalancer.fixed(url),
                httpClientConfiguration,
                null,
                new DefaultThreadFactory(MultithreadEventLoopGroup.class),
                new HttpNettyClientSslBuilder(new ResourceResolver(), sslContext),
                createDefaultMediaTypeRegistry(),
                AnnotationMetadataResolver.DEFAULT);
        this.cqlRole = cqlRole;
    }

    private static MediaTypeCodecRegistry createDefaultMediaTypeRegistry() {
        ObjectMapper objectMapper = new ObjectMapperFactory().objectMapper(null, null);
        ApplicationConfiguration applicationConfiguration = new ApplicationConfiguration();
        return MediaTypeCodecRegistry.of(
                new JsonMediaTypeCodec(objectMapper, applicationConfiguration, null),
                new JsonStreamMediaTypeCodec(objectMapper, applicationConfiguration, null)
        );
    }

    /**
     * Use the last connected cqlRole used by the CqlRoleManager, or the default cassandra.
     * When connection fails, sidecarClient is removed from the connection cache.
     * @param req
     * @return
     */
    public <I> MutableHttpRequest<I> auth(MutableHttpRequest<I> req) {
        logger.debug("cqlRole={}", cqlRole);
        if (cqlRole != null)
            req.basicAuth(cqlRole.getUsername(), cqlRole.getPassword());
        return req;
    }

    public Single<StatusResponse> status() {
        return httpClient.retrieve(auth(GET("_nodetool/status")), StatusResponse.class).singleOrError();
    }

    public Completable decommission() {
        return httpClient.exchange(auth(POST("_nodetool/decommission", ""))).ignoreElements();
    }

    public Completable remove(@Nullable  String dcName, String... hostIds) throws UnsupportedEncodingException {
        String qs = (dcName == null) ? "" : "?dc=" + URLEncoder.encode(dcName,"UTF-8");
        if (hostIds.length > 0) {
            qs += (qs.length() > 0) ? "&hosts=" : "?hosts=";
            boolean first = true;
            for (String hostId : hostIds) {
                qs += (first) ? "" : ",";
                qs += URLEncoder.encode(hostId, "UTF-8");
                first = false;
            }
        }
        return httpClient.exchange(auth(POST("_nodetool/remove" + qs, ""))).ignoreElements();
    }

    public Completable cleanup(@Nullable String keyspace) throws UnsupportedEncodingException {
        String qs = (keyspace == null) ? "" : "?keyspace=" + URLEncoder.encode(keyspace,"UTF-8");
        return httpClient.exchange(auth(POST("_nodetool/cleanup" +qs, ""))).ignoreElements();
    }

    public Completable rebuild(String sourceDcName, @Nullable String keyspace) throws UnsupportedEncodingException {
        String qs = (keyspace == null) ? "" : "?keyspace=" + URLEncoder.encode(keyspace,"UTF-8");
        return httpClient.exchange(auth(POST("_nodetool/rebuild/"+sourceDcName+ qs, ""))).ignoreElements();
    }

    public Completable flush(@Nullable String keyspace) throws UnsupportedEncodingException {
        String qs = (keyspace == null) ? "" : "?keyspace=" + URLEncoder.encode(keyspace,"UTF-8");
        return httpClient.exchange(auth(POST("_nodetool/flush" + qs, ""))).ignoreElements();
    }

    public Completable open(@Nullable String indices) throws UnsupportedEncodingException {
        String idx = (indices == null) ? "*" : indices;
        return httpClient.exchange(auth(POST(idx+"/_open", ""))).ignoreElements();
    }

    public Completable close(@Nullable String indices) throws UnsupportedEncodingException {
        String idx = (indices == null) ? "*" : indices;
        return httpClient.exchange(auth(POST(idx + "/_close", ""))).ignoreElements();
    }

    public Completable updateRouting(@Nullable String indices) throws UnsupportedEncodingException {
        String idx = (indices == null) ? "" : "/" + indices;
        return httpClient.exchange(auth(POST(idx + "/_updaterouting", ""))).ignoreElements();
    }

    public Completable reloadLicense() throws UnsupportedEncodingException {
        return httpClient.exchange(auth(POST("/_license", ""))).ignoreElements();
    }

    public Completable repairPrimaryRange(@Nullable String keyspace) throws UnsupportedEncodingException {
        String qs = (keyspace == null) ? "" : "?keyspace=" + URLEncoder.encode(keyspace,"UTF-8");
        return httpClient.exchange(auth(POST("_nodetool/repair" + qs, ""))).ignoreElements();
    }

    public Single<BackupResponse> snapshot(String repository, List<String> keyspaces) {
        return httpClient.retrieve(auth(POST("_nodetool/snapshot", keyspaces)), BackupResponse.class).singleOrError();
    }

    public boolean isRunning() {
        return httpClient.isRunning();
    }

    public void close() {
        httpClient.close();
    }
}
