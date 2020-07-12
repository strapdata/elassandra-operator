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

package com.strapdata.strapkop;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Getter;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * This class holds a type-safe representation of the configuration gathered from props file (application.yaml)
 */
@ConfigurationProperties("operator")
@Getter
public class OperatorConfig {

    /**
     * Operator watching namespace, watch on all namespaces if null
     */
    @Nullable
    String watchNamespace;

    /**
     * Operator JMXMP port
     */
    Integer jmxmpPort;

    /**
     * Operator k8s service name used for callbacks
     */
    String serviceName;

    /**
     * Namespace where the operator is deployed
     */
    String operatorNamespace;

    @Nullable
    int k8sWatchPeriodInSec = 300;

    /**
     * Terminated task retention
     */
    Duration taskRetention = Duration.ofDays(8);

    /**
     * operation history depth.
     */
    int operationHistoryDepth = 16;

    /**
     * CQL schema agreement wait in seconds
     */
    int maxSchemaAgreementWaitSeconds = 30;

    /**
     * Run the sysctl init container if true.
     */
    Boolean runSysctl;
}
