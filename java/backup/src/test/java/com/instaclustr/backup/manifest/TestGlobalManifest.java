package com.instaclustr.backup.manifest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TestGlobalManifest {

    private GlobalManifest gManifest = null;

    @BeforeTest
    public void init() {
        gManifest = new GlobalManifest("elassandra-cl1-dc1", "TESTTAG");
        gManifest.addManifest("elassandra-cl1-dc1-local-0", "a");
        gManifest.addManifest("elassandra-cl1-dc1-local-1", "b");
        gManifest.addManifest("elassandra-cl1-dc1-local-2", "c");
    }

    @Test
    public void testGetNodes() {
        final Set<String> nodes = gManifest.getNodes();
        assertEquals(3, nodes.size());
        assertTrue(nodes.containsAll(Arrays.asList("elassandra-cl1-dc1-local-0", "elassandra-cl1-dc1-local-1", "elassandra-cl1-dc1-local-2")));
    }

    @Test
    public void testGetManifestPath() {
        final List<String> objectKeys = gManifest.getManifestPaths();
        assertEquals(3, objectKeys.size());
        assertTrue(objectKeys.containsAll(Arrays.asList(gManifest.getDataCenter() + "/elassandra-cl1-dc1-local-0/a",
                gManifest.getDataCenter() + "/elassandra-cl1-dc1-local-1/b",
                gManifest.getDataCenter() + "/elassandra-cl1-dc1-local-2/c")));
    }


}