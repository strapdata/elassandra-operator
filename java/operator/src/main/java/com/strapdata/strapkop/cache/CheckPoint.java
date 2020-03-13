package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenterSpec;
import lombok.*;
import lombok.experimental.Wither;

@Data
@Wither
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class CheckPoint {
    private DataCenterSpec committedSpec;    // last successfully applied spec
    private String committedUserConfigMap;   // last successfully applied configmap
}
