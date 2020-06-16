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

/**
 * Reaper parallelism, see http://cassandra-reaper.io/docs/usage/single/
 */
public enum ReaperParallelism {
    /**
     * One node at a time
     */
    SEQUENTIAL("sequential"),

    /**
     * All nodes at the same time
     */
    PARALLEL("parallel"),

    /**
     * One node per data center at a time
     */
    DATACENTER_AWARE("dc_parallel");

    private final String name;

    /**
     * Return RepairParallelism that match given name.
     * If name is null, or does not match any, this returns default "sequential" parallelism,
     *
     * @param name name of repair parallelism
     * @return RepairParallelism that match given name
     */
    public static ReaperParallelism fromName(String name)
    {
        if (PARALLEL.getName().equals(name))
            return PARALLEL;
        else if (DATACENTER_AWARE.getName().equals(name))
            return DATACENTER_AWARE;
        else
            return SEQUENTIAL;
    }

    private ReaperParallelism(String name)
    {
        this.name = name;
    }

    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return getName();
    }
}