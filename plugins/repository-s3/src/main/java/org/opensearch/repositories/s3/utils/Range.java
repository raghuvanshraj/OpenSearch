/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.repositories.s3.utils;

import org.opensearch.common.collect.Tuple;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Range {

    // TODO review if this pattern is right
    // TODO if this should go in server code
    private static final Pattern RANGE_PATTERN = Pattern.compile("^bytes=([0-9]+)-([0-9]+)$");

    private final long start;
    private final long end;

    public Range(long start, long end) {
        this.start = start;
        this.end = end;
    }

    public static Tuple<Long, Long> fromHttpRangeHeader(String headerValue) {
//        Pattern httpRangePattern = Pattern.compile("(^[a-zA-Z][\\w]*)\\s+(\\d+)\\s?-\\s?(\\d+)?\\s?\\/?\\s?(\\d+|\\*)?");
        Matcher matcher = RANGE_PATTERN.matcher(headerValue);
        if (!matcher.find()) {
            throw new RuntimeException("Regex match for Content-Range header {" + headerValue + "} failed");
        }
        return new Tuple<>(Long.parseLong(matcher.group(1)), Long.parseLong(matcher.group(2)));
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

    public static String toHttpRangeHeader(long start, long end) {
        return "bytes=" + start + "-" + end;
    }
}
