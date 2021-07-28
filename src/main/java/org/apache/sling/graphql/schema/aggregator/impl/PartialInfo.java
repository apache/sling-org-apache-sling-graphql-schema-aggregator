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

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Version;

/**
 * This class provides some utility methods to extract information about a partial without parsing it.
 */
public final class PartialInfo {

    private static final String PARTIAL_NAME_AND_VERSION_REGEX = "([a-z][a-zA-Z0-9_\\.]*)(-(\\d\\.\\d\\.\\d))?";

    public static final String PARTIAL_FILE_EXTENSION = "txt";
    public static final Pattern PARTIAL_FILE_NAME_PATTERN =
            Pattern.compile(PARTIAL_NAME_AND_VERSION_REGEX + "\\." + PARTIAL_FILE_EXTENSION);
    public static final Pattern PARTIAL_REQUIRE_HEADER_PATTERN = Pattern.compile(PARTIAL_NAME_AND_VERSION_REGEX);
    public static final int PARTIAL_NAME_GROUP = 1;
    public static final int PARTIAL_VERSION_GROUP = 3;


    private final String name;
    private final Version version;

    public static final PartialInfo EMPTY = new PartialInfo("", Version.emptyVersion);

    PartialInfo(@NotNull String name, @NotNull Version version) {
        this.name = name;
        this.version = version;
    }

    /**
     * Returns the partial's name.
     *
     * @return the partial's name
     */
    public @NotNull String getName() {
        return name;
    }

    /**
     * Returns the partial's version.
     *
     * @return the partial's version
     */
    public @NotNull Version getVersion() {
        return version;
    }

    /**
     * Parses a {@code path} and returns a {@link PartialInfo}.
     *
     * @param path the path to parse
     * @return a {@link PartialInfo} with the parsed details; a {@link PartialInfo#EMPTY} means that the parsing was not able to identify a
     * valid {@link PartialInfo}
     */
    public static @NotNull PartialInfo fromPath(@NotNull Path path) {
        return fromFileName(path.getFileName().toString());
    }

    /**
     * Parses a {@code url} and returns a {@link PartialInfo}.
     *
     * @param url the url to parse
     * @return a {@link PartialInfo} with the parsed details; a {@link PartialInfo#EMPTY} means that the parsing was not able to identify a
     * valid {@link PartialInfo}
     */
    public static @NotNull PartialInfo fromURL(@NotNull URL url) {
        String file = url.getFile();
        if (!file.isEmpty()) {
            int lastSlash = file.lastIndexOf('/');
            if (lastSlash > -1 && lastSlash != file.length() - 1) {
                String fileName = file.substring(lastSlash + 1);
                return fromFileName(fileName);
            }
        }
        return PartialInfo.EMPTY;
    }

    /**
     * Parses a {@code fileName} and returns a {@link PartialInfo}.
     *
     * @param fileName the file name to parse
     * @return a {@link PartialInfo} with the parsed details; a {@link PartialInfo#EMPTY} means that the parsing was not able to identify a
     * valid {@link PartialInfo}
     */
    public static @NotNull PartialInfo fromFileName(@NotNull String fileName) {
        Matcher matcher = PARTIAL_FILE_NAME_PATTERN.matcher(fileName);
        if (matcher.matches()) {
            return new PartialInfo(matcher.group(PARTIAL_NAME_GROUP),
                    Version.parseVersion(matcher.group(PARTIAL_VERSION_GROUP)));
        }
        return PartialInfo.EMPTY;
    }

    /**
     * Parses the partial names provided in a {@code REQUIRES} section of a {@link Partial}.
     *
     * @param requires the value of the {@code REQUIRES} section
     * @return a set of {@link PartialInfo}
     */
    public static @NotNull Set<PartialInfo> fromRequiresSection(@NotNull String requires) {
        if (!requires.isEmpty()) {
            Set<PartialInfo> partialInfos = new HashSet<>();
            for (String partial : requires.split(",")) {
                Matcher matcher = PARTIAL_REQUIRE_HEADER_PATTERN.matcher(partial.trim());
                if (matcher.matches()) {
                    partialInfos.add(new PartialInfo(matcher.group(PARTIAL_NAME_GROUP),
                            Version.parseVersion(matcher.group(PARTIAL_VERSION_GROUP))));
                }
            }
            return Collections.unmodifiableSet(partialInfos);
        }
        return Collections.emptySet();
    }
}

