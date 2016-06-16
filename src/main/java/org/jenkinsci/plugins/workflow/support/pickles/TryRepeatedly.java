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

package org.jenkinsci.plugins.workflow.support.pickles;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import hudson.console.ModelHyperlinkNote;
import hudson.model.TaskListener;
import java.io.IOException;
import jenkins.util.Timer;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.Pickle;

/**
 * {@link ListenableFuture} that promises a value that needs to be periodically tried.
 * Specialized for use from {@link Pickle#rehydrate(FlowExecutionOwner)}.
 */
public abstract class TryRepeatedly<V> extends AbstractFuture<V> {

    private static final Logger LOGGER = Logger.getLogger(TryRepeatedly.class.getName());

    private final int delay;
    private ScheduledFuture<?> next;
    /** Number of {@link #tryLater} calls to run between logging attempts. */
    private float backoff = 1;
    /** Amount by which {@link #backoff} gets multiplied, so we do not flood the log with endless messages. */
    private static final float BACKOFF_EXPONENT = 1.42f; // >√2
    /** Reset to {@link #backoff} after each message, then decremented by on each call to {@link #tryLater}. */
    private int retriesRemaining;

    protected TryRepeatedly(int delay) {
        this(delay, delay);
    }

    protected TryRepeatedly(int delay, int initialDelay) {
        this.delay = delay;
        tryLater(initialDelay);
    }

    /**
     * Override to supply the owner passed to {@link Pickle#rehydrate(FlowExecutionOwner)}.
     */
    protected @Nonnull FlowExecutionOwner getOwner() {
        return FlowExecutionOwner.dummyOwner();
    }

    /**
     * Assuming {@link #getOwner} has been overridden, override to print a message to the build log explaining why the pickle is still unloadable.
     * Could use {@link ModelHyperlinkNote} etc.
     */
    protected void printWaitingMessage(@Nonnull TaskListener listener) {
        listener.getLogger().println("Still trying to load " + this);
    }

    private void tryLater(int currentDelay) {
        if (isCancelled())      return;

        next = Timer.get().schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    V v = tryResolve();
                    if (v == null) {
                        if (retriesRemaining == 0) {
                            try {
                                printWaitingMessage(getOwner().getListener());
                            } catch (IOException x) {
                                LOGGER.log(Level.WARNING, null, x);
                            }
                            backoff *= BACKOFF_EXPONENT;
                            retriesRemaining = (int) backoff;
                        } else {
                            retriesRemaining--;
                        }
                        tryLater(delay);
                    } else {
                        set(v);
                    }
                } catch (Throwable t) {
                    setException(t);
                }
            }
        }, currentDelay, TimeUnit.SECONDS);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (next != null) {
            next.cancel(mayInterruptIfRunning);
        }
        LOGGER.log(Level.FINE, "Cancelling {0} in {1}", new Object[] {this, getOwner()});
        return super.cancel(mayInterruptIfRunning);
    }

    /**
     * This method is called periodically to attempt to resolve the value that this future promises.
     *
     * @return
     *      null to retry this at a later moment.
     * @throws Exception
     *      Any exception thrown will cause the future to fail.
     */
    protected abstract @CheckForNull V tryResolve() throws Exception;
}
