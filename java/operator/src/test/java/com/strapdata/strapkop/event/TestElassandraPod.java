package com.strapdata.strapkop.event;


import com.strapdata.model.k8s.cassandra.DataCenter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.*;

public class TestElassandraPod {

    @Test
    public void testGkePodName() {
        String podName = "elassandra-clustername-datacenter-europe-west1-b-0";

        Matcher matcher = ElassandraPod.podNamePattern.matcher(podName);
        assertTrue(matcher.matches());
        assertEquals("clustername", matcher.group(1));
        assertEquals("datacenter",  matcher.group(2));
        assertEquals("europe-west1-b",  matcher.group(3));
        assertEquals("0",  matcher.group(4));
    }

    @Test
    public void testLocalPodName() {
        String podName = "elassandra-cl1-dc1-local-0";

        Matcher matcher = ElassandraPod.podNamePattern.matcher(podName);
        assertTrue(matcher.matches());
        assertEquals("cl1", matcher.group(1));
        assertEquals("dc1",  matcher.group(2));
        assertEquals("local",  matcher.group(3));
        assertEquals("0",  matcher.group(4));
    }
}
