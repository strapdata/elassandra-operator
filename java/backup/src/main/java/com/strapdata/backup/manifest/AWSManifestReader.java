package com.strapdata.backup.manifest;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.strapdata.backup.common.AWSRemoteObjectReference;
import com.strapdata.backup.common.RemoteObjectReference;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import io.vavr.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

public class AWSManifestReader extends ManifestReader {
    private static final Logger logger = LoggerFactory.getLogger(AWSManifestReader.class);

    private final AmazonS3 amazonS3;
    private final TransferManager transferManager;

    public AWSManifestReader(final TransferManager transferManager, String restoreFromClusterId, String restoreFromBackupBucket) {
        super(restoreFromClusterId, restoreFromBackupBucket);
        this.amazonS3 = transferManager.getAmazonS3Client();
        this.transferManager = transferManager;
    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(Path objectKey) throws Exception {
        return new AWSRemoteObjectReference(objectKey, resolveRemotePath(objectKey));
    }

    public GlobalManifest aggregateManifest(final String manifestName) {

        final ListObjectsRequest listObjectRequest = new ListObjectsRequest().
                withBucketName(this.restoreFromBackupBucket).
                withPrefix(getPrefix())
                .withDelimiter("/");

        final List<String> nodeNames = amazonS3.listObjects(listObjectRequest).getCommonPrefixes();

        return Observable.fromIterable(() -> nodeNames.iterator()).observeOn(Schedulers.io())
                .doOnNext(nodePath -> logger.debug("AggregateManifest found nodePath '{}'", nodePath))
                .map(nodePath -> {
                    final String nodeManifestPath = nodePath + "manifests/" + manifestName;
                    try {
                    ObjectMetadata meta = amazonS3.getObjectMetadata(this.restoreFromBackupBucket, nodeManifestPath);
                        logger.debug("Manifest '{}' found for nodePath '{}'", manifestName, nodePath);
                        return new Tuple2<String, String>(nodePath.split("/")[1], "manifests/" + manifestName);
                    } catch (AmazonS3Exception e) {
                        if (e.getStatusCode() == 404) {
                            logger.debug("Manifest '{}' not found for nodePath '{}'", manifestName, nodePath);
                            return new Tuple2<String, String>(nodePath.split("/")[1], NOMANIFEST);
                        } else {
                            throw new RuntimeException(e);
                        }
                    }
                })
                .filter(tuple -> !NOMANIFEST.equals(tuple._2)).collect(
                () -> new GlobalManifest(this.restoreFromClusterId, manifestName),
                (globalManifest, tuple) -> globalManifest.addManifest(tuple._1, tuple._2)
        ).blockingGet();
    }

}
