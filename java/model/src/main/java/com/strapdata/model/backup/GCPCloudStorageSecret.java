package com.strapdata.model.backup;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.*;

@Getter
@Builder
@JsonTypeName("gcp")
@NoArgsConstructor
@AllArgsConstructor
@Setter
public class GCPCloudStorageSecret implements CloudStorageSecret {
    private String projectId;
    private byte[] jsonCredentials;
}