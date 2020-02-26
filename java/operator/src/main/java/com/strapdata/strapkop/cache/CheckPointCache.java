package com.strapdata.strapkop.cache;

import com.strapdata.strapkop.k8s.OperatorNames;
import com.strapdata.strapkop.model.Key;
import com.strapdata.strapkop.model.k8s.cassandra.DataCenterSpec;
import lombok.*;
import lombok.experimental.Wither;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import java.util.Optional;

@Singleton
public class CheckPointCache extends Cache<Key, CheckPointCache.CheckPoint>  {
    private static final Logger LOGGER = LoggerFactory.getLogger(CheckPointCache.class);

    @Data
    @Wither
    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public class CheckPoint {
        private DataCenterSpec committedSpec;    // last successfully applied spec
        private String committedUserConfigMap;   // last successfully applied configmap

        private DataCenterSpec nextCurrentSpec;
        private String nextUserConfigMap;

        public CheckPoint prepare(DataCenterSpec nextCurrentSpec, String nextUserConfigMap) {
            LOGGER.debug("dc={} prepare next spec={} userConfigMap={}",
                    OperatorNames.dataCenterResource(nextCurrentSpec.getClusterName(), nextCurrentSpec.getDatacenterName()),
                    nextCurrentSpec == null ? "null" : nextCurrentSpec.fingerprint(), nextUserConfigMap);
            LOGGER.trace("prepare next spec={}", nextCurrentSpec == null ? "null" : nextCurrentSpec);
            this.nextCurrentSpec = nextCurrentSpec;
            this.nextUserConfigMap = nextUserConfigMap;
            return this;
        }

        public CheckPoint commit() {
            LOGGER.debug("dc={} commit new spec={} userConfigMap={}",
                    OperatorNames.dataCenterResource(nextCurrentSpec.getClusterName(), nextCurrentSpec.getDatacenterName()),
                    nextCurrentSpec == null ? "null" : nextCurrentSpec.fingerprint(), nextUserConfigMap);
            LOGGER.trace("commit new spec={}", nextCurrentSpec == null ? "null" : nextCurrentSpec);
            if (nextCurrentSpec != null)
                this.committedSpec = this.nextCurrentSpec;
            if (nextUserConfigMap != null)
                this.committedUserConfigMap = this.nextUserConfigMap;
            return this;
        }

        public CheckPoint rollback() {
            LOGGER.debug("dc={} rollback to spec={} userConfigMap={}",
                    OperatorNames.dataCenterResource(nextCurrentSpec.getClusterName(), nextCurrentSpec.getDatacenterName()),
                    committedSpec == null ? "null" : committedSpec.fingerprint(), committedUserConfigMap);
            LOGGER.trace("rollback to spec={}", committedSpec == null ? "null" : committedSpec);
            this.nextCurrentSpec = null;
            this.nextUserConfigMap = null;
            return this;
        }
    }

    public CheckPoint prepareCheckPoint(Key key, DataCenterSpec spec, Optional<String> userConfigMapRef) {
        if (spec != null) {
            return compute(key, (k, v) -> {
                if (v == null)
                    v = new CheckPoint().withCommittedSpec(spec).withCommittedUserConfigMap(userConfigMapRef.orElse(null));
                return v;
            }).prepare(spec, userConfigMapRef.orElse(null));
        }
        return null;
    }

    public CheckPoint commitCheckPoint(Key key) {
        return containsKey(key) ? get(key).rollback() : null;
    }

    public CheckPoint rollbackCheckPoint(Key key) {
        return containsKey(key) ? get(key).commit() : null;
    }

    public CheckPointCache.CheckPoint clearCheckPoint(Key key) {
        return remove(key);
    }

    public Optional<CheckPointCache.CheckPoint> getCheckPoint(Key key) {
        return Optional.ofNullable(get(key));
    }

}
