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

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.sling.graphql.schema.aggregator.impl.Partial.SectionName;
import org.junit.Test;

public class PartialReaderTest {
    public static final String CHARSET = "UTF-8";
    private static final String NONAME = "<NO NAME>";

    private void assertSection(Partial p, String name, String description, String contentRegexp) throws IOException {
        final Optional<Partial.Section> os = p.getSection(SectionName.valueOf(name));
        assertTrue("Expecting section " + name, os.isPresent());
        final Partial.Section s = os.get();
        if(description != null) {
            assertEquals("For section " + name, description, s.getDescription());
        }
        if(contentRegexp != null) {
            try(Reader r = s.getContent()) {
                final String actual = IOUtils.toString(r).trim();
                final Pattern regexp = Pattern.compile(contentRegexp, Pattern.DOTALL);
                assertTrue(
                    String.format("Expecting section %s to match %s but was [%s]", name, contentRegexp, actual),
                    regexp.matcher(actual).matches()
                );
            }
        }
    }

    private Supplier<Reader> getResourceReaderSupplier(String resourceName) {
        return () -> {
            try {
                final InputStream input = getClass().getResourceAsStream(resourceName);
                assertNotNull("Expecting resource " + resourceName, input);
                return new InputStreamReader(input, CHARSET);
            } catch(UnsupportedEncodingException uee) {
                throw new RuntimeException("Unsupported encoding " + CHARSET, uee);
            }
        };
    }

    private Supplier<Reader> getStringReaderSupplier(String content) {
        return () -> new StringReader(content);
    }

    @Test
    public void parseExample() throws Exception {
        final PartialReader p = new PartialReader(
                PartialInfo.fromPath(Paths.get("/partials/example.partial.txt")),
                getResourceReaderSupplier("/partials/example.partial.txt")
        );
        assertSection(p, "PARTIAL", "Example GraphQL schema partial", "The contents.*PARTIAL.*PARTIAL.*PARTIAL.*equired section\\.");
        assertSection(p, "REQUIRES", "base.scalars, base.schema", null);
        assertSection(p, "PROLOGUE", "", "The prologue content.*the aggregated schema.*other sections\\.");
        assertSection(p, "QUERY", "", "The optional query sections of all partials are aggregated in a query \\{\\} section in the output\\.");
        assertSection(p, "MUTATION", "", "The optional mutation sections of all partials are aggregated in a mutation \\{\\} section in the output\\.");
        assertSection(p, "TYPES", "", "The types sections.*mutation(\\s)+sections\\.");
    }

    @Test
    public void accentedCharacters() throws Exception {
        final PartialReader p = new PartialReader(
                PartialInfo.fromPath(Paths.get("/partials/utf8.partial.txt")),
                getResourceReaderSupplier("/partials/utf8.partial.txt")
        );
        assertSection(p, "PARTIAL", 
            "Example GraphQL schema partial with caract\u00E8res accentu\u00E9s",
            "L'\u00E9t\u00E9 nous \u00E9vitons l'\u00E2tre et pr\u00E9f\u00E9rons Chateaun\u00F6f et les \u00E4kr\u00E0s."
        );
    }

    @Test
    public void missingPartialSection() throws Exception {
        final Exception e = assertThrows(
            PartialReader.SyntaxException.class, 
            () -> new PartialReader(PartialInfo.EMPTY, getStringReaderSupplier(""))
        );
        final String expected = "Missing required PARTIAL section";
        assertTrue(String.format("Expected %s in %s", expected, e.getMessage()), e.getMessage().contains(expected));
    }

    @Test
    public void invalidSectionName() throws Exception {
        final String invalidName = "REQUIRE";
        final Exception e = assertThrows(
            PartialReader.SyntaxException.class, 
            () -> new PartialReader(
                    PartialInfo.EMPTY, getStringReaderSupplier(String.format("PARTIAL:test\n%s:something\n", invalidName)))
        );
        final String expected = "Invalid section name 'REQUIRE'";
        assertTrue(String.format("Expected %s in %s", expected, e.getMessage()), e.getMessage().contains(expected));
    }

    @Test
    public void duplicateSection() throws Exception {
        final Exception e = assertThrows(
            PartialReader.SyntaxException.class, 
            () -> new PartialReader(
                    PartialInfo.fromPath(Paths.get("/partials/duplicate.section.partial.txt")),
                    getResourceReaderSupplier("/partials/duplicate.section.partial.txt")
            )
        );
        final String expected = "Duplicate section 'QUERY'";
        assertTrue(String.format("Expected %s in %s", expected, e.getMessage()), e.getMessage().contains(expected));
    }

    @Test
    public void requires() throws Exception {
        final PartialReader p = new PartialReader(
                PartialInfo.fromPath(Paths.get("/partials/c.sdl.txt")),
                getResourceReaderSupplier("/partials/c.sdl.txt")
        );
        assertTrue("Expecting requires section", p.getSection(Partial.SectionName.REQUIRES).isPresent());
        assertEquals("[a.sdl, b.sdl]", p.getRequiredPartialNames().toString());
    }

    @Test
    public void testDigest() throws IOException {
        final PartialReader p = new PartialReader(
                PartialInfo.fromPath(Paths.get("/partials/versioned-1.0.0.txt")),
                getResourceReaderSupplier("/partials/versioned-1.0.0.txt")
        );
        assertEquals("SHA-256: 703bd06e9d65118c75abe9a7a06f6a2fcdb8a19ef62d994f4cc1be0b34420383", p.getDigest());
    }
}
