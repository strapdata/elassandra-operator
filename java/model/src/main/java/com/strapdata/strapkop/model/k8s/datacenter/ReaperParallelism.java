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