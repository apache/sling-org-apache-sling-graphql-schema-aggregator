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

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.sling.graphql.schema.aggregator.api.SchemaAggregator;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = SchemaAggregator.class)
public class DefaultSchemaAggregator implements SchemaAggregator {
    private static final Logger log = LoggerFactory.getLogger(DefaultSchemaAggregator.class.getName());
    public static final int MAX_REQUIREMENTS_RECURSION_LEVEL = 5;

    /** Some sections like Query {} are surround by blocks in
     *  the output.
     */
    enum OutputMode {
        NO_BLOCK,
        WITH_BLOCK_IF_NOT_EMPTY,
        WITH_BLOCK
    }

    @Reference
    private ProviderBundleTracker tracker;

    static String capitalize(Partial.SectionName name) {
        if(name == null) {
            return null;
        }

        final String s = name.toString();
        if(s.length() >  1) {
            return String.format("%s%s", s.substring(0, 1).toUpperCase(), s.substring(1).toLowerCase());
        } else {
            return s.toUpperCase();
        }
    }

    private void copySection(Set<Partial> selected, Partial.SectionName sectionName, OutputMode mode, Writer target) throws IOException {
        String prefixToWrite = (mode == OutputMode.NO_BLOCK) ? null : String.format("%ntype %s {%n", capitalize(sectionName));
        boolean anyOutput = false;
        for(Partial p : selected) {
            final Optional<Partial.Section> section = p.getSection(sectionName);
            if(section.isPresent()) {
                anyOutput = true;
                if(prefixToWrite != null) {
                    target.write(prefixToWrite);
                    prefixToWrite = null;
                }
                writeSourceInfo(target, p);
                IOUtils.copy(section.get().getContent(), target);
            } else if(mode == OutputMode.WITH_BLOCK && prefixToWrite != null) {
                target.write(prefixToWrite);
                prefixToWrite = null;
            }
        }
        if( (anyOutput && mode == OutputMode.WITH_BLOCK_IF_NOT_EMPTY) || mode == OutputMode.WITH_BLOCK) {
            target.write(String.format("%n}%n"));
        }
    }

    private void writeSourceInfo(Writer target, Partial p) throws IOException {
        target.write(String.format("%n# %s.source=%s%n", getClass().getSimpleName(), p.getPartialInfo()));
    }

    @Override
    public void aggregate(Writer target, String ...providerNamesOrRegexp) throws IOException {
        final String info = String.format("Schema aggregated by %s%n", getClass().getSimpleName());
        target.write(String.format("# %s", info));

        // build list of selected providers
        final Map<PartialInfo, Partial> providers = tracker.getSchemaProviders();
        if(log.isDebugEnabled()) {
            log.debug("Aggregating schemas, request={}, providers={}", Arrays.asList(providerNamesOrRegexp), providers.keySet());
        }
        final Set<String> missing = new HashSet<>();
        final Set<Partial> selected = selectProviders(providers, missing, providerNamesOrRegexp);

        if(!missing.isEmpty()) {
            log.debug("Requested providers {} not found in {}", missing, providers.keySet());
            throw new IOException(String.format("Missing providers: %s", missing));
        }

        // copy sections that belong in the output SDL
        copySection(selected, Partial.SectionName.PROLOGUE, OutputMode.NO_BLOCK, target);
        copySection(selected, Partial.SectionName.QUERY, OutputMode.WITH_BLOCK, target);
        copySection(selected, Partial.SectionName.MUTATION, OutputMode.WITH_BLOCK_IF_NOT_EMPTY, target);
        copySection(selected, Partial.SectionName.TYPES, OutputMode.NO_BLOCK, target);

        final StringBuilder partialNames = new StringBuilder();
        selected.forEach(p -> {
            if(partialNames.length() > 0) {
                partialNames.append(",");
            }
            partialNames.append(p.getPartialInfo());
        });
        target.write(String.format("%n# End of Schema aggregated from {%s} by %s", partialNames, getClass().getSimpleName()));
    }

    Set<Partial> selectProviders(Map<PartialInfo, Partial> providers, Set<String> missing, String ... providerNamesOrRegexp) {
        final Set<Partial> result= new LinkedHashSet<>();
        for(String str : providerNamesOrRegexp) {
            final Pattern p = toRegexp(str);
            if(p != null) {
                log.debug("Selecting providers matching {}", p);
                providers.entrySet().stream()
                    .filter(e -> p.matcher(e.getKey().getName()).matches())
                    .sorted(Comparator.comparing(e -> e.getValue().getPartialInfo()))
                    .forEach(e -> addWithRequirements(providers, result, missing, e.getValue(), 0))
                ;
            } else {
                log.debug("Selecting provider with key={}", str);
                Optional<PartialInfo> fromString = PartialInfo.fromRequiresSection(str).stream().findFirst();
                if (fromString.isPresent()) {
                    PartialInfo selected = fromString.get();
                    final Partial psp = providers.get(selected);
                    if (psp == null) {
                        missing.add(str);
                        continue;
                    }
                    addWithRequirements(providers, result, missing, psp, 0);
                } else {
                    missing.add(str);
                }
            }
        }
        return result;
    }

    private void addWithRequirements(Map<PartialInfo, Partial> providers, Set<Partial> addTo, Set<String> missing, Partial p, int recursionLevel) {

        // simplistic cycle detection
        if(recursionLevel > MAX_REQUIREMENTS_RECURSION_LEVEL) {
            throw new RuntimeException(String.format(
                "Requirements depth over %d, requirements cycle suspected at partial %s", 
                MAX_REQUIREMENTS_RECURSION_LEVEL,
                p.getPartialInfo()
            ));
        }

        addTo.add(p);
        for(PartialInfo req : p.getRequiredPartialNames()) {
            final Partial preq = providers.get(req);
            if(preq == null) {
                missing.add(req.toString());
            } else {
                addWithRequirements(providers, addTo, missing, preq, recursionLevel + 1);
            }
        }
    }

    static Pattern toRegexp(String input) {
        if(input.startsWith("/") && input.endsWith("/")) {
            return Pattern.compile(input.substring(1, input.length() - 1));
        }
        return null;
    }
}
