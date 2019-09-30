package com.strapdata.strapkop.sidecar.controllers;

import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.sidecar.BackupResponse;
import com.strapdata.strapkop.sidecar.services.BackupService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * Create Cassandra backups
 */
//@Tag(name = "backups")
@Controller("/backups")
@Produces(MediaType.APPLICATION_JSON)
public class BackupController {
    private static final Logger logger = LoggerFactory.getLogger(BackupController.class);

    @Inject
    private BackupService backupService;

    @Post(consumes = MediaType.APPLICATION_JSON)
    public BackupResponse createBackup(@Body BackupArguments backupArguments) {
        backupService.enqueueBackup(backupArguments);
        return new BackupResponse().setStatus("success");
    }
}