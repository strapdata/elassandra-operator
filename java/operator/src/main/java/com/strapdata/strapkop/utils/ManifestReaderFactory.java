package com.strapdata.strapkop.utils;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.google.cloud.AuthCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.strapdata.backup.manifest.AWSManifestReader;
import com.strapdata.backup.manifest.AzureManifestReader;
import com.strapdata.backup.manifest.GCPManifestReader;
import com.strapdata.backup.manifest.ManifestReader;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.strapdata.model.backup.StorageProvider;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.StrapkopException;
import com.strapdata.strapkop.k8s.K8sResourceUtils;
import com.strapdata.strapkop.k8s.OperatorNames;
import io.kubernetes.client.models.V1Secret;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.naming.ConfigurationException;
import java.io.ByteArrayInputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.security.InvalidKeyException;
import java.util.Base64;

@Singleton
public class ManifestReaderFactory {

    private static final Logger logger = LoggerFactory.getLogger(ManifestReaderFactory.class);

    @Inject
    private K8sResourceUtils k8sResourceUtils;

    public ManifestReader getManifestReader(StorageProvider provider, String bucket, DataCenter dc) throws URISyntaxException, StorageException, ConfigurationException, InvalidKeyException {
        final String operatorDCName = OperatorNames.dataCenterResource(dc.getSpec().getClusterName(), dc.getSpec().getDatacenterName());
        switch (provider) {
            case AWS_S3:
                return new AWSManifestReader(getTransferManager(dc), operatorDCName, bucket);
            case AZURE_BLOB:
                return new AzureManifestReader(getCloudBlobClient(dc), operatorDCName, bucket);
            case GCP_BLOB:
                return new GCPManifestReader(getGCPStorageClient(dc), operatorDCName, bucket);
            default:
        }
        throw new ConfigurationException("Could not create Manifest Reader");
    }

    public TransferManager getTransferManager(DataCenter dc) {
        final String awsSecretName = OperatorNames.blobStoreSecretAWS(dc);
        boolean result = false;
        try {
            V1Secret awsSecret = k8sResourceUtils.readNamespacedSecret(dc.getMetadata().getNamespace(), awsSecretName).blockingGet();
            if(awsSecret.getData().containsKey("region") && awsSecret.getData().containsKey("access-key") &&awsSecret.getData().containsKey("secret-key") ) {
                final String accessKey = new String(awsSecret.getData().get("access-key"), Charset.forName("UTF-8"));
                final String secretKey = new String(awsSecret.getData().get("secret-key"), Charset.forName("UTF-8"));
                final String region = new String(awsSecret.getData().get("region"), Charset.forName("UTF-8"));
                AmazonS3 s3Client = AmazonS3ClientBuilder
                        .standard()
                        .withRegion(region)
                        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)))
                        .build();
                return TransferManagerBuilder.standard().withS3Client(s3Client).build();
            } else {
                logger.warn("AWS blob secret configured but one of values is missing (region, access-key, secret-key)");
                throw new StrapkopException("Unable to initialize AWS client, credentials are missing");
            }
        } catch (Exception e) {
            throw new StrapkopException("Unable to initialize AWS client", e);
        }
    }

    private CloudBlobClient getCloudBlobClient(DataCenter dc) throws URISyntaxException, InvalidKeyException {
        final String azureSecretName = OperatorNames.blobStoreSecretAZURE(dc);
        try {
            V1Secret azureSecret = k8sResourceUtils.readNamespacedSecret(dc.getMetadata().getNamespace(), azureSecretName).blockingGet();
            if (azureSecret.getData().containsKey("storage-account") && azureSecret.getData().containsKey("storage-key")) {
                final String account = new String(azureSecret.getData().get("storage-account"), Charset.forName("UTF-8"));
                final String secret = new String(azureSecret.getData().get("storage-key"), Charset.forName("UTF-8"));

                final String connectionString = "DefaultEndpointsProtocol=https;"
                        + String.format("AccountName=%s;", account)
                        + String.format("AccountKey=%s", secret);

                final CloudStorageAccount storageAccount = CloudStorageAccount.parse(connectionString);
                return storageAccount.createCloudBlobClient();
            } else {
                logger.warn("Azure blob secret configured but one of values is missing (storage-key, storage-account)");
                throw new StrapkopException("Unable to initialize Azure client, credentials are missing");
            }
        } catch (Exception e) {
            throw new StrapkopException("Unable to initialize Azure client", e);
        }

    }

    public Storage getGCPStorageClient(DataCenter dc) {
        String secret = null;
        try {
            final String gcpSecretName = OperatorNames.blobStoreSecretGCP(dc);
            V1Secret gcpSecret = k8sResourceUtils.readNamespacedSecret(dc.getMetadata().getNamespace(), gcpSecretName).blockingGet();
            if (gcpSecret.getData().containsKey("gcp.json") && gcpSecret.getData().containsKey("project_id")) {
                final String projectId = new String(gcpSecret.getData().get("project_id"), Charset.forName("UTF-8"));
                return StorageOptions.newBuilder()
                        .setProjectId(projectId)
                        .setAuthCredentials(AuthCredentials.createForJson(new ByteArrayInputStream(gcpSecret.getData().get("gcp.json"))))
                        .build()
                        .getService();
            } else {
                logger.warn("GCP blob secret configured but gcp.json is missing");
                throw new StrapkopException("GCP blob secret configured but gcp.json is missing");
            }
        } catch (Exception e) {
            throw new StrapkopException("Unable to initialize GCP client", e);
        }
    }
}