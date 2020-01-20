package com.strapdata.backup.uploader;

import com.strapdata.backup.common.RemoteObjectReference;
import com.strapdata.backup.common.StorageInteractor;

import java.io.InputStream;
import java.nio.file.Path;

public abstract class SnapshotUploader extends StorageInteractor implements AutoCloseable {
    public SnapshotUploader(String rootBackupDir, String restoreFromClusterId, String restoreFromNodeId, String restoreFromBackupBucket) {
        super(rootBackupDir, restoreFromClusterId, restoreFromNodeId, restoreFromBackupBucket);
    }

    public abstract RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) throws Exception;

    public abstract RemoteObjectReference taskDescriptionRemoteReference(final String taskFile) throws Exception;

    public enum FreshenResult {
        FRESHENED,
        UPLOAD_REQUIRED
    }

    public abstract FreshenResult freshenRemoteObject(final RemoteObjectReference object) throws Exception;

    public abstract void uploadSnapshotFile(final long size, final InputStream localFileStream, final RemoteObjectReference object) throws Exception;

    abstract void cleanup() throws Exception;

    private boolean isClosed = false;

    @Override
    public final void close() throws Exception {
        if (isClosed)
            return;

        isClosed = true;
        cleanup();
    }
}
