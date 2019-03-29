/*
 * The MIT License
 *
 * Copyright 2019 CloudBees, Inc.
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

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.pickles.PickleFactory;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;

/**
 * Saves a {@link Pickle} and then offers it whenever asked for that type.
 */
public final class PickleDynamicContext extends DynamicContext {

    private static final Logger LOGGER = Logger.getLogger(PickleDynamicContext.class.getName());

    private final Pickle pickle;
    private final Class<?> type;

    public PickleDynamicContext(Object o) {
        this.pickle = pickle(o);
        this.type = o.getClass();
        LOGGER.log(Level.FINE, "saving a {0} of {1}", new Object[] {pickle, type});
    }

    private static Pickle pickle(Object o) {
        for (PickleFactory f : PickleFactory.all()) {
            Pickle pickle = f.writeReplace(o);
            if (pickle != null) {
                return pickle;
            }
        }
        throw new IllegalArgumentException("no pickle factory found for " + o);
    }

    @Override public <T> T get(Class<T> key, DelegatedContext context) throws IOException, InterruptedException {
        if (key.isAssignableFrom(type)) {
            FlowExecutionOwner owner = context.get(FlowExecution.class).getOwner();
            LOGGER.log(Level.FINE, "rehydrating a {0} for {1}", new Object[] {pickle, owner});
            try {
                return key.cast(pickle.rehydrate(owner).get());
            } catch (ExecutionException x) {
                throw new IOException(x.getCause());
            }
        } else {
            return null;
        }
    }

}
