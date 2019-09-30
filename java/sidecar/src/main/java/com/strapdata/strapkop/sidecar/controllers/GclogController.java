package com.strapdata.strapkop.sidecar.controllers;


import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.reactivex.Single;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Allow to list and download GC logs from Elassandra
 */
//@Tag(name = "gclog")
@Controller("/gclog")
public class GclogController {

    File gclogDir;

    public GclogController() {
        gclogDir = new File("/tmp/");
    }

    @Get("/")
    public Single<List<String>> list() {
        return Single.just(gclogDir)
                .map(dir -> Arrays.asList(dir.listFiles((d, name) -> name.startsWith("gc"))).stream()
                        .map(f -> f.getName())
                        .collect(Collectors.toList()));
    }
}
