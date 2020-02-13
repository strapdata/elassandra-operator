package com.strapdata.strapkop.model.backup;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "credentialType")
@JsonSubTypes({
        @JsonSubTypes.Type(value = GCPCloudStorageSecret.class, name = "gcp"),
        @JsonSubTypes.Type(value = AzureCloudStorageSecret.class, name = "azure"),
        @JsonSubTypes.Type(value = AWSCloudStorageSecret.class, name = "aws")})
public interface CloudStorageSecret {
}
