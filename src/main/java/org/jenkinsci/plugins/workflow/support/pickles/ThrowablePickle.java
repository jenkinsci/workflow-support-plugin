/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.pickles;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.Extension;
import hudson.Functions;
import hudson.remoting.ProxyException;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.output.NullOutputStream;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.pickles.PickleFactory;
import org.jenkinsci.plugins.workflow.support.concurrent.Futures;
import org.jenkinsci.plugins.workflow.support.pickles.serialization.RiverWriter;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Ensures that exceptions are safely serializable.
 * Replaces anything problematic with {@link ProxyException}.
 * Mainly defends against {@link NotSerializableException}.
 */
@Restricted(NoExternalUse.class)
public final class ThrowablePickle extends Pickle {

    private static final Logger LOGGER = Logger.getLogger(ThrowablePickle.class.getName());
    private static final long serialVersionUID = 1;

    /** Stack trace of the original exception. */
    private final ProxyException t;
    /** Class name of the original exception. */
    private final String clazz;
    /** Stack trace of the problem serializing the original exception. */
    private final String error;

    private ThrowablePickle(Throwable t, Exception x) {
        LOGGER.log(Level.FINE, "Sanitizing {0} due to {1}", new Object[] {t, x});
        this.t = new ProxyException(t);
        clazz = t.getClass().getName();
        error = Functions.printThrowable(x);
    }

    @Override public ListenableFuture<?> rehydrate(FlowExecutionOwner owner) {
        try {
            owner.getListener().getLogger().println(error.trim());
            owner.getListener().getLogger().println("Loading unserializable exception; result will no longer be assignable to class " + clazz);
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
        }
        return Futures.immediateFuture(t);
    }

    @Extension public static final class Factory extends PickleFactory {

        /** @see RiverWriter */
        @Override public Pickle writeReplace(Object o) {
            if (o instanceof Throwable) {
                Throwable t = (Throwable) o;
                try (OutputStream ignore = new NullOutputStream();
                     // Could set an ObjectResolver to ignore _other_ pickles, but we do really expect an Exception to have fields of, say, FilePath.
                     Marshaller marshaller = new RiverMarshallerFactory().createMarshaller(new MarshallingConfiguration())) {
                    GroovySandbox.runInSandbox(() -> {
                        marshaller.start(Marshalling.createByteOutput(ignore));
                        marshaller.writeObject(t);
                        return null;
                    }, Whitelist.all());
                } catch (Exception x) {
                    return new ThrowablePickle(t, x);
                }
            }
            return null;
        }

    }

}
