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

package com.strapdata.strapkop.plugins;

import com.google.common.collect.ImmutableMap;
import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import com.strapdata.strapkop.model.k8s.datacenter.ReaperScheduledRepair;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.RxHttpClient;
import io.reactivex.Scheduler;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.micronaut.http.HttpRequest.GET;
import static io.micronaut.http.HttpRequest.POST;

/**
 * This is the http client for the reaper admin api.
 * It use reaper authentication mechanism as described in http://cassandra-reaper.io/docs/api/
 */
@SuppressWarnings("rawtypes")
public class ReaperClient implements Closeable {

    private final Logger logger = LoggerFactory.getLogger(ReaperClient.class);

    private final Scheduler scheduler;
    private final RxHttpClient adminHttpClient; // to request the "/ping" endpoint
    private final RxHttpClient httpClient;
    private final DataCenter dataCenter;
    private String jwt = null;

    public ReaperClient(DataCenter dc, Scheduler scheduler) throws MalformedURLException {
        // cross namespace connection require a FQDN
        String serviceName = ReaperPlugin.reaperName(dc) + "." + dc.getMetadata().getNamespace() + ".svc.cluster.local";
        httpClient = RxHttpClient.create(new URL("http", serviceName, ReaperPlugin.APP_SERVICE_PORT, "/"));
        adminHttpClient = RxHttpClient.create(new URL("http", serviceName, ReaperPlugin.ADMIN_SERVICE_PORT, "/"));
        this.dataCenter = dc;
        this.scheduler = scheduler;
    }

    /**
     * Register the cluster (the datacenter in fact, because reaper is configured with availability == EACH and local_dc)
     */
    public Single<Boolean> registerCluster(String username, String password) {
        final String seedHost = OperatorNames.nodesService(dataCenter) + "." + dataCenter.getMetadata().getNamespace() + ".svc.cluster.local";
        final int jmxPort = dataCenter.getSpec().getJvm().getJmxPort();
        final String url = String.format("/cluster?seedHost=%s&jmxPort=%d", seedHost, jmxPort);
        logger.debug("datacenter={} url={}", dataCenter.id(), url);

        return authenticate(username, password)
                .flatMap(jwt -> httpClient.exchange(
                        POST(url, "").header("Authorization", String.format(Locale.ROOT, "Bearer %s", jwt)))
                        .observeOn(scheduler)
                        .singleOrError()
                        .map(res -> {
                            logger.debug("datacenter={} reaper registration rc={} reason={} ", dataCenter.id(), res.getStatus().getCode(), res.getStatus().getReason());
                            return res.getStatus().getCode() == 204   || res.getStatus().getCode() == 201;
                        }));
    }

    // TODO: kibana keyspace may have not tables at the time of the registration, causing a reaper error
    public Single<Tuple2<ReaperScheduledRepair,Boolean>> registerScheduledRepair(String username, String password, ReaperScheduledRepair reaperScheduledRepair) throws UnsupportedEncodingException {
        String url = "/repair_schedule?clusterName="+ URLEncoder.encode(dataCenter.getSpec().getClusterName(), "UTF-8") +
                ("&keyspace=" + URLEncoder.encode(reaperScheduledRepair.getKeyspace(), "UTF-8")) +
                ("&owner=" + URLEncoder.encode(reaperScheduledRepair.getOwner() == null ? "elassandra-operator" : reaperScheduledRepair.getOwner(), "UTF-8")) +
                ("&incrementalRepair=" + (reaperScheduledRepair.getIncrementalRepair() != null &&  reaperScheduledRepair.getIncrementalRepair() == true ? "true" : "false")) +
                ("&scheduleDaysBetween=" + reaperScheduledRepair.getScheduleDaysBetween()) +
                ("&intensity=" + reaperScheduledRepair.getIntensity()) +
                ("&incrementalRepair=" + reaperScheduledRepair.getIncrementalRepair()) +
                ("&repairParallelism=" + reaperScheduledRepair.getRepairParallelism().toString()) +
                (reaperScheduledRepair.getTables() == null ? "" : "&tables=" + reaperScheduledRepair.getTables().stream().map(URLEncoder::encode).collect(Collectors.joining(","))) +
                (reaperScheduledRepair.getScheduleTriggerTime() == null ? "" : "&scheduleTriggerTime=" + reaperScheduledRepair.getScheduleTriggerTime());

        logger.debug("datacenter={} url={}", dataCenter.id(), url);

        String url2 = url;
        return authenticate(username, password)
                .flatMap(jwt -> httpClient.exchange(
                        POST(url2, "").header("Authorization", String.format("Bearer %s", jwt)))
                        .observeOn(Schedulers.io())
                        .singleOrError()
                        .map(res -> {
                            logger.debug("datacenter={} reaperScheduledRepair={} rc={}", dataCenter.id(), reaperScheduledRepair, res.getStatus().getCode());
                            return new Tuple2<>(reaperScheduledRepair, res.getStatus().getCode() == 200);
                        }));
    }

    /**
     * Encapsulate the authentication logic, fetch the jwt token the first time then store it for later reuse
     */
    private Single<String> authenticate(String username, String password) {
        if (this.jwt != null) {
            return Single.just(this.jwt);
        }

        return login(username, password)
                .flatMap(this::getJwt)
                .doOnError(throwable -> {
                    logger.error("reaper authentication error", throwable);
                })
                .doOnSuccess(jwt -> this.jwt = jwt);
    }

    /**
     * call POST /login to get the cookie
     */
    private Single<String> login(String username, String password) {
        final Map<String, String> data = ImmutableMap.of(
                "username", username,
                "password", password
        );
        return httpClient.exchange(POST("/login", data).contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE))
                .observeOn(Schedulers.io())
                .doOnNext(httpResponse -> {
                    logger.debug("reaper login response status={}", httpResponse.getStatus().getCode());
                })
                .map(this::parseCookie)
                .singleOrError();
    }

    /**
     * call GET /jwt with the cookie to get the token
     */
    private Single<String> getJwt(String cookie) {
        return httpClient.exchange(GET("/jwt").header("Cookie", cookie))
                .doOnNext(httpResponse -> {
                    logger.debug("reaper jwt response status={}", httpResponse.getStatus().getCode());
                })
                .map(httpResponse -> Objects.requireNonNull(httpResponse.body()).toString(httpResponse.getCharacterEncoding()))
                .subscribeOn(Schedulers.io()) // force the execution of body extraction in same thread as Request execution
                .singleOrError();
    }

    private static Pattern cookiePattern = Pattern.compile("^(JSESSIONID=.*);");

    private String parseCookie(HttpResponse<ByteBuffer> httpResponse) {

        final String cookieHeader = httpResponse.header("Set-Cookie");
        if (cookieHeader == null) {
            return null;
        }

        final Matcher m = cookiePattern.matcher(cookieHeader);
        if (!m.find()) {
            return null;
        }

        return m.group(1);
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
        adminHttpClient.close();
    }
}
