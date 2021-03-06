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
import java.net.URL;

import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A {@link Partial} built out of a Bundle entry using a {@link PartialReader} */
class BundleEntryPartial extends PartialReader implements Comparable<BundleEntryPartial> {
    private static final Logger log = LoggerFactory.getLogger(BundleEntryPartial.class.getName());
    private final String key;
    private final long bundleId;

    private BundleEntryPartial(Bundle b, URL bundleEntry) throws IOException {
        super(PartialInfo.fromURL(bundleEntry), new URLReaderSupplier(bundleEntry));
        this.bundleId = b.getBundleId();
        this.key = String.format("%s(%d):%s", b.getSymbolicName(), b.getBundleId(), bundleEntry);
    }

    /** @return a BundleEntryPartialProvider for the entryPath in
     *  the supplied Bundle, or null if none can be built.
      */
    static BundleEntryPartial forBundle(Bundle b, String entryPath) throws IOException {
        final URL entry = b.getEntry(entryPath);
        if(entry == null) {
            log.info("Entry {} not found for bundle {}", entryPath, b.getSymbolicName());
            return null;
        } else {
            return new BundleEntryPartial(b, entry);
        }
    }

    @Override
    public boolean equals(Object other) {
        if(other instanceof BundleEntryPartial) {
            return ((BundleEntryPartial)other).key.equals(key);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    public String toString() {
        return String.format("%s: %s", getClass().getSimpleName(), key);
    }

    public long getBundleId() {
        return bundleId;
    }

    @Override
    public int compareTo(BundleEntryPartial o) {
        return getPartialInfo().compareTo(o.getPartialInfo());
    }
}
