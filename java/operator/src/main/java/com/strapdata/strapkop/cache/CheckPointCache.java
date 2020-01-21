package com.strapdata.strapkop.cache;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterSpec;
import io.vavr.Tuple;
import io.vavr.Tuple2;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Optional;

@Singleton
public class CheckPointCache extends Cache<Key, Tuple2<CheckPointCache.CheckPoint, CheckPointCache.CheckPoint>>  {
    private static final Logger LOGGER = LoggerFactory.getLogger(CheckPointCache.class);

    public void prepareCheckPoint(Key key, DataCenterSpec spec, Optional<String> userConfigMapRef) {
        LOGGER.debug("prepare a new RestorePoint with spec={} userConfigMap={}", spec == null ? "null" : spec.fingerprint(), userConfigMapRef);
        LOGGER.trace("prepare a new RestorePoint with spec={}", spec == null ? "null" : spec);

        if (spec != null) {
            CheckPointCache.CheckPoint ongoingCheckPoint = new CheckPointCache.CheckPoint()
                    .setSpec(spec)
                    .setUserConfigMap(userConfigMapRef.orElse(null));
            Tuple2<CheckPointCache.CheckPoint, CheckPointCache.CheckPoint> checkPoint = get(key);
            if (checkPoint != null) {
                checkPoint = checkPoint.update1(ongoingCheckPoint);
            } else {
                checkPoint = new Tuple2<>(ongoingCheckPoint, null);
            }
            put(key, checkPoint);
        }
    }

    public void commitCheckPoint(Key key, DataCenterSpec spec) {
        Tuple2<CheckPointCache.CheckPoint, CheckPointCache.CheckPoint> tuple = get(key);
        CheckPoint ongoingCheckPoint = tuple == null ? null : tuple._1;
        if ( ongoingCheckPoint != null && ongoingCheckPoint.spec != null && ongoingCheckPoint.spec.fingerprint().equals(spec.fingerprint())) {
            put(key, tuple.update2(ongoingCheckPoint).update1(null));
            LOGGER.debug("new stable RestorePoint with spec={}", spec == null ? "null" : spec.fingerprint());
        } else {
            // update only the ongoing restore point, to preserve the last committed successfully
            put(key, tuple.update1(null));
            LOGGER.info("new stable RestorePoint with spec={} can't be committed", spec == null ? "null" : spec.fingerprint());
        }
    }

    public void clearCheckPoint(Key key) {
        remove(key);
        LOGGER.info("RestorePoint removed");
    }

    public Optional<CheckPointCache.CheckPoint> getCheckPoint(Key key) {
        return Optional.ofNullable(get(key)).map(t -> t._2);
    }

    @Data
    public class CheckPoint {
        private DataCenterSpec spec;
        private String userConfigMap;
    }
}
