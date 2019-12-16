package com.strapdata.model.backup;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.*;

@Getter
@Builder
@JsonTypeName("aws")
@NoArgsConstructor
@AllArgsConstructor
@Setter
public class AWSCloudStorageSecret implements CloudStorageSecret {
    private String accessKeyId;
    private String accessKeySecret;
    private String region;
}
