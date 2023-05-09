/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.repositories.s3.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Range {

    private final long start;
    private final long end;

    public Range(long start, long end) {
        this.start = start;
        this.end = end;
    }

    public static Range fromHttpRangeHeader(String headerValue) {
        // TODO need to extract range start and end and return new Range object
        Pattern httpRangePattern = Pattern.compile("(^[a-zA-Z][\\w]*)\\s+(\\d+)\\s?-\\s?(\\d+)?\\s?\\/?\\s?(\\d+|\\*)?");
        Matcher matcher = httpRangePattern.matcher(headerValue);
        if (!matcher.find()) {
            throw new RuntimeException("Regex match for Content-Range header {" + headerValue + "} failed");
        }
        return new Range(Long.parseLong(matcher.group(2)), Long.parseLong(matcher.group(2)));
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    public String getHttpRangeHeader() {
        return "bytes " + start + "-" + end;
    }
}
