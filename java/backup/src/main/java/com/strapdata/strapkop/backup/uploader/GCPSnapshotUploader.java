package com.strapdata.strapkop.backup.uploader;

import com.google.cloud.WriteChannel;
import com.google.cloud.storage.*;
import com.google.common.io.ByteStreams;
import com.google.inject.Inject;
import com.strapdata.strapkop.backup.common.Constants;
import com.strapdata.strapkop.backup.common.GCPRemoteObjectReference;
import com.strapdata.strapkop.backup.common.RemoteObjectReference;
import com.strapdata.strapkop.model.backup.BackupArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;


public class GCPSnapshotUploader extends SnapshotUploader{
    private static final Logger logger = LoggerFactory.getLogger(GCPSnapshotUploader.class);

    private final Storage storage;

    @Inject
    public GCPSnapshotUploader(final Storage storage,
                               final BackupArguments arguments,
                               final String rootBackupDir,
                               final String namespace) {
        super(rootBackupDir, namespace, arguments.clusterId, arguments.backupId, arguments.backupBucket);
        this.storage = storage;

    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) throws Exception {
        return new GCPRemoteObjectReference(objectKey, resolveRemotePath(objectKey), restoreFromBackupBucket);
    }

    @Override
    public RemoteObjectReference taskDescriptionRemoteReference(String taskName) throws Exception {
        // objectKey is kept simple (e.g. "manifests/autosnap-123") so that it directly reflects the local path
        return new GCPRemoteObjectReference(Paths.get(Constants.TASK_DESCRIPTION_DOWNLOAD_DIR).resolve(taskName), resolveTaskDescriptionRemotePath(taskName), restoreFromBackupBucket);
    }

    @Override
    public FreshenResult freshenRemoteObject(final RemoteObjectReference object) throws Exception {
        final BlobId blobId = ((GCPRemoteObjectReference) object).blobId;

        try {
            storage.copy(new Storage.CopyRequest.Builder()
                    .setSource(blobId)
                    .setTarget(BlobInfo.newBuilder(blobId).build(),
                            Storage.BlobTargetOption.predefinedAcl(Storage.PredefinedAcl.BUCKET_OWNER_FULL_CONTROL)
                    )
                    .build()
            );

            return FreshenResult.FRESHENED;

        } catch (final StorageException e) {
            if (e.getCode() != 404)
                throw e;

            return FreshenResult.UPLOAD_REQUIRED;
        }
    }

    @Override
    public void uploadSnapshotFile(final long size, final InputStream localFileStream, final RemoteObjectReference object) throws Exception {
        final BlobId blobId = ((GCPRemoteObjectReference) object).blobId;

        try (final WriteChannel outputChannel = storage.writer(BlobInfo.newBuilder(blobId).build(), Storage.BlobWriteOption.predefinedAcl(Storage.PredefinedAcl.BUCKET_OWNER_FULL_CONTROL));
             final ReadableByteChannel inputChannel = Channels.newChannel(localFileStream)) {

            ByteStreams.copy(inputChannel, outputChannel);
        }
    }

    @Override
    public void cleanup() throws Exception {
    }
}