package com.strapdata.strapkop.model.fabric8.task;

import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.client.CustomResourceDoneable;

public class DoneableTask extends CustomResourceDoneable<Task> {
    public DoneableTask(Task resource, Function<Task, Task> function) {
        super(resource, function);
    }
}
