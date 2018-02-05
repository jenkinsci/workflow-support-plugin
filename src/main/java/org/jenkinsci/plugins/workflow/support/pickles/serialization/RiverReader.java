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

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.io.IOUtils;
import org.jboss.marshalling.ChainingObjectResolver;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.ObjectResolver;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.support.concurrent.Futures;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;

import static org.apache.commons.io.IOUtils.*;
import org.jboss.marshalling.ByteInput;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Reads program data file that stores the object graph of the CPS-transformed program.
 *
 * <p>
 * The file consists of two separate object streams. The first stream contains a list of {@link Pickle}s,
 * which are used to restore stateful objects. The second stream is the main stream that contains
 * the main program state, which includes references to {@link DryCapsule} (which gets replaced to
 * their respective stateful objects.
 *
 * @author Kohsuke Kawaguchi
 */
public class RiverReader implements Closeable {
    private final File file;
    private final ClassLoader classLoader;
    /**
     * {@link DryOwner} in the serialized graph gets replaced by this object.
     */
    private final FlowExecutionOwner owner;

    /**
     * {@link ObjectResolver} that replaces {@link DryOwner} by the actual owner.
     */
    private ObjectResolver ownerResolver = new ObjectResolver() {
        @Override
        public Object readResolve(Object replacement) {
            if (replacement instanceof DryOwner)
                return owner;
            return replacement;
        }

        @Override
        public Object writeReplace(Object original) {
            throw new IllegalStateException();
        }
    };

    @SuppressFBWarnings(value="MS_SHOULD_BE_FINAL", justification="intentionally not")
    @Restricted(NoExternalUse.class) // tests only
    public static @CheckForNull ObjectResolver customResolver = null;

    private InputStream in;

    public RiverReader(File f, ClassLoader classLoader, FlowExecutionOwner owner) throws IOException {
        this.file = f;
        this.classLoader = classLoader;
        this.owner = owner;
    }

    private int parseHeader(DataInputStream din) throws IOException {
        if (din.readLong()!= RiverWriter.HEADER)
            throw new IOException("Invalid stream header");

        short v = din.readShort();
        if (v!=1)
            throw new IOException("Unexpected stream version: "+v);

        return din.readInt();
    }

    @Deprecated
    public ListenableFuture<Unmarshaller> restorePickles() throws IOException {
        return restorePickles(new ArrayList<ListenableFuture<?>>());
    }

    /**
     * Step 1. Start unmarshalling pickles in the persisted stream,
     * and return the future that will signal when that is all complete.
     *
     * Once the pickles are restored, the future yields {@link Unmarshaller}
     * that can be then used to load the objects persisted by {@link RiverWriter}.
     */
    public ListenableFuture<Unmarshaller> restorePickles(Collection<ListenableFuture<?>> pickleFutures) throws IOException {
        in = openStreamAt(0);
        try {
        DataInputStream din = new DataInputStream(in);
        int offset = parseHeader(din);

        // load the pickle stream
        List<Pickle> pickles = readPickles(offset);
        final PickleResolver evr = new PickleResolver(pickles, owner);

        // prepare the unmarshaller to load the main stream, by using yet-fulfilled PickleResolver
        MarshallingConfiguration config = new MarshallingConfiguration();
        config.setClassResolver(new SimpleClassResolver(classLoader));
        //config.setSerializabilityChecker(new SerializabilityCheckerImpl());
        config.setObjectResolver(combine(evr, ownerResolver));
        Unmarshaller eu = new RiverMarshallerFactory().createUnmarshaller(config);
        eu.start(Marshalling.createByteInput(din));

        final Unmarshaller sandboxed = new SandboxedUnmarshaller(eu);

        // start rehydrating, and when done make the unmarshaller available
        return Futures.transform(evr.rehydrate(pickleFutures), new Function<PickleResolver, Unmarshaller>() {
            public Unmarshaller apply(PickleResolver input) {
                return sandboxed;
            }
        });
        } catch (IOException x) {
            in.close();
            throw x;
        }
    }

    private List<Pickle> readPickles(int offset) throws IOException {
        BufferedInputStream es = openStreamAt(offset);
        try {
            MarshallingConfiguration config = new MarshallingConfiguration();
            config.setClassResolver(new SimpleClassResolver(classLoader));
            config.setObjectResolver(combine(ownerResolver));
            Unmarshaller eu = new RiverMarshallerFactory().createUnmarshaller(config);
            try {
                eu.start(Marshalling.createByteInput(es));
                return (List<Pickle>)eu.readObject();
            } catch (ClassNotFoundException e) {
                throw new IOException("Failed to read the stream",e);
            } finally {
                eu.finish();
            }
        } finally {
            closeQuietly(es);
        }
    }

    private BufferedInputStream openStreamAt(int offset) throws IOException {
        InputStream in = new FileInputStream(file);
        IOUtils.skipFully(in, offset);
        return new BufferedInputStream(in);
    }

    private ObjectResolver combine(ObjectResolver... resolvers) {
        List<ObjectResolver> _resolvers = Lists.newArrayList(resolvers);
        if (customResolver != null) {
            _resolvers.add(0, customResolver);
        }
        return _resolvers.size() == 1 ? _resolvers.get(0) : new ChainingObjectResolver(_resolvers);
    }

    @Override public void close() {
        if (in != null) {
            try {
                in.close();
            } catch (IOException x) {
                Logger.getLogger(RiverReader.class.getName()).log(Level.WARNING, "could not close stream on " + file, x);
            }
        }
    }

    /** Applies {@link GroovySandbox} to a delegate unmarshaller. */
    private static final class SandboxedUnmarshaller implements Unmarshaller {

        private final Unmarshaller delegate;

        SandboxedUnmarshaller(Unmarshaller delegate) {
            this.delegate = delegate;
        }

        @FunctionalInterface
        private interface ReadSAM<T> {
            T call() throws ClassNotFoundException, IOException;
        }

        private static <T> T sandbox(ReadSAM<T> lambda) throws ClassNotFoundException, IOException {
            // TODO runInSandbox overloads are not friendly to lambdas due to checked exceptions.
            // Would be nicer to define something like:
            // public static final class GroovySandbox implements AutoCloseable {
            //     public static GroovySandbox of(Whitelist wl);
            //     @Override public void close() {…}
            // }
            // so you could write more simply:
            // try (GroovySandbox sandbox = GroovySandbox.of(Whitelist.all())) {
            //     return delegate.readObject();
            // }
            try {
                return GroovySandbox.runInSandbox(lambda::call, Whitelist.all());
            } catch (ClassNotFoundException x) {
                throw x;
            } catch (IOException x) {
                throw x;
            } catch (RuntimeException x) {
                throw x;
            } catch (Exception x) {
                throw new AssertionError(x);
            }
        }

        @Override public Object readObject() throws ClassNotFoundException, IOException {
            return sandbox(() -> delegate.readObject());
        }

        @Override public Object readObjectUnshared() throws ClassNotFoundException, IOException {
            return sandbox(() -> delegate.readObjectUnshared());
        }

        @Override public <T> T readObject(Class<T> type) throws ClassNotFoundException, IOException {
            return sandbox(() -> delegate.readObject(type));
        }

        @Override public <T> T readObjectUnshared(Class<T> type) throws ClassNotFoundException, IOException {
            return sandbox(() -> delegate.readObjectUnshared(type));
        }

        @Override public void start(ByteInput newInput) throws IOException {
            delegate.start(newInput);
        }

        @Override public void clearInstanceCache() throws IOException {
            delegate.clearInstanceCache();
        }

        @Override public void clearClassCache() throws IOException {
            delegate.clearClassCache();
        }

        @Override public void finish() throws IOException {
            delegate.finish();
        }

        @Override public int read() throws IOException {
            return delegate.read();
        }

        @Override public int read(byte[] b) throws IOException {
            return delegate.read(b);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override public long skip(long n) throws IOException {
            return delegate.skip(n);
        }

        @Override public int available() throws IOException {
            return delegate.available();
        }

        @Override public void close() throws IOException {
            delegate.close();
        }

        @Override public void readFully(byte[] b) throws IOException {
            delegate.readFully(b);
        }

        @Override public void readFully(byte[] b, int off, int len) throws IOException {
            delegate.readFully(b, off, len);
        }

        @Override public int skipBytes(int n) throws IOException {
            return delegate.skipBytes(n);
        }

        @Override public boolean readBoolean() throws IOException {
            return delegate.readBoolean();
        }

        @Override public byte readByte() throws IOException {
            return delegate.readByte();
        }

        @Override public int readUnsignedByte() throws IOException {
            return delegate.readUnsignedByte();
        }

        @Override public short readShort() throws IOException {
            return delegate.readShort();
        }

        @Override public int readUnsignedShort() throws IOException {
            return delegate.readUnsignedShort();
        }

        @Override public char readChar() throws IOException {
            return delegate.readChar();
        }

        @Override public int readInt() throws IOException {
            return delegate.readInt();
        }

        @Override public long readLong() throws IOException {
            return delegate.readLong();
        }

        @Override public float readFloat() throws IOException {
            return delegate.readFloat();
        }

        @Override public double readDouble() throws IOException {
            return delegate.readDouble();
        }

        @Override public String readLine() throws IOException {
            return delegate.readLine();
        }

        @Override public String readUTF() throws IOException {
            return delegate.readUTF();
        }

    }

}
