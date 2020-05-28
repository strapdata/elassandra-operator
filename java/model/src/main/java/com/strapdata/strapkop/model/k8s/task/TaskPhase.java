package com.strapdata.strapkop.model.k8s.task;

public enum TaskPhase {
    WAITING,
    RUNNING,    // task is ongoing
    SUCCEED,
    FAILED,
    IGNORED;  // means the task is related a non-existing datacenter.


    public boolean isTerminated() {
        return this.equals(SUCCEED) || this.equals(FAILED) || this.equals(IGNORED);
    }
    public boolean isSucceed() { return this.equals(SUCCEED); }
}
