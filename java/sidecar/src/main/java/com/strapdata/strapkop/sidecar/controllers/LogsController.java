package com.strapdata.strapkop.sidecar.controllers;


import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.server.types.files.SystemFile;
import io.reactivex.Single;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Allow to list and download GC+audit+heapdump logs from Elassandra
 */
@Tag(name = "logs")
@Controller("/logs")
public class LogsController {

    private static final Logger logger = LoggerFactory.getLogger(LogsController.class);

    File logsDir;

    public LogsController() {
        logsDir = new File(System.getenv("CASSANDRA_LOGS") == null ? "/var/log/cassandra/" : System.getenv("CASSANDRA_LOGS"));
        if (!logsDir.exists()) {
            logger.error("GC log directory does not exists:"+ logsDir.getAbsolutePath());
        }
    }

    /***
     * List available GC log files.
     * @return
     */
    @Get("/gc")
    public Single<List<String>> gc() {
        return Single.just(logsDir)
                .map(dir -> Arrays.asList(dir.listFiles((d, name) -> name.startsWith("gc"))).stream()
                        .map(f -> f.getName())
                        .collect(Collectors.toList()));
    }

    /**
     * List available audit files.
     * @return
     */
    @Get("/audit")
    public Single<List<String>> audit() {
        return Single.just(logsDir)
                .map(dir -> Arrays.asList(dir.listFiles((d, name) -> name.startsWith("audit"))).stream()
                        .map(f -> f.getName())
                        .collect(Collectors.toList()));
    }

    /**
     * Retreive a log file.
     * @param filename
     * @return
     */
    @Get("/{filename}")
    public SystemFile gc(String filename) {
        File file = new File(logsDir, filename);
        if (!file.exists()) {
            logger.error("File not found exception:"+filename);
            return null;
        }
        return new SystemFile(file).attach(filename);
    }
}
