package com.strapdata.model.k8s.task;

public enum TaskPhase {
    WAITING,
    RUNNING,
    SUCCEED,
    FAILED;

    public boolean isTerminated() {
        return this.equals(SUCCEED) || this.equals(FAILED);
    }
}
