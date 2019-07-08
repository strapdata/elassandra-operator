package com.strapdata.model.k8s.task;

public interface TaskVisitor {
    void visit(BackupTask backupTask);
}
