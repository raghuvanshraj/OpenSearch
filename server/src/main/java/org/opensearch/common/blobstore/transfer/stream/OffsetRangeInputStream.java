/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.blobstore.transfer.stream;

import java.io.IOException;
import java.io.InputStream;

public abstract class OffsetRangeInputStream extends InputStream {
    public abstract long getFilePointer() throws IOException;
}
