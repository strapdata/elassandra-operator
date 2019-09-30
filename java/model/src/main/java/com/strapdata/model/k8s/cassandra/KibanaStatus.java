package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

@Data
@NoArgsConstructor
public class KibanaStatus {

    public static final String DEFAULT_SPACE = "";

    /**
     * Deployed kibana spaces (default = "")
     */
    @SerializedName("spaces")
    private Set<String> spaces = new HashSet<>();
}
