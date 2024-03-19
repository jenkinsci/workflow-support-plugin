/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.workflow.support.pickles.serialization;

import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.pickles.PickleFactory;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.ObjectResolver;
import org.jboss.marshalling.river.RiverMarshallerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;
import org.jboss.marshalling.ByteOutput;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox;

/**
 * {@link ObjectOutputStream} compatible object graph serializer
 * that handles stateful objects for proper rehydration later.
 *
 * @author Kohsuke Kawaguchi
 * @see RiverMarshallerFactory
 * @see RiverReader
 */
public class RiverWriter implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(RiverWriter.class.getName());

    /**
     * File that we are writing to.
     */
    private final File file;

    /**
     * The location of the persisted file implies a {@link FlowExecutionOwner}, so we don't
     * actually store the owner object.
     */
    private final FlowExecutionOwner owner;

    /**
     * Writes to {@link #file}.
     */
    private final FileChannel channel;

    /**
     * Handles object graph -> byte[] conversion
     */
    private final Marshaller marshaller;

    private boolean pickling;

    /**
     * Persisted form of stateful objects that need special handling during rehydration.
     */
    List<Pickle> pickles = new ArrayList<Pickle>();

    @Deprecated
    public RiverWriter(File f, FlowExecutionOwner _owner) throws IOException {
        this(f, _owner, pickleFactories());
    }

    private static Collection<? extends PickleFactory> pickleFactories() {
        Collection<? extends PickleFactory> pickleFactories = PickleFactory.all();
        if (pickleFactories.isEmpty()) {
            throw new IllegalStateException("JENKINS-26137: Jenkins is shutting down");
        }
        return pickleFactories;
    }

    public RiverWriter(File f, FlowExecutionOwner _owner, final Collection<? extends PickleFactory> pickleFactories) throws IOException {
        file = f;
        owner = _owner;
        channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE);
        channel.write(HEADER_BUFFER.duplicate());
        LOGGER.fine(() -> "Starting to save " + file);

        MarshallingConfiguration config = new MarshallingConfiguration();
        //config.setSerializabilityChecker(new SerializabilityCheckerImpl());
        config.setObjectResolver(new ObjectResolver() {
            public Object readResolve(Object o) {
                throw new IllegalStateException();
            }

            public Object writeReplace(Object o) {
                if (o == owner) {
                    return new DryOwner();
                }

                if (pickling) {
                    for (PickleFactory f : pickleFactories) {
                        Pickle v = f.writeReplace(o);
                        if (v != null) {
                            pickles.add(v);
                            return new DryCapsule(pickles.size() - 1); // let Pickle be serialized into the stream
                        }
                    }
                }
                return o;
            }
        });

        marshaller = new RiverMarshallerFactory().createMarshaller(config);
        marshaller.start(new FileChannelOutput(channel));
        pickling = true;
    }

    public void writeObject(Object o) throws IOException {
        try {
            GroovySandbox.runInSandbox(() -> {
                marshaller.writeObject(o);
                return null;
            }, Whitelist.all());
        } catch (IOException x) {
            throw x;
        } catch (RuntimeException x) {
            throw x;
        } catch (Exception x) {
            throw new AssertionError(x);
        }
        LOGGER.fine(() -> "Wrote main body to " + file);
    }

    /**
     * @deprecated Apparently unused.
     */
    @Deprecated
    public ObjectOutput getObjectOutput() {
        return marshaller;
    }

    public void close() throws IOException {
        int ephemeralsOffset;
        try {
            marshaller.finish();
            ephemeralsOffset = (int)channel.position();

            // write the ephemerals stream
            pickling = false;
            marshaller.start(new FileChannelOutput(channel));
            marshaller.writeObject(pickles);
            marshaller.finish();

            // back fill the offset to the ephemerals stream
            channel.position(EPHEMERALS_BACKPTR);
            ByteBuffer ephemeralsPtrBuffer = ByteBuffer.allocate(4).putInt(ephemeralsOffset);
            ephemeralsPtrBuffer.flip();
            channel.write(ephemeralsPtrBuffer);
            channel.force(true);
        } finally {
            channel.close();
        }
        LOGGER.fine(() -> "Closed " + file + "; pickle offset @" + ephemeralsOffset);
    }

    private static class FileChannelOutput implements ByteOutput {
        private final FileChannel channel;
        /** Used to reduce allocation for single-byte writes. */
        private final byte[] singleton = new byte[1];

        private FileChannelOutput(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public void write(int b) throws IOException {
            singleton[0] = (byte)b; // Downcasting as per interface Javadoc.
            channel.write(ByteBuffer.wrap(singleton));
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            channel.write(ByteBuffer.wrap(bytes));
        }

        @Override
        public void write(byte[] bytes, int off, int len) throws IOException {
            channel.write(ByteBuffer.wrap(bytes, off, len));
        }

        @Override
        public void close() throws IOException {
            // We only close the channel once all writes are complete.
        }

        @Override
        public void flush() throws IOException {
            // We invoke FileChannel.force manually before closing the channel.
        }
    }

    /*constant*/ static final long HEADER = 7330745437582215633L;
    /*constant*/ static final short VERSION = 1;
    private static final int EPHEMERALS_BACKPTR = 10; // sizeof(long) + sizeof(short)
    /** Used to reduce allocation. Always call {@link ByteBuffer#duplicate} rather than using this directly. */
    // Downcasting to Buffer is needed to avoid NoSuchMethodError when running on Java 9+ due to ByteBuffer method return type changes.
    private static final ByteBuffer HEADER_BUFFER = (ByteBuffer)((Buffer)ByteBuffer.allocate(14)
            .putLong(HEADER)
            .putShort(VERSION)
            .putInt(0) // Space for EPHEMERALS_BACKPTR
            .asReadOnlyBuffer())
            .flip(); 
}
