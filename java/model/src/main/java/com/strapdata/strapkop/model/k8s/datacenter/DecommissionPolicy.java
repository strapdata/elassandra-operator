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

package com.strapdata.strapkop.model.k8s.datacenter;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * PVC decommissioning policy
 */
@AllArgsConstructor
public enum DecommissionPolicy {
    KEEP_PVC("keep-pvc"),
    DELETE_PVC("delete-pvc"),
    SNAPSHOT_AND_DELETE_PVC("snapshot-and-delete-pvc");

    @Getter
    private String value;

    public static DecommissionPolicy fromString(String s)
    {
        if (s != null)
            for (DecommissionPolicy e : DecommissionPolicy.values())
                if (e.toString().equals(s))
                    return e;
        return null;
    }
}