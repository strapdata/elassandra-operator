
package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.models.V1ListMeta;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Wither;

import java.util.ArrayList;
import java.util.List;

/**
 * A List of DataCenter objects
 * 
 */
@Data
@Wither
@AllArgsConstructor
@NoArgsConstructor
public class DataCenterList {
    
    @SerializedName("apiVersion")
    @Expose
    private String apiVersion;

    @SerializedName("kind")
    @Expose
    private String kind;

    @SerializedName("metadata")
    @Expose
    private V1ListMeta metadata;

    @SerializedName("items")
    @Expose
    private List<DataCenter> items = new ArrayList<DataCenter>();
}