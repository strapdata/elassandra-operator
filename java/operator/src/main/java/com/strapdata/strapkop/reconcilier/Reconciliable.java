package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.event.K8sWatchEvent;
import io.reactivex.Completable;
import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
@With
public class Reconciliable {

    @ToString.Exclude
    Completable completable;

    Kind kind;

    K8sWatchEvent.Type type;

    String resourceVersion;

    Long submitTime;
    Long startTime;

    public static enum Kind  {
        TASK,
        DATACENTER,
        STATEFULSET,
        ELASSANDRA_POD,
        NODE;
    }
}
