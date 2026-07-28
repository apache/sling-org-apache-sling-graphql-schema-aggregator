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
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NotNull;

/** Reader for the partials format, which parses a partial file and
 *  provides access to its sections.
 *  See the example.partial.txt and the tests for a description of
 *  the format.
 */
public class PartialReader implements Partial {
    private static final Pattern SECTION_LINE = Pattern.compile("([A-Z]+) *:(.*)");
    private static final int EOL = '\n';

    private final Map<SectionName, Section> sections = new EnumMap<>(SectionName.class);
    private final PartialInfo partialInfo;
    private final Set<PartialInfo> requiredPartialNames;
    private final String digest;

    /** The PARTIAL section is the only required one */
    public static final String PARTIAL_SECTION = "PARTIAL";

    static class SyntaxException extends IOException {
        SyntaxException(String reason) {
            super(reason);
        }
    }

    static class ParsedSection implements Partial.Section {
        private final Supplier<Reader> sectionSource;
        private final SectionName name;
        private final String description;
        private final int startCharIndex;
        private final int endCharIndex;

        ParsedSection(Supplier<Reader> sectionSource, SectionName name, String description, int start, int end) {
            this.sectionSource = sectionSource;
            this.name = name;
            this.description = description;
            this.startCharIndex = start;
            this.endCharIndex = end;
        }

        @Override
        public SectionName getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public Reader getContent() throws IOException {
            final Reader r = sectionSource.get();
            skipFully(r, startCharIndex);
            return new BoundedContentReader(r, endCharIndex - startCharIndex);
        }

        /**
         * Skips up to {@code count} characters from {@code r}.
         *
         * This uses Reader.skip() repeatedly. If skip() makes no progress the method falls back
         * to reading into a temporary buffer (up to 8 KiB) to advance in bulk instead of
         * degrading to single-character reads. That avoids very slow behavior when skip()
         * consistently returns 0.
         *
         * If EOF is reached before the requested number of characters is skipped the method
         * returns normally after consuming available input; it does not throw. Callers that
         * require a strict guarantee that the requested start exists should validate the source
         * or check the reader state after this call.
         */
        private static void skipFully(Reader r, int count) throws IOException {
            int remaining = count;
            // start with a buffer sized to the remaining amount but never larger than 8 KiB
            char[] buf = new char[Math.max(1, Math.min(8192, remaining))];
            while (remaining > 0) {
                final long skipped = r.skip(remaining);
                if (skipped > 0) {
                    remaining -= (int) skipped;
                    // shrink buffer if the remaining amount is smaller than current buffer
                    if (remaining > 0 && buf.length > remaining) {
                        buf = new char[Math.min(8192, remaining)];
                    }
                } else {
                    final int toRead = Math.min(buf.length, remaining);
                    final int n = r.read(buf, 0, toRead);
                    if (n == -1) {
                        // EOF reached before skipping everything - stop
                        break;
                    }
                    remaining -= n;
                }
            }
        }
    }

    /** Bounds reads to at most {@code maxChars} characters.
     *  commons-io's BoundedReader stopped enforcing this bound on its read(char[]) overload
     *  in 2.22.0 (only read() and read(char[],int,int) got the fix) - IOUtils.copy() reads
     *  through exactly that overload, so a section's content would run straight into the
     *  next one. Extending Reader directly, instead of commons-io's ProxyReader, means the
     *  JDK's own default read()/read(char[]) delegate to read(char[],int,int) below, so
     *  every overload stays bounded no matter which commons-io version is on the classpath.
     *
     *  Note: when the underlying reader reaches EOF, reads behave normally and return -1.
     *  This class does not attempt to recover or throw when the section's start offset was
     *  beyond EOF; callers that need that guarantee should validate the source beforehand.
     *  For correctness and performance, this class explicitly implements read(char[],int,int)
     *  so JDK and commons-io bulk read paths stay bounded; read() and read(char[]) will
     *  delegate to that implementation.
     */
    private static final class BoundedContentReader extends Reader {
        private final Reader target;
        private int remaining;

        BoundedContentReader(Reader target, int maxChars) {
            this.target = target;
            this.remaining = maxChars;
        }

        @Override
        public int read(char[] cbuf, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            final int toRead = Math.min(len, remaining);
            final int n = target.read(cbuf, off, toRead);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }

    public PartialReader(@NotNull PartialInfo partialInfo, @NotNull Supplier<Reader> source) throws IOException {
        this.partialInfo = partialInfo;
        // Normalize line endings to LF regardless of how the file was checked out (e.g. CRLF on
        // Windows), so parsing, section content and the digest are all consistent across platforms.
        final Supplier<Reader> normalizedSource = normalizeLineEndings(source);
        parse(normalizedSource);
        this.digest = "SHA-256: "
                + Hex.encodeHexString(DigestUtils.updateDigest(
                                DigestUtils.getSha256Digest(),
                                IOUtils.toByteArray(normalizedSource.get(), StandardCharsets.UTF_8))
                        .digest());
        final Partial.Section requirements = sections.get(SectionName.REQUIRES);
        if (requirements == null) {
            requiredPartialNames = Collections.emptySet();
        } else {
            requiredPartialNames = PartialInfo.fromRequiresSection(requirements.getDescription());
        }
    }

    private static Supplier<Reader> normalizeLineEndings(Supplier<Reader> source) {
        return () -> {
            try (Reader r = source.get()) {
                final String raw = IOUtils.toString(r);
                return new StringReader(raw.replace("\r\n", "\n").replace('\r', '\n'));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /* Detect lines that start with a <SECTION>: name
     *  in the input, and save them as sections
     */
    private void parse(Supplier<Reader> source) throws IOException {
        final Reader input = source.get();
        StringBuilder line = new StringBuilder();
        int c;
        int charCount = 0;
        int lastSectionStart = 0;
        String sectionName = null;
        String sectionDescription = "";
        while ((c = input.read()) != -1) {
            if (c == EOL) {
                final Matcher m = SECTION_LINE.matcher(line);
                if (m.matches()) {
                    // Add previous section
                    addSectionIfNameIsSet(
                            source,
                            toSectionName(sectionName),
                            sectionDescription,
                            lastSectionStart,
                            charCount - line.length());
                    // And setup for the new section
                    sectionName = m.group(1).trim();
                    sectionDescription = m.group(2).trim();
                    lastSectionStart = charCount + 1;
                }
                line = new StringBuilder();
            } else {
                line.append((char) c);
            }
            charCount++;
        }

        // Add last section
        addSectionIfNameIsSet(
                source, toSectionName(sectionName), sectionDescription, lastSectionStart, Integer.MAX_VALUE);

        // And validate
        if (!sections.containsKey(SectionName.PARTIAL)) {
            throw new SyntaxException(String.format("Missing required %s section", PARTIAL_SECTION));
        }
    }

    private void addSectionIfNameIsSet(
            Supplier<Reader> sectionSource, SectionName name, String description, int start, int end)
            throws SyntaxException {
        if (name == null) {
            return;
        }
        if (sections.containsKey(name)) {
            throw new SyntaxException(String.format("Duplicate section '%s'", name));
        }
        sections.put(name, new ParsedSection(sectionSource, name, description, start, end));
    }

    private SectionName toSectionName(String str) throws SyntaxException {
        if (str == null) {
            return null;
        }
        try {
            return SectionName.valueOf(str);
        } catch (Exception e) {
            throw new SyntaxException(String.format("Invalid section name '%s'", str));
        }
    }

    @Override
    public @NotNull PartialInfo getPartialInfo() {
        return partialInfo;
    }

    @Override
    public @NotNull Optional<Section> getSection(Partial.SectionName name) {
        final Section s = sections.get(name);
        return Optional.ofNullable(s);
    }

    @Override
    public @NotNull Set<PartialInfo> getRequiredPartialNames() {
        return Collections.unmodifiableSet(requiredPartialNames);
    }

    @Override
    public @NotNull String getDigest() {
        return digest;
    }
}
