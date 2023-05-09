/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.repositories.s3.utils;

public class Range {

    private final long start;
    private final long end;

    public Range(long start, long end) {
        this.start = start;
        this.end = end;
    }

    public static Range fromHttpRangeHeader(String headerValue) {
        // TODO need to extract range start and end and return new Range object
        return new Range(0, 10);
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public String getHttpRangeHeader() {
        return "bytes=" + start + "-" + end;
    }
}
