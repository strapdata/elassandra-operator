package com.strapdata.strapkop.utils;

import com.strapdata.model.k8s.cassandra.DataCenterSpec;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Simple class to keep in memory the last stable DC configuration (DCSpec and UserConfigMap)
 * Because DC reconciliations are performed by a single thread work queue, it is safe to not synchronize the static object
 */
public class RestorePointCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(RestorePointCache.class);

    private static RestorePoint restorePoint = null;
    private static RestorePoint ongoingRestorePoint = null;

    public static void prepareRestorePoint(DataCenterSpec spec, Optional<String> userConfigMapRef) {
        LOGGER.debug("prepare a new RestorePoint with spec={} userConfigMap={}", spec == null ? "null" : spec.fingerprint(), userConfigMapRef);
        LOGGER.trace("prepare a new RestorePoint with spec={}", spec == null ? "null" : spec);

        if (spec != null) {
            ongoingRestorePoint = new RestorePoint()
                    .setSpec(spec)
                    .setUserConfigMap(userConfigMapRef.orElse(null));
        }
    }

    public static void commitRestorePoint(DataCenterSpec spec) {
        if (ongoingRestorePoint != null && ongoingRestorePoint.spec != null && ongoingRestorePoint.spec.fingerprint().equals(spec.fingerprint())) {
            restorePoint = ongoingRestorePoint;
            ongoingRestorePoint = null;
            LOGGER.debug("new stable RestorePoint with spec={}", spec == null ? "null" : spec.fingerprint());
        } else {
            // update only the ongoing restore point, to preserve the last committed successfully
            ongoingRestorePoint = null;
            LOGGER.info("new stable RestorePoint with spec={} can't be committed", spec == null ? "null" : spec.fingerprint());
        }
    }

    public static void clearRestorePoint() {
        restorePoint = null;
        ongoingRestorePoint = null;
        LOGGER.info("RestorePoint removed");
    }

    public static Optional<RestorePoint> getRestorePoint() {
        return Optional.ofNullable(restorePoint);
    }

    @Data
    public static class RestorePoint {
        private DataCenterSpec spec;
        private String userConfigMap;
    }
}
