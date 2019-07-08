package com.strapdata.model.k8s.task;

public class BackupTask extends Task<BackupTaskSpec, TaskStatus> {
    @Override
    public void accept(TaskVisitor visitor) {
        visitor.visit(this);
    }
}
