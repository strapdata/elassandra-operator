package com.strapdata.gradle

import com.microsoft.azure.storage.CloudStorageAccount
import com.microsoft.azure.storage.blob.CloudBlobClient
import com.microsoft.azure.storage.blob.CloudBlobContainer
import com.microsoft.azure.storage.blob.CloudBlockBlob
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class AzureStorageDeployTask extends DefaultTask {

    String connectionString
    String container
    String fileToDeploy
    String mimeType

    @TaskAction
    def deploy() {
        checkArguments()

        print "connectionString=${connectionString}"
        CloudStorageAccount account = CloudStorageAccount.parse(connectionString);
        CloudBlobClient serviceClient = account.createCloudBlobClient();

        CloudBlobContainer blobContainer = serviceClient.getContainerReference(container);
        blobContainer.createIfNotExists();

        def file = project.file(fileToDeploy)

        CloudBlockBlob blob = blobContainer.getBlockBlobReference(file.name);
        if (mimeType)
            blob.getProperties().setContentType(mimeType);
        blob.upload(new FileInputStream(file), file.length());
    }

    private void checkArguments() {
        if (!connectionString) {
            throw new IllegalArgumentException("The 'connectionString' property is missing");
        }

        if (!container) {
            throw new IllegalArgumentException("The 'container' property is missing");
        }

        if (!fileToDeploy) {
            throw new IllegalArgumentException("The 'fileToDeploy' property is missing");
        }
    }
}