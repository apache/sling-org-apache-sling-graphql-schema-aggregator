/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.graphql.schema.aggregator;

import org.osgi.framework.Bundle;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.ops4j.pax.tinybundles.core.TinyBundles.bundle;

import org.apache.sling.graphql.schema.aggregator.impl.ProviderBundleTracker;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.namespace.extender.ExtenderNamespace;

import static org.ops4j.pax.exam.CoreOptions.streamBundle;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;

/** Test Utilities */
public class U {
    
    public static Bundle mockProviderBundle(BundleContext bc, String symbolicName, long id, String ... schemaNames) throws IOException {
        final UUID uuid = UUID.randomUUID();
        final Bundle b = mock(Bundle.class);
        final BundleWiring wiring = mock(BundleWiring.class);
        when(b.getSymbolicName()).thenReturn(symbolicName);
        when(b.getBundleId()).thenReturn(id);
        when(b.adapt(BundleWiring.class)).thenReturn(wiring);
        final BundleWire wire = mock(BundleWire.class);
        when(wiring.getRequiredWires(ExtenderNamespace.EXTENDER_NAMESPACE)).thenReturn(Collections.singletonList(wire));
        final BundleRevision revision = mock(BundleRevision.class);
        when(wire.getProvider()).thenReturn(revision);
        when(revision.getBundle()).thenAnswer(invocationOnMock -> bc.getBundle());

        final Dictionary<String, String> headers = new Hashtable<>();
        String fakePath = symbolicName + "/path/" + id;
        headers.put(ProviderBundleTracker.SCHEMA_PATH_HEADER, fakePath);
        when(b.getHeaders()).thenReturn(headers);

        final List<String> resources = new ArrayList<>();
        for(String name : schemaNames) {
            URL partial = testFileURL(name);
            if(partial == null) {
                File tempFolder = Files.createTempDirectory(uuid.toString()).toFile();
                partial = fakePartialURL(tempFolder, name);
            }
            String fakeResource = fakePath + "/resource/" + name;
            resources.add(fakeResource);
            when(b.getEntry(fakeResource)).thenReturn(partial);
        }
        when(b.getEntryPaths(fakePath)).thenReturn(Collections.enumeration(resources));
        return b;
    }

    /** Simple way to get a URL: create a temp file */
    public static URL fakePartialURL(File folder, String name) throws IOException {
        final File f = new File(folder, name);
        f.deleteOnExit();
        final PrintWriter w = new PrintWriter(new FileWriter(f));
        w.print(fakePartialSchema(name));
        w.flush();
        w.close();
        // Safe in our case, we're using acceptable characters in the path
        return f.toURL();
    }

    public static URL testFileURL(String name) {
        return U.class.getResource(String.format("/partials/%s", name));
    }

    public static String fakePartialSchema(String name) {
        return String.format("PARTIAL:%s\nQUERY:%s\nFake query for %s\n", name, name, name);
    }

    public static Option tinyProviderBundle(String symbolicName, String ... partialsNames) {
        final String schemaPath = symbolicName + "/schemas";
        final TinyBundle b = bundle()
            .set(ProviderBundleTracker.SCHEMA_PATH_HEADER, schemaPath)
            .set(Constants.BUNDLE_SYMBOLICNAME, symbolicName)
            .set(
                    Constants.REQUIRE_CAPABILITY,
                    "osgi.extender;filter:=\"(&(osgi.extender=sling.graphql-schema-aggregator)(version>=0.1)(!(version>=1.0)))\""
            )
        ;

        for(String name : partialsNames) {
            final String resourcePath = schemaPath + "/" + name + ".txt";
            b.add(resourcePath, new ByteArrayInputStream(fakePartialSchema(name).getBytes()));
        }

        return streamBundle(b.build());
    }

    public static void assertPartialsFoundInSchema(String output, String ... partialName) {
        for(String name : partialName) {
            final String expected = "DefaultSchemaAggregator.source=" + name;
            if(!output.contains(expected)) {
                fail(String.format("Expecting output to contain %s: %s", expected, output));
            }
        }
    }
}
