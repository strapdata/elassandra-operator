package com.strapdata.strapkop.backup.downloader;

import com.strapdata.strapkop.backup.common.Constants;
import com.strapdata.strapkop.model.backup.RestoreArguments;
import com.strapdata.strapkop.backup.common.AzureRemoteObjectReference;
import com.strapdata.strapkop.backup.common.RemoteObjectReference;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

public class AzureDownloader extends Downloader {
    private static final Logger logger = LoggerFactory.getLogger(AzureDownloader.class);

    private final CloudBlobContainer blobContainer;

    public AzureDownloader(final CloudBlobClient cloudBlobClient,
                               final RestoreArguments arguments,
                           final String rootBackupDir,
                           final String namespace) throws StorageException, URISyntaxException {
        super(rootBackupDir, namespace, arguments);
        this.blobContainer = cloudBlobClient.getContainerReference(restoreFromBackupBucket);
    }
    
    @Override
    public RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) throws StorageException, URISyntaxException {
        final String path = resolveRemotePath(objectKey);
        return new AzureRemoteObjectReference(objectKey, path, blobContainer.getBlockBlobReference(path));
    }

    @Override
    public RemoteObjectReference taskDescriptionRemoteReference(String taskName) throws Exception {
        final String path = resolveTaskDescriptionRemotePath(taskName);
        return new AzureRemoteObjectReference(Paths.get(Constants.TASK_DESCRIPTION_DOWNLOAD_DIR).resolve(taskName), path, blobContainer.getBlockBlobReference(path));
    }

    @Override
    public void downloadFile(final Path localPath, final RemoteObjectReference object) throws Exception {
        final CloudBlockBlob blob = ((AzureRemoteObjectReference) object).blob;
        logger.info("download file with azure remote_path={}", blob.getUri());
        Files.createDirectories(localPath.getParent());
        blob.downloadToFile(localPath.toAbsolutePath().toString());
    }

    @Override
    public List<RemoteObjectReference> listFiles(final RemoteObjectReference prefix) throws Exception {
        final AzureRemoteObjectReference azureRemoteObjectReference = (AzureRemoteObjectReference) prefix;
        final String blobPrefix = Paths.get(restoreFromClusterId).resolve(restoreFromNodeId).resolve(azureRemoteObjectReference.getObjectKey()).toString();

        List<RemoteObjectReference> fileList = new ArrayList<>();

        Pattern containerPattern = Pattern.compile("^/" + restoreFromClusterId + "/" + restoreFromClusterId + "/" + restoreFromNodeId + "/");

        Iterable<ListBlobItem> blobItemsIterable = blobContainer.listBlobs(blobPrefix, true, EnumSet.noneOf(BlobListingDetails.class), null, null);
        Iterator<ListBlobItem> blobItemsIterator = blobItemsIterable.iterator();

        while (blobItemsIterator.hasNext()) {
            ListBlobItem listBlobItem = blobItemsIterator.next();

            try {
                fileList.add(objectKeyToRemoteReference(Paths.get(containerPattern.matcher(listBlobItem.getUri().getPath()).replaceFirst(""))));
            } catch (StorageException | URISyntaxException e) {
                logger.error("Failed to generate objectKey for blob item \"{}\".", listBlobItem.getUri(), e);

                throw e;
            }
        }

        return fileList;
    }

    @Override
    void cleanup() throws Exception {
        // Nothing to cleanup
    }
}
