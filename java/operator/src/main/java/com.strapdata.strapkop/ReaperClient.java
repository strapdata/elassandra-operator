package com.strapdata.strapkop;

import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.RxHttpClient;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.micronaut.http.HttpRequest.GET;
import static io.micronaut.http.HttpRequest.POST;

/**
 * This is the http client for the reaper api.
 * It use reaper authentication mechanism as described in http://cassandra-reaper.io/docs/api/
 */
@SuppressWarnings("rawtypes")
public class ReaperClient implements Closeable {
    
    
    private final Logger logger = LoggerFactory.getLogger(ReaperClient.class);
    
    private final RxHttpClient httpClient;
    private final DataCenter dataCenter;
    
    public ReaperClient(DataCenter dc) throws MalformedURLException {
        httpClient = RxHttpClient.create(new URL("http", OperatorNames.reaper(dc), 8080, "/"));
        this.dataCenter = dc;
    }
    
    /**
     * Check for the availability of reaper
     */
    public Single<Boolean> ping() {
        return httpClient.exchange(GET("/ping"))
                .observeOn(Schedulers.io())
                .map(res -> res.code() == 204)
                .onErrorReturnItem(false)
                .single(false);
    }
    
    /**
     * Register the cluster (the datacenter in fact, because reaper is configured with availability == EACH and local_dc)
     */
    public Single<Boolean> registerCluster() {
        
        final String seedHost = OperatorNames.seedsService(dataCenter);
        final int jmxPort = dataCenter.getSpec().getJmxPort();
        
        return authenticate().flatMap(jwt -> httpClient.exchange(
                    POST(String.format("/cluster?seedHost=%s&jmxPort=%d", seedHost, jmxPort), "")
                    .header("Authorization", String.format("Bearer %s", jwt))
            )
                .observeOn(Schedulers.io())
                .singleOrError()
        ).map(res -> true);
    }
    
    private String jwt = null;
    
    /**
     * Encapsulate the authentication logic, fetch the jwt token the first time then store it for later reuse
     */
    private Single<String> authenticate() {
        
        if (this.jwt != null) {
            return Single.just(this.jwt);
        }
        
        return login("admin", "admin")
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
     * call GET /jwt to get the token
     */
    private Single<String> getJwt(String cookie) {
        return httpClient.exchange(GET("/jwt").header("Cookie", cookie))
                .observeOn(Schedulers.io())
                .doOnNext(httpResponse -> {
                    logger.debug("reaper jwt response status={}", httpResponse.getStatus().getCode());
                })
                .map(httpResponse -> Objects.requireNonNull(httpResponse.body()).toString(StandardCharsets.UTF_8))
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
    }
}
