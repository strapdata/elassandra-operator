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

package com.strapdata.strapkop.model.sidecar;

/**
 * This has been renamed from NodeStatus ElassandraNodeStatus to avoid confusion with k8s nodes
 */
public enum ElassandraNodeStatus {
    UNKNOWN,
    STARTING,
    NORMAL,
    JOINING,
    LEAVING,
    DECOMMISSIONED,
    MOVING,
    DRAINING,
    DRAINED,
    DOWN,   // temporary down due to a maintenance operation
    FAILED; // failed to start or restart

    public boolean isMoving() {
        return JOINING.equals(this) || DRAINING.equals(this) || LEAVING.equals(this) || MOVING.equals(this) || STARTING.equals(this);
    }

    // test if the Node belongs to the cluster
    public boolean isJoined() {
        return MOVING.equals(this) || NORMAL.equals(this);
    }
}