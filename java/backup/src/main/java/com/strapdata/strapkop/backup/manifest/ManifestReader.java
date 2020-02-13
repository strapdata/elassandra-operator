package com.strapdata.strapkop.backup.manifest;

import com.google.common.base.Strings;
import com.strapdata.strapkop.backup.common.StorageInteractor;

import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class ManifestReader extends StorageInteractor {
    protected static final String NOMANIFEST = "#noman#";

    public ManifestReader(String rootBackupDir, String restoreFromNamespace, String restoreFromClusterId, String restoreFromBackupBucket) {
        super(rootBackupDir, restoreFromNamespace, restoreFromClusterId, null, restoreFromBackupBucket);
    }

    @Override
    public String resolveRemotePath(final Path objectKey) {
        return Strings.isNullOrEmpty(restoreFromRootDir) ? Paths.get(restoreFromClusterId).resolve(objectKey).toString() :
                Paths.get(restoreFromRootDir).resolve(restoreFromClusterId).resolve(objectKey).toString() ;
    }

    public abstract GlobalManifest aggregateManifest(String manifestName);

    protected String getPrefix() {
        return this.restoreFromClusterId+"/";
    }
}
