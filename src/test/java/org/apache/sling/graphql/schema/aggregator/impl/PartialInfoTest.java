/*~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 ~ Licensed to the Apache Software Foundation (ASF) under one
 ~ or more contributor license agreements.  See the NOTICE file
 ~ distributed with this work for additional information
 ~ regarding copyright ownership.  The ASF licenses this file
 ~ to you under the Apache License, Version 2.0 (the
 ~ "License"); you may not use this file except in compliance
 ~ with the License.  You may obtain a copy of the License at
 ~
 ~   http://www.apache.org/licenses/LICENSE-2.0
 ~
 ~ Unless required by applicable law or agreed to in writing,
 ~ software distributed under the License is distributed on an
 ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 ~ KIND, either express or implied.  See the License for the
 ~ specific language governing permissions and limitations
 ~ under the License.
 ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~*/
package org.apache.sling.graphql.schema.aggregator.impl;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Set;

import org.junit.Test;
import org.osgi.framework.Version;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PartialInfoTest {

    @Test
    public void testFileNameParsing() {
        PartialInfo p1 = PartialInfo.fromFileName("partial.txt");
        assertEquals("partial", p1.getName());
        assertEquals(Version.emptyVersion, p1.getVersion());

        PartialInfo p2 = PartialInfo.fromFileName("partial-2.0.0.txt");
        assertEquals("partial", p2.getName());
        assertEquals(Version.parseVersion("2.0.0"), p2.getVersion());

        assertEquals(PartialInfo.EMPTY, PartialInfo.fromFileName("partial-a.b.c.txt"));
        assertEquals(PartialInfo.EMPTY, PartialInfo.fromFileName("1.2.3"));
    }

    @Test
    public void testPathParsing() {
        PartialInfo p1 = PartialInfo.fromPath(Paths.get("a", "path", "section", "partial.txt"));
        assertEquals("partial", p1.getName());
        assertEquals(Version.emptyVersion, p1.getVersion());

        PartialInfo p2 = PartialInfo.fromPath(Paths.get("partial-2.0.0.txt"));
        assertEquals("partial", p2.getName());
        assertEquals(Version.parseVersion("2.0.0"), p2.getVersion());

        assertEquals(PartialInfo.EMPTY, PartialInfo.fromPath(Paths.get("partial-a.b.c.txt")));
        assertEquals(PartialInfo.EMPTY, PartialInfo.fromPath(Paths.get("1.2.3")));
    }

    @Test
    public void testURLParsing() throws MalformedURLException {
        PartialInfo p1 = PartialInfo.fromURL(new URL( "file:///root/partial.txt"));
        assertEquals("partial", p1.getName());
        assertEquals(Version.emptyVersion, p1.getVersion());

        PartialInfo p2 = PartialInfo.fromURL(new URL("file:///partial-2.0.0.txt"));
        assertEquals("partial", p2.getName());
        assertEquals(Version.parseVersion("2.0.0"), p2.getVersion());

        assertEquals(PartialInfo.EMPTY, PartialInfo.fromURL(new URL("file:///bid/folder/partial-a.b.c.txt")));
        assertEquals(PartialInfo.EMPTY, PartialInfo.fromURL(new URL("file:///bid/folder/1.2.3")));
    }

    @Test
    public void testFromRequireHeader() {
        Set<PartialInfo> parsed = PartialInfo.fromRequiresSection("partial, a_partial-1.0.0, 0");
        assertEquals(2, parsed.size());
        for (PartialInfo partialInfo : parsed) {
            assertTrue(
                    "partial".equals(partialInfo.getName()) ||
                            ("a_partial".equals(partialInfo.getName()) && Version.parseVersion("1.0.0").equals(partialInfo.getVersion()))
            );
        }
    }

}
