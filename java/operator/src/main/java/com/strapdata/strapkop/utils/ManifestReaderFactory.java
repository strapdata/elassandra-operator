package com.strapdata.strapkop.utils;

import com.google.common.base.Strings;
import com.microsoft.azure.storage.StorageException;
import com.strapdata.backup.common.Constants;
import com.strapdata.backup.manifest.AWSManifestReader;
import com.strapdata.backup.manifest.AzureManifestReader;
import com.strapdata.backup.manifest.GCPManifestReader;
import com.strapdata.backup.manifest.ManifestReader;
import com.strapdata.model.backup.CloudStorageSecret;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.Restore;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.models.V1EnvVar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.naming.ConfigurationException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;

import static com.strapdata.backup.common.CloudDownloadUploadFactory.*;

@Singleton
public class ManifestReaderFactory {

    private static final Logger logger = LoggerFactory.getLogger(ManifestReaderFactory.class);

    public ManifestReader getManifestReader(DataCenter dc, Restore restore, CloudStorageSecret secret)
            throws IOException, URISyntaxException, StorageException, ConfigurationException, InvalidKeyException {

        final String operatorDCName = OperatorNames.dataCenterResource(dc.getSpec().getClusterName(), dc.getSpec().getDatacenterName());

        String backupDir = restore.getBackupDir();
        if (Strings.isNullOrEmpty(backupDir) && dc.getSpec().getEnv() != null) {
            for (V1EnvVar evar : dc.getSpec().getEnv()) {
                if (Constants.ENV_ROOT_BACKUP_DIR.equals(evar.getName())) {
                    backupDir = evar.getValue();
                }
            }
        }

        logger.debug("Load manifest from '{}' directory.", backupDir);
        switch (restore.getProvider()) {
            case AWS_S3:
                return new AWSManifestReader(getTransferManager(secret), backupDir, restore.getNamespace(), operatorDCName, restore.getBucket());
            case AZURE_BLOB:
                return new AzureManifestReader(getCloudBlobClient(secret), backupDir, restore.getNamespace(), operatorDCName, restore.getBucket());
            case GCP_BLOB:
                return new GCPManifestReader(getGCPStorageClient(secret), backupDir, restore.getNamespace(), operatorDCName, restore.getBucket());
            default:
        }
        throw new ConfigurationException("Could not create Manifest Reader");
    }
}