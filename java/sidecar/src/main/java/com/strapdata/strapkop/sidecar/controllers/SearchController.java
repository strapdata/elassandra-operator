package com.strapdata.strapkop.sidecar.controllers;


import com.strapdata.strapkop.sidecar.cassandra.CassandraModule;
import com.strapdata.strapkop.sidecar.cassandra.ElasticNodeMetricsMBean;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.reactivex.Completable;
import io.reactivex.Single;

@Controller("/enterprise/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchController {

    private ElasticNodeMetricsMBean elasticNodeMetricsMBean;

    public SearchController(CassandraModule cassandraModule) {
        this.elasticNodeMetricsMBean = cassandraModule.elasticNodeMetricsMBeanProvider();
    }

    @Get("/")
    public Single<Boolean> isSearchEnabled() {
        return Single.create(emitter -> {
            emitter.onSuccess(elasticNodeMetricsMBean.isSearchEnabled());
        });
    }

    @Post("/enable")
    public Completable enable() {
        return Completable.create(emitter -> {
            elasticNodeMetricsMBean.setSearchEnabled(true);
            emitter.onComplete();
        });
    }

    @Post("/disable")
    public Completable disable() {
        return Completable.create(emitter -> {
            elasticNodeMetricsMBean.setSearchEnabled(false);
            emitter.onComplete();
        });
    }
}
