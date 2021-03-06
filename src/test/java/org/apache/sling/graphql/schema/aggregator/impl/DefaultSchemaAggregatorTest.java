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
package org.apache.sling.graphql.schema.aggregator.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.sling.graphql.schema.aggregator.U;
import org.junit.Before;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import graphql.language.TypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class DefaultSchemaAggregatorTest {
    private DefaultSchemaAggregator dsa;
    private ProviderBundleTracker tracker;
    private BundleContext bundleContext;

    private void assertOutput(String expectedResourceName, String actual) throws IOException {
        try(InputStream is = getClass().getResourceAsStream(expectedResourceName)) {
            assertNotNull("Expecting classpath resource to be present:" + expectedResourceName, is);
            final String expected = IOUtils.toString(is, "UTF-8").trim();
            assertEquals(expected, actual);
        }
    }

    @Before
    public void setup() throws Exception {
        dsa = new DefaultSchemaAggregator();
        final Field f = dsa.getClass().getDeclaredField("tracker");
        f.setAccessible(true);
        bundleContext = mock(BundleContext.class);
        when(bundleContext.getBundle()).thenReturn(mock(Bundle.class));
        tracker = new ProviderBundleTracker();
        tracker.activate(bundleContext);
        f.set(dsa, tracker);
    }

    private void assertContainsIgnoreCase(String substring, String source) {
        assertTrue("Expecting '" + substring + "' in source string ", source.toLowerCase().contains(substring.toLowerCase()));
    }

    @Test
    public void noProviders() {
        final StringWriter target = new StringWriter();
        final IOException iox = assertThrows(IOException.class, () -> dsa.aggregate(target, "Aprov", "Bprov"));
        assertContainsIgnoreCase("missing providers", iox.getMessage());
        assertContainsIgnoreCase("Aprov", iox.getMessage());
        assertContainsIgnoreCase("Bprov", iox.getMessage());
        assertContainsIgnoreCase("schema aggregated by DefaultSchemaAggregator", target.toString());
    }

    @Test
    public void severalProviders() throws Exception{
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "A", 1, "a1.txt", "a2.z.w.txt", "a3abc.txt", "a4abc.txt"), null);
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "B", 2, "b1a.txt", "b2.xy.txt"), null);
        dsa.aggregate(target, "b1a", "b2.xy", "a2.z.w");
        final String sdl = target.toString().trim();
        assertContainsIgnoreCase("schema aggregated by DefaultSchemaAggregator", sdl);
        assertOutput("/partials/several-providers-output.txt", sdl);
    }

    @Test
    public void regexpSelection() throws Exception {
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "A", 1, "a.authoring.1.txt", "a.authoring.2.txt", "a.txt", "b.txt"), null);
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "B", 2, "b1.txt", "b.authoring.txt"), null);
        dsa.aggregate(target, "b1", "/.*\\.authoring.*/");
        assertContainsIgnoreCase("schema aggregated by DefaultSchemaAggregator", target.toString());
        U.assertPartialsFoundInSchema(target.toString(), "a.authoring.1", "a.authoring.2", "b.authoring", "b1");
    }

    @Test
    public void verifyResultSyntax() throws Exception {
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "SDL", 1, "a.sdl.txt", "b.sdl.txt", "c.sdl.txt"), null);

        dsa.aggregate(target, "/.*/");

        // Parse the output with a real SDL parser
        final String sdl = target.toString();
        final TypeDefinitionRegistry reg = new SchemaParser().parse(sdl);

        // And make sure it contains what we expect
        assertTrue(reg.getDirectiveDefinition("fetcher").isPresent());
        assertTrue(reg.getType("SlingResourceConnection").isPresent());
        assertTrue(reg.getType("PageInfo").isPresent());
        
        final Optional<TypeDefinition> query = reg.getType("Query");
        assertTrue("Expecting Query", query.isPresent());
        assertTrue(query.get().getChildren().toString().contains("oneSchemaResource"));
        assertTrue(query.get().getChildren().toString().contains("oneSchemaQuery"));

        final Optional<TypeDefinition> mutation = reg.getType("Mutation");
        assertTrue("Expecting Mutation", mutation.isPresent());
        assertTrue(mutation.get().getChildren().toString().contains("someMutation"));

        assertOutput("/partials/result-syntax-output.txt", sdl);
    }

    @Test
    public void verifyResultSyntaxMutationOnly() throws Exception {
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "SDL", 1, "a.sdl.txt", "mutation.only.txt"), null);

        dsa.aggregate(target, "mutation.only");

        // Parse the output with a real SDL parser
        final String sdl = target.toString();
        final TypeDefinitionRegistry reg = new SchemaParser().parse(sdl);

        // And make sure it contains what we expect
        assertTrue(reg.getDirectiveDefinition("fetcher").isPresent());
        
        final Optional<TypeDefinition> mutation = reg.getType("Mutation");
        assertTrue("Expecting Mutation", mutation.isPresent());
        assertTrue(mutation.get().getChildren().toString().contains("theOnlyMutation"));

        // A Query is required, even if not provided in any partial
        final Optional<TypeDefinition> query = reg.getType("Query");
        assertTrue("Expecting Query", query.isPresent());

        assertOutput("/partials/result-syntax-output-mutation-only.txt", sdl);
    }

    @Test
    public void requires() throws Exception {
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "SDL", 1, "a.sdl.txt", "b.sdl.txt", "c.sdl.txt"), null);
        dsa.aggregate(target, "c.sdl");
        final String sdl = target.toString();

        // Verify that required partials are included
        Stream.of(
            "someMutation",
            "typeFromB",
            "typeFromA"
        ).forEach((s -> {
            assertTrue("Expecting aggregate to contain " + s, sdl.contains(s));
        }));
   }

    @Test
    public void versionedPartials() throws IOException {
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "required.partials", 1, "required-1.0.0.txt"), null);
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "versioned.partials", 2, "versioned-1.0.0.txt"), null);
        dsa.aggregate(target, "versioned-1.0.0");
        assertOutput("/partials/versionedPartials-output.txt", target.toString());
    }

    @Test
    public void versionedPartialsMissingCorrectVersion() throws IOException {
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "required.partials", 1, "required-1.0.0.txt"), null);
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "versioned.partials", 2, "versioned-2.0.0.txt"), null);
        final IOException iox = assertThrows(IOException.class, () -> dsa.aggregate(target, "versioned-2.0.0"));
        assertContainsIgnoreCase("Missing providers", iox.getMessage());
        assertContainsIgnoreCase("required-2.0.0", iox.getMessage());
    }

    @Test
    public void cycleInRequirements() throws Exception {
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "SDL", 1, "circularA.txt", "circularB.txt"), null);
        final RuntimeException rex = assertThrows(RuntimeException.class, () -> dsa.aggregate(target, "circularA"));

        Stream.of(
            "requirements cycle",
            "circularA"
        ).forEach((s -> {
            assertTrue(String.format("Expecting message to contain %s: %s",  s, rex.getMessage()), rex.getMessage().contains(s));
        }));
    }

    @Test
    public void providersOrdering() throws Exception {
        final StringWriter target = new StringWriter();
        tracker.addingBundle(U.mockProviderBundle(bundleContext, "ordering", 1, "aprov.txt", "cprov.txt", "z_test.txt", "a_test.txt",
                "zprov.txt",
                "z_test.txt", "bprov.txt", "c_test.txt"), null);
        dsa.aggregate(target, "aprov", "zprov", "/[a-z]_test/", "a_test", "cprov");
        final String sdl = target.toString();

        // The order of named partials is kept, regexp selected ones are ordered by name
        // And A_test has already been used so it's not used again when called explicitly after regexp
        final String expected = "End of Schema aggregated from {aprov,zprov,a_test,c_test,z_test,cprov} by DefaultSchemaAggregator";
        assertTrue(String.format("Expecting schema to contain [%s]: %s", expected, sdl), sdl.contains(expected));
   }
}
