/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.repositories.s3;

import software.amazon.awssdk.core.metrics.CoreMetric;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class StatsMetricPublisher implements MetricPublisher {

    private static final String LIST_OBJECTS_V2_OPERATION = "ListObjectsV2";
    private static final String GET_OBJECT_OPERATION = "GetObject";
    private static final String PUT_OBJECT_OPERATION = "PutObject";
    private static final String CREATE_MULTIPART_UPLOAD_OPERATION = "CreateMultipartUpload";
    private static final String UPLOAD_PART_OPERATION = "UploadPart";
    private static final String COMPLETE_MULTIPART_UPLOAD_OPERATION = "CompleteMultipartUpload";

    private final Stats stats = new Stats();

    @Override
    public void publish(MetricCollection metricCollection) {
        // ListObjectsV2
        stats.listCount.addAndGet(metricCollection.metricValues(CoreMetric.OPERATION_NAME).stream().filter(operation -> operation.equals(LIST_OBJECTS_V2_OPERATION)).count());

        // GetObject
        stats.getCount.addAndGet(metricCollection.metricValues(CoreMetric.OPERATION_NAME).stream().filter(operation -> operation.equals(GET_OBJECT_OPERATION)).count());

        // PutObject
        stats.putCount.addAndGet(metricCollection.metricValues(CoreMetric.OPERATION_NAME).stream().filter(operation -> operation.equals(PUT_OBJECT_OPERATION)).count());

        // Multipart Uploads
        stats.postCount.addAndGet(metricCollection.metricValues(CoreMetric.OPERATION_NAME).stream().filter(operation -> operation.equals(CREATE_MULTIPART_UPLOAD_OPERATION) ||
            operation.equals(UPLOAD_PART_OPERATION) ||
            operation.equals(COMPLETE_MULTIPART_UPLOAD_OPERATION)).count());
    }

    @Override
    public void close() {}

    public Stats getStats() {
        return stats;
    }

    static class Stats {

        final AtomicLong listCount = new AtomicLong();

        final AtomicLong getCount = new AtomicLong();

        final AtomicLong putCount = new AtomicLong();

        final AtomicLong postCount = new AtomicLong();

        Map<String, Long> toMap() {
            final Map<String, Long> results = new HashMap<>();
            results.put("GetObject", getCount.get());
            results.put("ListObjects", listCount.get());
            results.put("PutObject", putCount.get());
            results.put("PutMultipartObject", postCount.get());
            return results;
        }
    }
}
