package com.strapdata.strapkop.reconcilier;

import lombok.*;

import java.util.Date;

@ToString
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@With
public class ReconcileContext {
    int rackIndex = -1;
    int replica = -1;
    Operation operation = Operation.NOOP;
    Date startAt = new Date();

    enum Operation {
        NOOP,
        SCALING_UP,
        SCALING_DOWN,
        UPDATING,
        STARTING,
        STOPPING
    }
}
