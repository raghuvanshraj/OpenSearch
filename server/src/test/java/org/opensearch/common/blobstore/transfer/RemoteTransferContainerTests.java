/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.blobstore.transfer;

import org.junit.Before;
import org.opensearch.common.Stream;
import org.opensearch.common.blobstore.stream.StreamContext;
import org.opensearch.common.blobstore.stream.write.WritePriority;
import org.opensearch.common.blobstore.transfer.stream.OffsetRangeFileInputStream;
import org.opensearch.common.blobstore.transfer.stream.OffsetRangeInputStream;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class RemoteTransferContainerTests extends OpenSearchTestCase {

    private static final int TEST_FILE_SIZE_BYTES = 128;

    private Path testFile;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        testFile = createTempFile();
        Files.write(testFile, randomByteArrayOfLength(TEST_FILE_SIZE_BYTES), StandardOpenOption.APPEND);
    }

    public void testSupplyStreamContextDivisibleParts() throws IOException, InterruptedException {
        try (
            RemoteTransferContainer remoteTransferContainer = new RemoteTransferContainer(
                testFile.getFileName().toString(),
                testFile.getFileName().toString(),
                TEST_FILE_SIZE_BYTES,
                true,
                WritePriority.HIGH,
                new RemoteTransferContainer.OffsetRangeInputStreamSupplier() {
                    @Override
                    public OffsetRangeInputStream get(long size, long position) throws IOException {
                        return new OffsetRangeFileInputStream(testFile, size, position);
                    }
                },
                0
            )
        ) {
            testSupplyStreamContext(remoteTransferContainer, 16, 16, 8);
        }
    }

    public void testSupplyStreamContextNonDivisibleParts() throws IOException, InterruptedException {
        try (
            RemoteTransferContainer remoteTransferContainer = new RemoteTransferContainer(
                testFile.getFileName().toString(),
                testFile.getFileName().toString(),
                TEST_FILE_SIZE_BYTES,
                true,
                WritePriority.HIGH,
                new RemoteTransferContainer.OffsetRangeInputStreamSupplier() {
                    @Override
                    public OffsetRangeInputStream get(long size, long position) throws IOException {
                        return new OffsetRangeFileInputStream(testFile, size, position);
                    }
                },
                0
            )
        ) {
            testSupplyStreamContext(remoteTransferContainer, 10, 8, 13);
        }
    }

    private void testSupplyStreamContext(
        RemoteTransferContainer remoteTransferContainer,
        long partSize,
        long lastPartSize,
        int expectedPartCount
    ) throws InterruptedException {
        StreamContext streamContext = remoteTransferContainer.supplyStreamContext(partSize);
        int partCount = streamContext.getNumberOfParts();
        assertEquals(expectedPartCount, partCount);
        Thread[] threads = new Thread[partCount];
        long totalContentLength = remoteTransferContainer.getContentLength();
        assert partSize * (partCount - 1) + lastPartSize == totalContentLength
            : "part sizes and last part size don't add up to total content length";
        logger.info("partSize: {}, lastPartSize: {}, partCount: {}", partSize, lastPartSize, streamContext.getNumberOfParts());
        for (int partIdx = 0; partIdx < partCount; partIdx++) {
            int finalPartIdx = partIdx;
            long expectedPartSize = (partIdx == partCount - 1) ? lastPartSize : partSize;
            long expectedOffset = partIdx * partSize;
            threads[partIdx] = new Thread(() -> {
                try {
                    Stream stream = streamContext.getStreamProvider().provideStream(finalPartIdx);
                    assertEquals(expectedPartSize, stream.getContentLength());
                    assertEquals(expectedOffset, stream.getOffset());
                } catch (IOException e) {
                    fail("IOException during stream creation");
                }
            });
            threads[partIdx].start();
        }
        for (int i = 0; i < partCount; i++) {
            threads[i].join();
        }
    }

    public void testSupplyStreamContextCalledTwice() throws IOException {
        try (
            RemoteTransferContainer remoteTransferContainer = new RemoteTransferContainer(
                testFile.getFileName().toString(),
                testFile.getFileName().toString(),
                TEST_FILE_SIZE_BYTES,
                true,
                WritePriority.HIGH,
                new RemoteTransferContainer.OffsetRangeInputStreamSupplier() {
                    @Override
                    public OffsetRangeInputStream get(long size, long position) throws IOException {
                        return new OffsetRangeFileInputStream(testFile, size, position);
                    }
                },
                0
            )
        ) {
            remoteTransferContainer.supplyStreamContext(16);
            assertThrows(RuntimeException.class, () -> remoteTransferContainer.supplyStreamContext(16));
        }
    }
}
