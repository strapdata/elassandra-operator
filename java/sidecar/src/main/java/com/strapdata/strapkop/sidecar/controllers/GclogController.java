package com.strapdata.strapkop.sidecar.controllers;


import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.reactivex.Single;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Allow to list and download GC logs from Elassandra
 */
@Tag(name = "gclog")
@Controller("/gclog")
public class GclogController {

    private static final Logger logger = LoggerFactory.getLogger(GclogController.class);

    File gclogDir;

    public GclogController() {
        gclogDir = new File(System.getenv("GCLOG_DIR") == null ? "/var/log/cassandra/" : System.getenv("GCLOG_DIR"));
        if (!gclogDir.exists()) {
            logger.error("GC log directory does not exists:"+gclogDir.getAbsolutePath());
        }
    }

    @Get("/")
    public Single<List<String>> list() {
        return Single.just(gclogDir)
                .map(dir -> Arrays.asList(dir.listFiles((d, name) -> name.startsWith("gc"))).stream()
                        .map(f -> f.getName())
                        .collect(Collectors.toList()));
    }
}
