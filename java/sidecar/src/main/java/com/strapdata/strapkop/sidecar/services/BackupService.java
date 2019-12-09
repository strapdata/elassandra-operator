package com.strapdata.strapkop.sidecar.services;

import com.instaclustr.backup.task.BackupTask;
import com.instaclustr.backup.util.GlobalLock;
import com.microsoft.azure.storage.StorageException;
import com.strapdata.model.backup.BackupArguments;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import jmx.org.apache.cassandra.service.StorageServiceMBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;

@Context
@Infrastructure
public class BackupService {
    
    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);
    
    private final StorageServiceMBean storageServiceMBean;
    
    private BehaviorSubject<BackupTask> subject = BehaviorSubject.create();
    private Disposable disposable;
    
    public BackupService(StorageServiceMBean storageServiceMBean) {
        this.storageServiceMBean = storageServiceMBean;
        logger.info("Initializing BackupService");
        disposable = subject.observeOn(Schedulers.newThread())
                .doOnNext(task -> logger.info("Start backup upload for snapshot '{}'", task.getArguments().snapshotTag))
                .doOnNext(task -> logger.debug("processing backup upload for snapshot '{}' on thread {}", task.getArguments().snapshotTag, Thread.currentThread().getName()))
                .doOnNext(task -> {
                    try {
                        task.performBackup();
                    }
                    catch (Throwable throwable) {
                        logger.error("error while processing backup", throwable);
                    }
                })
                //.retry()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        backupTask -> logger.info("backup {} has completed", backupTask.getArguments().snapshotTag),
                        Throwable::printStackTrace);
    }
    
    public void enqueueBackup(final BackupArguments arguments) {
        // Create the BackupTask
        logger.info("received backup request for {}", arguments.snapshotTag);
        BackupTask task;
        GlobalLock globalLock;
        try {
            globalLock = new GlobalLock("/tmp");
            task = new BackupTask(arguments, globalLock, this.storageServiceMBean);
            globalLock.getLock(arguments.waitForLock);
        } catch (IOException|StorageException|ConfigurationException|URISyntaxException|InvalidKeyException e) {
            logger.error("Unable to create BackupTask : {} ", e.getMessage(), e);
            return;
        }

        // snapshot in synchronous manner and then backup if snapshot succeeded
        if (task.performSnapshot()) {
            // submit the task to upload the SSTable in async manner
            subject.onNext(task);
        }
    }

    // TODO: bind stop event to gracefully shutdown backups
}
