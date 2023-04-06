/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.remotestore.multipart.mocks;

import org.opensearch.OpenSearchException;
import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.fs.FsBlobStore;

import java.io.IOException;
import java.nio.file.Path;

public class MockFsBlobStore extends FsBlobStore {

    public MockFsBlobStore(int bufferSizeInBytes, Path path, boolean readonly) throws IOException {
        super(bufferSizeInBytes, path, readonly);
    }

    @Override
    public BlobContainer blobContainer(BlobPath path) {
        try {
            return new MockFsBlobContainer(this, path, buildAndCreate(path));
        } catch (IOException ex) {
            throw new OpenSearchException("failed to create blob container", ex);
        }
    }
}
