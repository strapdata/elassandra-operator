package com.strapdata.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Cassandra reaper default configuration.
 */
@Data
@NoArgsConstructor
public class Kibana {

    /**
     * Reaper docker image;
     */
    @SerializedName("image")
    @Expose
    private String image  = "docker.elastic.co/kibana/kibana-oss:6.2.3";

    /**
     * Kibana user spaces (key = space name)
     */
    @SerializedName("spaces")
    @Expose
    private List<KibanaSpace> spaces = new ArrayList<>();

}
