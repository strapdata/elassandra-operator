/*
 * Copyright (C) 2020 Strapdata SAS (support@strapdata.com)
 *
 * The Elassandra-Operator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The Elassandra-Operator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Elassandra-Operator.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.strapdata.strapkop.cache;

import com.google.common.collect.ImmutableList;
import com.strapdata.strapkop.k8s.ElassandraPod;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenter;
import io.micrometer.core.instrument.ImmutableTag;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.management.remote.JMXConnector;
import java.io.IOException;
import java.util.Objects;

/**
 * This cache associate a sidecar client to an elassandra pod.
 */
@Singleton
public class JMXConnectorCache extends Cache<ElassandraPod, JMXConnector> {

    private static final Logger logger = LoggerFactory.getLogger(JMXConnectorCache.class);

    JMXConnectorCache(MeterRegistry meterRegistry) {
        meterRegistry.gaugeMapSize("cache.size", ImmutableList.of(new ImmutableTag("type", "jmx_connector")), this);
    }

    /**
     * Remove all clients that match a given datacenter. Client are closed before removal
     */
    public void purgeDataCenter(final DataCenter dc) {
        this.entrySet().removeIf(e -> {
                    if (Objects.equals(e.getKey().getParent(), dc.getMetadata().getName()) &&
                            Objects.equals(e.getKey().getNamespace(), dc.getMetadata().getNamespace())) {
                        try {
                            e.getValue().close();
                        }
                        catch (IOException exc) {
                            logger.warn("runtime error while closing sidecar client for pod={}", e.getKey().getName(), exc);
                        }
                        return true;
                    } else {
                        return false;
                    }
                }
        );
    }
}
