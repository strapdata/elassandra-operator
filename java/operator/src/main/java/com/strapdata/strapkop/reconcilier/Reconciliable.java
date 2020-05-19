package com.strapdata.strapkop.reconcilier;

import com.strapdata.strapkop.event.K8sWatchEvent;
import io.reactivex.Completable;
import lombok.*;

import java.math.BigInteger;

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

    BigInteger id;

    Long submitTime;
    Long startTime;

    public static enum Kind  {
        TASK,
        DATACENTER,
        STATEFULSET,
        ELASSANDRA_POD
    }
}
