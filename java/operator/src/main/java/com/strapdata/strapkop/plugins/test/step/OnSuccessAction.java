package com.strapdata.strapkop.plugins.test.step;

import com.strapdata.model.k8s.cassandra.DataCenter;

@FunctionalInterface
public interface OnSuccessAction {
    Step execute(DataCenter dc) throws StepFailedException;
}
