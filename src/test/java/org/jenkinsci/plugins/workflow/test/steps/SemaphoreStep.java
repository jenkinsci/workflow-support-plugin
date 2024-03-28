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

package org.jenkinsci.plugins.workflow.test.steps;

import hudson.Extension;
import hudson.model.Run;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Step that blocks until signaled.
 * Starts running and waits for {@link #success(String, Object)} or {@link #failure(String, Throwable)} to be called, if they have not been already.
 */
public final class SemaphoreStep extends Step implements Serializable {

    private static final Logger LOGGER = Logger.getLogger(SemaphoreStep.class.getName());

    /** State of semaphore steps within one Jenkins home and thus (possibly restarting) test. */
    private static final class State {
        private static final Map<File,State> states = new HashMap<>();
        static synchronized State get() {
            File home = Jenkins.get().getRootDir();
            State state = states.get(home);
            if (state == null) {
                LOGGER.info(() -> "Initializing state in " + home);
                state = new State();
                states.put(home, state);
            }
            return state;
        }
        private State() {}
        private final Map<String,Integer> iota = new HashMap<>();
        synchronized int allocateNumber(String id) {
            Integer old = iota.get(id);
            if (old == null) {
                old = 0;
            }
            int number = old + 1;
            iota.put(id, number);
            return number;
        }
        final Map<String,KeyState> keyStates = new HashMap<>();
    }

    private final String id;
    private final int number;

    @DataBoundConstructor public SemaphoreStep(String id) {
        this.id = id;
        number = State.get().allocateNumber(id);
    }

    public String getId() {
        return id;
    }

    private String k() {
        return id + "/" + number;
    }

    /**@deprecated use {@link #success(String, Object)} */
    @Deprecated
    public void success(Object returnValue) {
        success(k(), returnValue);
    }

    /** Marks the step as having successfully completed; or, if not yet started, makes it do so synchronously when started. */
    public static void success(String k, Object returnValue) {
        State s = State.get();
        StepContext c;
        synchronized (s) {
            KeyState keyState = s.keyStates.get(k);
            if (!(keyState instanceof StartedState)) {
                LOGGER.info(() -> "Planning to unblock " + k + " as success");
                s.keyStates.put(k, new ImmediateSuccessState(returnValue));
                return;
            }
            c = getContext(s, k);
        }
        LOGGER.info(() -> "Unblocking " + k + " as success");
        c.onSuccess(returnValue);
    }

    /** @deprecated use {@link #failure(String, Throwable)} */
    @Deprecated
    public void failure(Throwable error) {
        failure(k(), error);
    }

    /** Marks the step as having failed; or, if not yet started, makes it do so synchronously when started. */
    public static void failure(String k, Throwable error) {
        State s = State.get();
        StepContext c;
        synchronized (s) {
            Object keyState = s.keyStates.get(k);
            if (!(keyState instanceof StartedState)) {
                LOGGER.info(() -> "Planning to unblock " + k + " as failure");
                s.keyStates.put(k, new ImmediateFailureState(error));
                return;
            }
            c = getContext(s, k);
        }
        LOGGER.info(() -> "Unblocking " + k + " as failure");
        c.onFailure(error);
    }

    /** @deprecated should not be needed */
    @Deprecated
    public StepContext getContext() {
        State s = State.get();
        synchronized (s) {
            return getContext(s, k());
        }
    }

    private static StepContext getContext(State s, String k) {
        assert Thread.holdsLock(s);
        return (StepContext) Jenkins.XSTREAM.fromXML(((StartedState) s.keyStates.get(k)).context);
    }

    public static void waitForStart(@NonNull String k, @CheckForNull Run<?,?> b) throws IOException, InterruptedException {
        State s = State.get();
        synchronized (s) {
            while (!(s.keyStates.get(k) instanceof StartedState)) {
                if (b != null && !b.isBuilding()) {
                    throw new AssertionError(JenkinsRule.getLog(b));
                }
                s.wait(100);
            }
        }
    }

    @Override public StepExecution start(StepContext context) throws Exception {
        return new Execution(context, k());
    }

    public static class Execution extends AbstractStepExecutionImpl {

        private final String k;

        Execution(StepContext context, String k) {
            super(context);
            this.k = k;
        }

        @Override public boolean start() throws Exception {
            State s = State.get();
            Object returnValue = null;
            Throwable error = null;
            boolean success = false, failure = false, sync = true;
            String c = Jenkins.XSTREAM.toXML(getContext());
            synchronized (s) {
                Object keyState = s.keyStates.get(k);
                if (keyState instanceof ImmediateSuccessState) {
                    success = true;
                    returnValue = ((ImmediateSuccessState) keyState).returnValue;
                } else if (keyState instanceof ImmediateFailureState) {
                    failure = true;
                    error = ((ImmediateFailureState) keyState).error;
                } else if (keyState == null) {
                    s.keyStates.put(k, new StartedState(c));
                } else {
                    throw new IllegalStateException("Unable to start semaphore step " + k + " in state " + keyState);
                }
            }
            if (success) {
                LOGGER.info(() -> "Immediately running " + k);
                getContext().onSuccess(returnValue);
            } else if (failure) {
                LOGGER.info(() -> "Immediately failing " + k);
                getContext().onFailure(error);
            } else {
                LOGGER.info(() -> "Blocking " + k);
                sync = false;
            }
            synchronized (s) {
                // Even if we completed immediately, set the state to StartedState so waitForSuccess knows to stop waiting.
                s.keyStates.put(k, new StartedState(c));
                s.notifyAll();
            }
            return sync;
        }

        @Override public void stop(Throwable cause) throws Exception {
            State s = State.get();
            synchronized (s) {
                s.keyStates.remove(k);
            }
            LOGGER.log(Level.INFO, cause, () -> "Stopping " + k);
            super.stop(cause);
        }

        @Override public String getStatus() {
            State s = State.get();
            synchronized (s) {
                return s.keyStates.get(k) == null ? "waiting on " + k : "finished " + k ;
            }
        }

        private static final long serialVersionUID = 1L;

    }

    @Extension public static final class DescriptorImpl extends StepDescriptor {

        @Override public String getFunctionName() {
            return "semaphore";
        }

        @Override public String getDisplayName() {
            return "Test step";
        }

        @Override public Set<? extends Class<?>> getRequiredContext() {
            return Set.of();
        }

    }

    // Marker interface just for clarity.
    private interface KeyState { }

    /**
     * The step has not yet started, but will succeed immediately when it does start.
     */
    private static class ImmediateSuccessState implements KeyState {
        private final Object returnValue;
        private ImmediateSuccessState(Object returnValue) {
            this.returnValue = returnValue;
        }
    }

    /**
     * The step has not yet started, and will fail immediately when it does start.
     */
    private static class ImmediateFailureState implements KeyState {
        private final Throwable error;
        private ImmediateFailureState(Throwable error) {
            this.error = error;
        }
    }

    /**
     * The step has at least started, and may have finished.
     */
    private static class StartedState implements KeyState {
        private final String context;
        private StartedState(String context) {
            this.context = context;
        }
    }

    private static final long serialVersionUID = 1L;
}
