package com.strapdata.strapkop.sidecar;

import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Currently @Client annotation advice that generates the client code from an interface is totally static and cannot
 * be used to configure client with dynamic urls. See {@link io.micronaut.http.client.interceptor.HttpClientIntroductionAdvice}
 */
public class ElasticClient {

    static final Logger logger = LoggerFactory.getLogger(ElasticClient.class);

    private RestHighLevelClient esClient;

    protected ElasticClient(RestHighLevelClient esClient) {
        this.esClient = esClient;
    }
}
