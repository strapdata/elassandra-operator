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

package com.strapdata.strapkop.event;


import com.strapdata.strapkop.k8s.ElassandraPod;
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
