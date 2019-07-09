package com.strapdata.strapkop.cache;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.task.BackupTask;

import javax.inject.Singleton;

@Singleton
public class BackupCache extends Cache<Key, BackupTask> {
}
