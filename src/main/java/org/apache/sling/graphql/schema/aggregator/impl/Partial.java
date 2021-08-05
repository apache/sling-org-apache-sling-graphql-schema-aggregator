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
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

/** Wrapper for the partials format, that parses a partial file and
 *  provides access to its sections.
 *  See the example.partial.txt and the tests for a description of
 *  the format.
  */
public interface Partial {
    /** A section in the partial */
    interface Section {
        SectionName getName();
        String getDescription();
        Reader getContent() throws IOException;
    }

    enum SectionName {
        PARTIAL,
        REQUIRES,
        PROLOGUE,
        QUERY,
        MUTATION,
        TYPES
    }

    /**
     * Returns the partial info.
     *
     * @return the partial info
     */
    @NotNull PartialInfo getPartialInfo();

    /** Return a specific section of the partial, by name */
    @NotNull Optional<Section> getSection(SectionName name);

    /** Names of the Partials on which this one depends */
    @NotNull Set<PartialInfo> getRequiredPartialNames();

    /**
     * <p>
     * Returns the digest of the source that was used to build this partial. Implementations should output this using the following format:
     * <pre>
     * algorithm: digest
     * </pre>
     * where the algorithm has to be one of the standard names defined in the
     * <a href="https://docs.oracle.com/en/java/javase/11/docs/specs/security/standard-names.html#messagedigest-algorithms">Java Security Standard Algorithm Names</a>.
     * </p>
     * <p>A SHA-256 digest would have, for example, the following format:
     * <pre>
     * SHA-256: 703bd06e9d65118c75abe9a7a06f6a2fcdb8a19ef62d994f4cc1be0b34420383
     * </pre>
     * </p>
     * @return the digest of the source that was used to build this partial
     */
    @NotNull String getDigest();

}
