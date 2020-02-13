package com.strapdata.strapkop.model.k8s.task;

import lombok.Data;
import lombok.experimental.Wither;

/**
 * Task to decommmission a datacenter (remove DC from replication map, decommission all nodes)
 */
@Data
@Wither
public class DecommissionTaskSpec {

}
