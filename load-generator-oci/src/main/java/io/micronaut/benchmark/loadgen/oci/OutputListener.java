package io.micronaut.benchmark.loadgen.oci;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public interface OutputListener {
    void onData(ByteBuffer data);

    void onComplete();

    class Waiter implements OutputListener {
        private final Lock lock = new ReentrantLock();
        private final Condition foundCondition = lock.newCondition();
        private ByteBuffer pattern;
        private boolean done = false;

        public Waiter(ByteBuffer initialPattern) {
            this.pattern = initialPattern;
        }

        @Override
        public void onData(ByteBuffer byteBuffer) {
            lock.lock();
            try {
                while (byteBuffer.hasRemaining() && pattern != null) {
                    byte expected = pattern.get();
                    byte actual = byteBuffer.get();
                    if (actual != expected) {
                        pattern.rewind();
                    } else if (!pattern.hasRemaining()) {
                        pattern = null;
                        foundCondition.signalAll();
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void onComplete() {
            lock.lock();
            try {
                done = true;
                foundCondition.signalAll();
            } finally {
                lock.unlock();
            }
        }

        public void awaitWithNextPattern(ByteBuffer nextPattern) {
            lock.lock();
            try {
                while (pattern != null) {
                    if (done) {
                        throw new IllegalStateException("Pattern not found");
                    }
                    foundCondition.awaitUninterruptibly();
                }
                pattern = nextPattern;
            } finally {
                lock.unlock();
            }
        }
    }

    class Write implements OutputListener, Closeable {
        private static final Logger LOG = LoggerFactory.getLogger(Write.class);

        private final OutputStream outputStream;

        public Write(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public void onData(ByteBuffer data) {
            try {
                outputStream.write(data.array(), data.arrayOffset() + data.position(), data.remaining());
            } catch (ClosedChannelException ignored) {
            } catch (IOException e) {
                LOG.error("Failed to write data", e);
            }
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void close() throws IOException {
            outputStream.close();
        }
    }

    class Stream extends OutputStream {
        private final List<OutputListener> listeners;

        public Stream(List<OutputListener> listeners) {
            this.listeners = listeners;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (OutputListener listener : listeners) {
                listener.onData(ByteBuffer.wrap(b, off, len));
            }
        }

        @Override
        public void close() throws IOException {
            for (OutputListener listener : listeners) {
                listener.onComplete();
            }
        }
    }
}
