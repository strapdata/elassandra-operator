package com.strapdata.strapkop.backup.common;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.google.cloud.AuthCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.strapdata.strapkop.backup.downloader.*;
import com.strapdata.strapkop.backup.uploader.*;
import com.strapdata.strapkop.model.backup.*;

import javax.naming.ConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.InvalidKeyException;
import java.util.Optional;

public class CloudDownloadUploadFactory {

    public static TransferManager getTransferManager(final CloudStorageSecret secret) {
        if (secret == null) {
            /*
             * Environment Variables - AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY (RECOMMENDED since they are recognized by all the AWS SDKs and CLI except for .NET), or AWS_ACCESS_KEY and AWS_SECRET_KEY (only recognized by Java SDK)
             * Java System Properties - aws.accessKeyId and aws.secretKey
             * Credential profiles file at the default location (~/.aws/credentials) shared by all AWS SDKs and the AWS CLI
             * Credentials delivered through the Amazon EC2 container service if AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" environment variable is set and security manager has permission to access the variable,
             * Instance profile credentials delivered through the Amazon EC2 metadata service
             */
            return TransferManagerBuilder.defaultTransferManager();
        } else {
            final String accessKey = ((AWSCloudStorageSecret) secret).getAccessKeyId();
            final String secretKey = ((AWSCloudStorageSecret) secret).getAccessKeySecret();
            final String region = ((AWSCloudStorageSecret) secret).getRegion();
            AmazonS3 s3Client = AmazonS3ClientBuilder
                    .standard()
                    .withRegion(region)
                    .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                    .build();
            return TransferManagerBuilder.standard().withS3Client(s3Client).build();
        }
    }

    public static CloudBlobClient getCloudBlobClient(final CloudStorageSecret secret) throws URISyntaxException, InvalidKeyException {
        String connectionString;
        if (secret == null) {
            // Seems that the azure-storage java SDK does not support credentials discovery. However az cli does support this
            // so here we try to reproduce the same behavior using AZURE_STORAGE_ACCOUNT and AZURE_STORAGE_KEY
            final String accountName = System.getenv("AZURE_STORAGE_ACCOUNT");
            String accessKey = System.getenv("AZURE_STORAGE_KEY");
            if (accessKey == null) {
                // az cli also uses AZURE_STORAGE_ACCESS_KEY because of a bug : https://github.com/MicrosoftDocs/azure-docs/issues/14365
                accessKey = System.getenv("AZURE_STORAGE_ACCESS_KEY");
            }

            connectionString = "DefaultEndpointsProtocol=https;"
                    + String.format("AccountName=%s;", accountName)
                    + String.format("AccountKey=%s", accessKey);
        } else {

            connectionString = "DefaultEndpointsProtocol=https;"
                    + String.format("AccountName=%s;", ((AzureCloudStorageSecret)secret).getAccountName())
                    + String.format("AccountKey=%s", ((AzureCloudStorageSecret)secret).getAccountKey());
        }

        final CloudStorageAccount account = CloudStorageAccount.parse(connectionString);
        return account.createCloudBlobClient();
    }

    public static Storage getGCPStorageClient(final CloudStorageSecret secret) throws IOException {
        if (secret == null) {
            /*
             * Instance profile,
             * GOOGLE_APPLICATION_CREDENTIALS env var, or
             * application_default_credentials.json default
             */
            return StorageOptions.getDefaultInstance().getService();
        } else {
            return StorageOptions.newBuilder()
                    .setProjectId(((GCPCloudStorageSecret)secret).getProjectId())
                    .setAuthCredentials(AuthCredentials.createForJson(new ByteArrayInputStream(((GCPCloudStorageSecret)secret).getJsonCredentials())))
                    .build()
                    .getService();
        }
    }

    public static SnapshotUploader getUploader(final BackupArguments arguments) throws IOException, URISyntaxException, StorageException, ConfigurationException, InvalidKeyException {
        final String rootBackupDir = System.getenv(Constants.ENV_ROOT_BACKUP_DIR);
        final String namespace = Optional.ofNullable(System.getenv(Constants.ENV_NAMESPACE)).orElse("default");
        //final String backupID, final String clusterID, final String backupBucket,
        switch (arguments.storageProvider) {
            case AWS_S3:
                //TODO: support encrypted backups via KMS
                //AWS client set to auto detect credentials
                return new AWSSnapshotUploader(getTransferManager(arguments.cloudCredentials), arguments, rootBackupDir, namespace);
            case AZURE_BLOB:
                //TODO: use SAS token?
                return new AzureSnapshotUploader(getCloudBlobClient(arguments.cloudCredentials), arguments, rootBackupDir, namespace);
            case GCP_BLOB:
                return new GCPSnapshotUploader(getGCPStorageClient(arguments.cloudCredentials), arguments, rootBackupDir, namespace);
            case FILE:
                return new LocalFileSnapShotUploader(arguments, rootBackupDir, namespace);
        }
        throw new ConfigurationException("Could not create Snapshot Uploader");
    }

    public static Downloader getDownloader(final RestoreArguments arguments) throws IOException, URISyntaxException, StorageException, ConfigurationException, InvalidKeyException {
        final String rootBackupDir = System.getenv(Constants.ENV_ROOT_BACKUP_DIR);
        switch (arguments.storageProvider) {
            case AWS_S3:
                //TODO: support encrypted backups via KMS
                //AWS client set to auto detect credentials
                return new AWSDownloader(getTransferManager(arguments.cloudCredentials), arguments, rootBackupDir, arguments.namespace);
            case AZURE_BLOB:
                //TODO: use SAS token?
                return new AzureDownloader(getCloudBlobClient(arguments.cloudCredentials), arguments, rootBackupDir, arguments.namespace);
            case GCP_BLOB:
                return new GCPDownloader(getGCPStorageClient(arguments.cloudCredentials), arguments, rootBackupDir, arguments.namespace);
            case FILE:
                return new LocalFileDownloader(arguments, rootBackupDir, arguments.namespace);
        }
        throw new ConfigurationException("Could not create Snapshot Uploader");
    }

}
