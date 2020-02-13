package com.strapdata.strapkop.model.k8s.cassandra;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashSet;
import java.util.Set;

/**
 * Reconciliation block
 */
@Data
@NoArgsConstructor
public class Block {

    /**
     * Reconciliation locked;
     */
    @SerializedName("locked")
    @Expose
    private boolean locked  = false;

    /**
     * Block reasons
     */
    @SerializedName("reasons")
    @Expose
    private Set<BlockReason> reasons = new HashSet<>();

}
