/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
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

package org.jenkinsci.plugins.workflow.support;

import hudson.EnvVars;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.console.ConsoleLogFilter;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.PasswordParameterValue;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.util.Secret;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.log.TaskListenerDecorator;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.EnvironmentAction;
import org.jenkinsci.plugins.workflow.support.actions.LogStorageAction;

/**
 * Partial implementation of step context.
 */
public abstract class DefaultStepContext extends StepContext {

    private static final Logger LOGGER = Logger.getLogger(DefaultStepContext.class.getName());

    /**
     * To prevent double instantiation of task listener, once we create it we keep it here.
     */
    private transient TaskListener listener;

    /**
     * Uses {@link #doGet} but automatically translates certain kinds of objects into others.
     * <p>{@inheritDoc}
     */
    @Override public final <T> T get(Class<T> key) throws IOException, InterruptedException {
        T value = doGet(key);
        if (key == EnvVars.class) {
            Run<?,?> run = get(Run.class);
            EnvironmentAction a = run == null ? null : run.getAction(EnvironmentAction.class);
            TaskListener taskListener = getListener(true);
            EnvVars customEnvironment = a != null ? a.getEnvironment() : run.getEnvironment(taskListener);
            return key.cast(EnvironmentExpander.getEffectiveEnvironment(customEnvironment, (EnvVars) value, get(EnvironmentExpander.class), this, taskListener));
        } else if (key == Launcher.class) {
            return key.cast(makeLauncher((Launcher) value));
        } else if (value != null) {
            return value;
        } else if (key == TaskListener.class) {
            return key.cast(getListener(false));
        } else if (key == EnvironmentExpander.class) {
            // Only called when `value == null` so that it does not override expanders contributed by steps.
            return key.cast(new PasswordParameterEnvironmentExpander(get(Run.class)));
        } else if (Node.class.isAssignableFrom(key)) {
            Computer c = get(Computer.class);
            Node n = null;
            if (c != null) {
                n = c.getNode();
            }
            /* contract is to quietly return null:
            if (n == null) {
                throw new IllegalStateException("There is no current node. Perhaps you forgot to call node?");
            }
            */
            return castOrNull(key, n);
        } else if (Run.class.isAssignableFrom(key)) {
            return castOrNull(key, getExecution().getOwner().getExecutable());
        } else if (Job.class.isAssignableFrom(key)) {
            return castOrNull(key, get(Run.class).getParent());
        } else if (FlowExecution.class.isAssignableFrom(key)) {
            return castOrNull(key,getExecution());
        } else if (FlowNode.class.isAssignableFrom(key)) {
            return castOrNull(key, getNode());
        } else {
            // unrecognized key
            return null;
        }
    }

    private synchronized TaskListener getListener(boolean allowInactive) throws IOException, InterruptedException {
        if (listener == null) {
            FlowNode node = getNode();
            if (!node.isActive()) {
                if (allowInactive) {
                    return getExecution().getOwner().getListener();
                } else {
                    throw new IOException("cannot start writing logs to a finished node " + node + " " + node .getDisplayFunctionName() + " in " + node.getExecution());
                }
            }
            listener = LogStorageAction.listenerFor(node, TaskListenerDecorator.merge(TaskListenerDecorator.fromConsoleLogFilter(get(ConsoleLogFilter.class)), get(TaskListenerDecorator.class)));
            LOGGER.log(Level.FINE, "opened log for {0}", node.getDisplayFunctionName());
            if (listener instanceof AutoCloseable) {
                node.getExecution().addListener(new GraphListener.Synchronous() {
                    @Override public void onNewHead(FlowNode newNode) {
                        if (!node.isActive()) {
                            node.getExecution().removeListener(this);
                            LOGGER.log(Level.FINE, "closing log for {0}", node.getDisplayFunctionName());
                            try {
                                ((AutoCloseable) listener).close();
                            } catch (Exception x) {
                                LOGGER.log(Level.WARNING, null, x);
                            }
                        }
                    }
                });
            }
        }
        return listener;
    }

    private <T> T castOrNull(Class<T> key, Object o) {
        if (key.isInstance(o)) {
            return key.cast(o);
        } else {
            return null;
        }
    }

    private @CheckForNull Launcher makeLauncher(@CheckForNull Launcher contextual) throws IOException, InterruptedException {
        Launcher launcher = contextual;
        Node n = get(Node.class);
        if (launcher == null) {
            if (n == null) {
                return null;
            }
            launcher = n.createLauncher(get(TaskListener.class));
        }
        LauncherDecorator decorator = get(LauncherDecorator.class);
        if (decorator != null && n != null) {
            launcher = decorator.decorate(launcher, n);
        }
        return launcher;
    }

    /**
     * The actual logic of {@link #get}, such as retrieving overrides passed to {@link #newBodyInvoker}.
     */
    protected abstract @CheckForNull <T> T doGet(Class<T> key) throws IOException, InterruptedException;

    /**
     * Finds the associated execution.
     * Automatically available from {@link #get}.
     */
    protected abstract @NonNull FlowExecution getExecution() throws IOException;

    /**
     * Finds the associated node.
     * Automatically available from {@link #get}.
     */
    protected abstract @NonNull FlowNode getNode() throws IOException;

    /**
     * Default implementation of {@link EnvironmentExpander} that recognizes password parameters as sensitive variables.
     */
    private static class PasswordParameterEnvironmentExpander extends EnvironmentExpander {
        private static final long serialVersionUID = 1L;

        private final Set<String> passwordParameterVariables;

        public PasswordParameterEnvironmentExpander(Run<?, ?> run) {
            ParametersAction action = run.getAction(ParametersAction.class);
            if (action != null) {
                passwordParameterVariables = action.getParameters().stream()
                        .filter(e -> e instanceof PasswordParameterValue
                                        && !((Secret) e.getValue()).getPlainText().isEmpty())
                        .map(ParameterValue::getName)
                        .collect(Collectors.toCollection(HashSet::new)); // Make sure the set is serializable.
            } else {
                passwordParameterVariables = Collections.emptySet();
            }
        }

        @Override
        public void expand(EnvVars ev) {
            // Do nothing.
        }

        @Override
        public Set<String> getSensitiveVariables() {
            return passwordParameterVariables;
        }
    }

}
