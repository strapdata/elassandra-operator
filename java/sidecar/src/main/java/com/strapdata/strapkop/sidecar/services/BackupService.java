package com.strapdata.strapkop.sidecar.services;

import com.instaclustr.backup.task.BackupTask;
import com.instaclustr.backup.util.GlobalLock;
import com.strapdata.model.backup.BackupArguments;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Infrastructure;
import io.reactivex.Completable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Context
@Infrastructure
public class BackupService {
    
    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);
    
    private BehaviorSubject<BackupArguments> subject = BehaviorSubject.create();
    private Disposable disposable;
    
    public BackupService() {
        logger.info("Initializing BackupService");
        disposable = subject.observeOn(Schedulers.newThread())
                .doOnNext(backupArguments -> logger.info("received backup request for {}", backupArguments.snapshotTag))
                .doOnNext(backupArguments -> logger.debug("processing backup on thread {}", Thread.currentThread().getName()))
                .map(backupArguments -> new BackupTask(backupArguments, new GlobalLock("/tmp")))
                .doOnNext(BackupTask::call)
                .retry()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        backupTask -> logger.info("backup {} has completed", backupTask.getArguments().snapshotTag),
                        Throwable::printStackTrace);
    }
    
    public void enqueueBackup(final BackupArguments arguments) {
        subject.onNext(arguments);
    }

    // TODO: bind stop event to gracefully shutdown backups
}
