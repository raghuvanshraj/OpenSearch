/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.remotestore.multipart.mocks;

import org.apache.lucene.index.CorruptIndexException;
import org.opensearch.common.Stream;
import org.opensearch.common.StreamProvider;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.fs.FsBlobContainer;
import org.opensearch.common.blobstore.fs.FsBlobStore;
import org.opensearch.common.blobstore.stream.StreamContext;
import org.opensearch.common.blobstore.stream.write.UploadResponse;
import org.opensearch.common.blobstore.stream.write.WriteContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class MockFsBlobContainer extends FsBlobContainer {

    private static final int TRANSFER_TIMEOUT_MILLIS = 30000;

    private final boolean triggerDataIntegrityFailure;

    public MockFsBlobContainer(FsBlobStore blobStore, BlobPath blobPath, Path path, boolean triggerDataIntegrityFailure) {
        super(blobStore, blobPath, path);
        this.triggerDataIntegrityFailure = triggerDataIntegrityFailure;
    }

    @Override
    public boolean isMultiStreamUploadSupported() {
        return true;
    }

    @Override
    public CompletableFuture<UploadResponse> writeBlobByStreams(WriteContext writeContext) throws IOException {
        CompletableFuture<UploadResponse> completableFuture = new CompletableFuture<>();

        int nParts = 10;
        long partSize = writeContext.getFileSize() / nParts;
        StreamContext streamContext = writeContext.getStreamContext(partSize);
        StreamProvider streamProvider = streamContext.getStreamProvider();
        final Path file = path.resolve(writeContext.getFileName());
        byte[] buffer = new byte[(int) writeContext.getFileSize()];
        AtomicLong totalContentRead = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(streamContext.getNumberOfParts());
        for (int partIdx = 0; partIdx < streamContext.getNumberOfParts(); partIdx++) {
            int finalPartIdx = partIdx;
            Thread thread = new Thread(() -> {
                try {
                    Stream stream = streamProvider.provideStream(finalPartIdx);
                    InputStream inputStream = stream.getInputStream();
                    long remainingContentLength = stream.getContentLength();
                    long offset = stream.getOffset();
                    while (remainingContentLength > 0) {
                        int readContentLength = inputStream.read(buffer, (int) offset, (int) remainingContentLength);
                        totalContentRead.addAndGet(readContentLength);
                        remainingContentLength -= readContentLength;
                        offset += readContentLength;
                    }
                    inputStream.close();
                } catch (IOException e) {
                    completableFuture.completeExceptionally(e);
                } finally {
                    latch.countDown();
                }
            });
            thread.start();
        }
        try {
            if (!latch.await(TRANSFER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException("Timed out waiting for file transfer to complete for " + writeContext.getFileName());
            }
        } catch (InterruptedException e) {
            throw new IOException("Await interrupted on CountDownLatch, transfer failed for " + writeContext.getFileName());
        }
        try (OutputStream outputStream = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
            outputStream.write(buffer);
        }
        if (writeContext.getFileSize() != totalContentRead.get()) {
            throw new IOException(
                "Incorrect content length read for file "
                    + writeContext.getFileName()
                    + ", actual file size: "
                    + writeContext.getFileSize()
                    + ", bytes read: "
                    + totalContentRead.get()
            );
        }

        try {
            // bulks need to succeed for segment files to be generated
            if (isSegmentFile(writeContext.getFileName()) && triggerDataIntegrityFailure) {
                completableFuture.completeExceptionally(
                    new RuntimeException(
                        new CorruptIndexException(
                            "Data integrity check failure for file: " + writeContext.getFileName(),
                            writeContext.getFileName()
                        )
                    )
                );
            } else {
                writeContext.getUploadFinalizer().accept(true);
                completableFuture.complete(new UploadResponse(true));
            }
        } catch (Exception e) {
            completableFuture.completeExceptionally(e);
        }

        return completableFuture;
    }

    private boolean isSegmentFile(String filename) {
        return !filename.endsWith(".tlog") && !filename.endsWith(".ckp");
    }
}
