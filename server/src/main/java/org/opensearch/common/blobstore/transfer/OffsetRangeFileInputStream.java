package org.opensearch.common.blobstore.transfer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class OffsetRangeFileInputStream extends InputStream {
    private final InputStream inputStream;
    private final String fileName;
    private final FileChannel fileChannel;

    private final long actualSizeToRead;
    // This is the maximum position till stream is to be read. If read methods exceed maxPos then bytes are read
    // till maxPos. If no byte is left after maxPos, then -1 is returned from read methods.
    private final long limit;
    // Position in stream from which read will start.
    private long counter = 0;

    private long markPointer;
    private long markCounter;

    public OffsetRangeFileInputStream(Path path, String fileName, long size, long position) throws IOException {
        fileChannel = FileChannel.open(path, StandardOpenOption.READ);
        fileChannel.position(position);
        inputStream = Channels.newInputStream(fileChannel);
        long totalLength = fileChannel.size();
        this.counter = 0;
        this.limit = size;
        this.fileName = fileName;
        if ((totalLength - position) > limit) {
            actualSizeToRead = limit;
        } else {
            actualSizeToRead = totalLength - position;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (fileChannel.position() >= fileChannel.size()) {
            return -1;
        }
        if (fileChannel.position() + len > fileChannel.size()) {
            len = (int) (fileChannel.size() - fileChannel.position());
        }
        if (counter + len > limit) {
            len = (int) (limit - counter);
        }
        if (len <= 0) {
            return -1;
        }

        inputStream.read(b, off, len);
        counter += len;
        return len;
    }

    @Override
    public int read() throws IOException {
        if (counter++ >= limit) {
            return -1;
        }
        return (fileChannel.position() < fileChannel.size()) ? (inputStream.read() & 0xff) : -1;
    }

    @Override
    public boolean markSupported() {
        return true;
    }

    @Override
    public synchronized void mark(int readlimit) {
        try {
            markPointer = fileChannel.position();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        markCounter = counter;
    }

    @Override
    public synchronized void reset() throws IOException {
        fileChannel.position(markPointer);
        counter = markCounter;
    }

    public FileChannel getFileChannel() {
        return fileChannel;
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
        super.close();
    }
}
