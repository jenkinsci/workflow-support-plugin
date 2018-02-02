/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.concurrent;

import hudson.FilePath;
import hudson.Util;
import hudson.remoting.VirtualChannel;
import hudson.util.DaemonThreadFactory;
import hudson.util.NamingThreadFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Allows operations to be limited in execution time.
 * For example, {@link VirtualChannel#call} or {@link FilePath#isDirectory} could otherwise hang forever.
 * Use in a {@code try}-with-resources block.
 */
public class Timeout implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(Timeout.class.getName());

    static class ClassloaderSanityDaemonThreadFactory extends DaemonThreadFactory {
        public Thread newThread(Runnable r) {
            Thread t = super.newThread(r);
            t.setContextClassLoader(Timeout.class.getClassLoader());
            return t;
        }
    }


    private static final ScheduledExecutorService interruptions = Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory(new ClassloaderSanityDaemonThreadFactory(), "Timeout.interruptions"));

    private final Thread thread;
    private volatile boolean completed;
    private long endTime;
    /*
    private final String originalName;
    */

    private Timeout(long time, TimeUnit unit) {
        thread = Thread.currentThread();
        LOGGER.log(Level.FINER, "Might interrupt {0} after {1} {2}", new Object[] {thread.getName(), time, unit});
        /* see below:
        originalName = thread.getName();
        thread.setName(String.format("%s (Timeout@%h: %s)", originalName, this, Util.getTimeSpanString(unit.toMillis(time))));
        */
        ping(time, unit);
    }

    @Override public void close() {
        completed = true;
        /*
        thread.setName(originalName);
        */
        LOGGER.log(Level.FINER, "completed {0}", thread.getName());
    }

    private void ping(final long time, final TimeUnit unit) {
        interruptions.schedule(() -> {
            if (completed) {
                LOGGER.log(Level.FINER, "{0} already finished, no need to interrupt", thread.getName());
                return;
            }
            if (LOGGER.isLoggable(Level.FINE)) {
                Throwable t = new Throwable();
                t.setStackTrace(thread.getStackTrace());
                LOGGER.log(Level.FINE, "Interrupting " + thread.getName() + " after " + time + " " + unit, t);
            }
            thread.interrupt();
            if (endTime == 0) {
                // First interruption.
                endTime = System.nanoTime();
            } else {
                // Not dead yet?
                String unresponsiveness = Util.getTimeSpanString((System.nanoTime() - endTime) / 1_000_000);
                LOGGER.log(Level.INFO, "{0} unresponsive for {1}", new Object[] {thread.getName(), unresponsiveness});
                /* TODO does not work; thread.getName() does not seem to return the current value when called from another thread, even w/ synchronized access, and running with -Xint
                thread.setName(thread.getName().replaceFirst(String.format("(Timeout@%h: )[^)]+", this), "$1unresponsive for " + unresponsiveness));
                */
            }
            ping(5, TimeUnit.SECONDS);
        }, time, unit);
    }

    public static Timeout limit(final long time, final TimeUnit unit) {
        return new Timeout(time, unit);
    }

    // TODO JENKINS-32986 offer a variant that will escalate to Thread.stop

}
