package com.strapdata.strapkop;

import com.google.common.collect.ImmutableMap;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.micronaut.core.io.buffer.ByteBuffer;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.RxHttpClient;
import io.reactivex.Single;

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

@SuppressWarnings("rawtypes")
public class ReaperClient implements Closeable {
    
    private final RxHttpClient httpClient;
    private final DataCenter dataCenter;
    
    public ReaperClient(DataCenter dc) throws MalformedURLException {
        httpClient = RxHttpClient.create(new URL("http", OperatorNames.reaper(dc), 8080, "/"));
        this.dataCenter = dc;
        httpClient.close();
    }
    
    /**
     * Register the cluster
     */
    public Single<Boolean> registerCluster() {
        
        final String seedHost = OperatorNames.seedsService(dataCenter);
        final int jmxPort = dataCenter.getSpec().getJmxPort();
        
        return authenticate().flatMap(jwt -> httpClient.exchange(
                    POST(String.format("/cluster?seedHost=%s&jmxPort=%d", seedHost, jmxPort), "")
                    .header("Authorization", String.format("Bearer %s", jwt))
            ).singleOrError()
        ).map(res -> true);
    }
    
    private String jwt = null;
    
    /**
     * Authenticate the first time then store the jwt for later reuse
     */
    private Single<String> authenticate() {
        
        if (this.jwt != null) {
            return Single.just(this.jwt);
        }
        
        return login("admin", "admin")
                .flatMap(this::getJwt)
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
                .map(this::parseCookie)
                .singleOrError();
    }
    
    /**
     * call GET /jwt to get the token
     */
    private Single<String> getJwt(String cookie) {
        return httpClient.exchange(GET("/jwt").header("Cookie", cookie))
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
    
        return m.group(0);
    }
    
    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
