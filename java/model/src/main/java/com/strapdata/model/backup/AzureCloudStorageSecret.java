package com.strapdata.model.backup;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.*;

@Getter
@Builder
@JsonTypeName("azure")
@NoArgsConstructor
@AllArgsConstructor
@Setter
public class AzureCloudStorageSecret implements CloudStorageSecret {
    private String accountName;
    private String accountKey;

}
