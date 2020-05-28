package com.strapdata.strapkop.model.k8s.datacenter;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.util.List;

/**
 * keyspace: The name of the table keyspace.
 * tables: The name of the targeted tables (column families) as comma separated list. If no tables given, then the whole keyspace is targeted. (Optional)
 * owner: Owner name for the schedule. This could be any string identifying the owner.
 * segmentCount: Defines the amount of segments to create for scheduled repair runs. (Optional)
 * repairParallelism: Defines the used repair parallelism for scheduled repair runs. (Optional)
 * intensity: Defines the repair intensity for scheduled repair runs. (Optional)
 * incrementalRepair: Defines if incremental repair should be done. true/false
 * scheduleDaysBetween: Defines the amount of days to wait between scheduling new repairs. For example, use value 7 for weekly schedule, and 0 for continuous.
 * scheduleTriggerTime: Defines the time for first scheduled trigger for the run. If you don’t give this value, it will be next mid-night (UTC). Give date values in ISO format, e.g. “2015-02-11T01:00:00”. (Optional)
 * nodes : a specific list of nodes whose tokens should be repaired. (Optional)
 * datacenters : a specific list of datacenters to repair. (Optional)
 * blacklistedTables : The name of the tables that should not be repaired. Cannot be used in conjunction with the tables parameter. (Optional)
 * repairThreadCount : Since Cassandra 2.2, repairs can be performed with up to 4 threads in order to parallelize the work on different token ranges. (Optional)
 */
@Data
@With
@AllArgsConstructor
@NoArgsConstructor
public class ReaperScheduledRepair {

    @SerializedName("owner")
    @Expose
    private String owner;

    @SerializedName("keyspace")
    @Expose
    private String keyspace;

    @SerializedName("tables")
    @Expose
    private List<String> tables;

    @SerializedName("blacklistedTables")
    @Expose
    private List<String> blacklistedTables;

    @SerializedName("segmentCount")
    @Expose
    private Integer segmentCount = null;

    @SerializedName("repairParallelism")
    @Expose
    private ReaperParallelism repairParallelism = ReaperParallelism.PARALLEL;

    @SerializedName("intensity")
    @Expose
    private Double intensity = 1.0;

    @SerializedName("incrementalRepair")
    @Expose
    private Boolean incrementalRepair = false;

    @SerializedName("scheduleDaysBetween")
    @Expose
    private Integer scheduleDaysBetween = 0;

    @SerializedName("scheduleTriggerTime")
    @Expose
    private String scheduleTriggerTime = null;

    @SerializedName("repairThreadCount")
    @Expose
    private Integer repairThreadCount = 0;

}
