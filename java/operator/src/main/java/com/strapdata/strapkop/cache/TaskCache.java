package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.task.Task;

import javax.inject.Singleton;

@Singleton
public class TaskCache extends Cache<Key, Task> {
}
