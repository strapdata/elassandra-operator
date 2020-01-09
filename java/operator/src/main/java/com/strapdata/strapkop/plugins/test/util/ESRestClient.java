package com.strapdata.strapkop.plugins.test.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.strapdata.strapkop.StrapkopException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.RxHttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ESRestClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ESRestClient.class);

    private final String esLogin;
    private final String esPassword;
    private final String elassandraNode;
    private final int port;
    private final boolean ssl;

    private final RxHttpClient client;

    public ESRestClient(String elassandraNode, int port, boolean ssl, String esLogin, String esPassword) {
        this.port = port;
        this.ssl = ssl;
        this.elassandraNode = elassandraNode;
        this.esLogin = esLogin;
        this.esPassword = esPassword;

        final String url = (ssl ? "https" : "http") + "://" + elassandraNode + ":" + port + "/";
        try {
            this.client = RxHttpClient.create(new URL(url));
        } catch (MalformedURLException e) {
            throw new StrapkopException("ESRestClient refuse url '" + url +"'", e);
        }
    }

    public void refresh(String keyspace) {
        try {


            Thread.sleep(5000);

            MutableHttpRequest<String> request = HttpRequest.POST("/"+keyspace+"/_refresh", "")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON_TYPE);
            if (esLogin != null) {
                request = request.basicAuth(esLogin, esPassword);
            }
            HttpResponse<Map<String, Object>> response = this.client.toBlocking().exchange(request);

            switch (response.getStatus()) {
                case OK:
                case CREATED:
                    Optional<Map<String, Object>> body = response.getBody();
                    LOGGER.debug(body.orElse(new HashMap<String, Object>(0)).toString());
                    break;
                default:
                    LOGGER.warn("ESREstClient received non OK status '{}' : {}", response.getStatus(), response.body());
            }
        } catch (HttpClientResponseException e) {
            LOGGER.warn("ESREstClient received non OK status '{}' : {}", e.getResponse().getStatus(), e.getResponse().body());
        } catch (InterruptedException e) {
            // do nothing
            LOGGER.debug("Sleep in refesh interrupted");
        }
    }

    public void upload(String keyspace, String table, Map<String, String> document) {
        try {
            MutableHttpRequest<Map<String, String>> request = HttpRequest.POST("/" + keyspace + "/" + table, document)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON_TYPE);
            if (esLogin != null) {
                request = request.basicAuth(esLogin, esPassword);
            }
            HttpResponse<Map<String, Object>> response = this.client.toBlocking().exchange(request);

            switch (response.getStatus()) {
                case OK:
                case CREATED:
                    Optional<Map<String, Object>> body = response.getBody();
                    LOGGER.trace(body.orElse(new HashMap<String, Object>(0)).toString());
                    break;
                default:
                    LOGGER.warn("ESREstClient received non OK status '{}' : {}", response.getStatus(), response.body());
                    throw new StrapkopException("ESREstClient received non OK status '" + response.getStatus() + "'");
            }
        } catch (HttpClientResponseException e) {
            LOGGER.warn("ESREstClient received non OK status '{}' : {}", e.getResponse().getStatus(), e.getResponse().body());
            throw new StrapkopException("ESREstClient received non OK status '" + e.getResponse().getStatus() + "'");
        }
    }

    public JsonNode getDocuments(String keyspace, String table, String query) throws IOException {

        MutableHttpRequest<Object> request = HttpRequest.GET("/" + keyspace + "/" + table + "/_search").body(query)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON_TYPE);

        if (esLogin != null) {
            request = request.basicAuth(esLogin, esPassword);
        }

        HttpResponse<JsonNode> response = this.client.toBlocking().exchange(request, JsonNode.class);

        switch (response.getStatus()) {
            case OK:
                ObjectMapper m = new ObjectMapper();
                JsonNode body = response.body();
                return body;
            default:
                LOGGER.warn("ESREstClient received non OK status '{}' : {}", response.getStatus(), response.body());
                throw new StrapkopException("ESREstClient received non OK status '" + response.getStatus() + "'");
        }
    }
    @Override
    public void close() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                LOGGER.debug("Error on ESRestClient.close", e);
            }
        }
    }


    public static void main(String[] args) throws IOException {
        Map<String, String> doc = new HashMap<>();
        doc.put("a", "aaaa");
        doc.put("b", "bbbb");
        doc.put("c", "cccc");
        try (ESRestClient client = new ESRestClient("localhost", 19200, false, null, null)) {
            client.upload("test", "doc", doc);
            JsonNode node = client.getDocuments("test", "doc", "{\n" +
                    "  \"query\": { \n" +
                    "    \n" +
                    "  }\n" +
                    "}");
            if (node != null) {
                System.out.println(node.path("hits").path("total").intValue());
            }
        }
    }
}