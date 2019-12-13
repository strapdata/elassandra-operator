package com.strapdata.backup.manifest;

import com.strapdata.backup.common.StorageInteractor;

import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class ManifestReader extends StorageInteractor {
    protected static final String NOMANIFEST = "#noman#";

    public ManifestReader(String restoreFromClusterId, String restoreFromBackupBucket) {
        super(restoreFromClusterId, null, restoreFromBackupBucket);
    }

    @Override
    public String resolveRemotePath(final Path objectKey) {
        return Paths.get(restoreFromClusterId).resolve(objectKey).toString();
    }

    public abstract GlobalManifest aggregateManifest(String manifestName);


    protected String getPrefix() {
        return this.restoreFromClusterId+"/";
    }
}
