package com.strapdata.strapkop.sidecar.services;

import com.google.common.util.concurrent.AbstractIdleService;
import com.instaclustr.model.backup.BackupArguments;
import com.instaclustr.backup.task.BackupTask;
import com.instaclustr.backup.util.GlobalLock;
import com.microsoft.azure.storage.StorageException;

import javax.inject.Singleton;
import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Singleton
public class BackupService extends AbstractIdleService {
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public void enqueueBackup(final BackupArguments arguments) throws IOException, StorageException, ConfigurationException, URISyntaxException, InvalidKeyException {
        final BackupTask backupTask = new BackupTask(arguments, new GlobalLock("/tmp"));
        executorService.submit(backupTask);
    }

    @Override
    protected void startUp() throws Exception {
    }

    @Override
    protected void shutDown() throws Exception {
        // TODO: gracefully stop any running backups
    }
}
