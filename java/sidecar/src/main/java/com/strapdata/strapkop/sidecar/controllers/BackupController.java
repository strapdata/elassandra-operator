package com.strapdata.strapkop.sidecar.controllers;

import com.strapdata.model.backup.BackupArguments;
import com.strapdata.model.sidecar.BackupResponse;
import com.microsoft.azure.storage.StorageException;
import com.strapdata.strapkop.sidecar.services.BackupService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.reactivex.Single;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;

@Controller("/backups")
@Produces(MediaType.APPLICATION_JSON)
public class BackupController {
    private static final Logger logger = LoggerFactory.getLogger(BackupController.class);

    @Inject
    private BackupService backupService;

    @Post(value = "/", consumes = MediaType.APPLICATION_JSON)
    public Single<BackupResponse> createBackup(@Body BackupArguments backupArguments) throws URISyntaxException, StorageException, ConfigurationException, IOException, InvalidKeyException {
        return Single.create(emitter -> {
            logger.info("received backup request for {}", backupArguments.backupId);
            try {
                backupService.enqueueBackup(backupArguments);
                logger.info("enqueued backup request for {}", backupArguments.backupId);
                emitter.onSuccess(new BackupResponse("success"));
            } catch(Exception e) {
                logger.warn("Failed to get backup resonse", e);
                emitter.onError(e);
            }
         });
    }
}