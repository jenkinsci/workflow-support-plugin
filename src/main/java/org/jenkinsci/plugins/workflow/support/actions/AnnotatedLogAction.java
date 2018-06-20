/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.actions;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.PersistentAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.log.LogStorage;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A marker for a node which had some log text.
 */
@Restricted(NoExternalUse.class) // for use from DefaultStepContext only
public class AnnotatedLogAction extends LogAction implements FlowNodeAction, PersistentAction {

    private static final Logger LOGGER = Logger.getLogger(AnnotatedLogAction.class.getName());

    public transient FlowNode node;

    private AnnotatedLogAction(FlowNode node) {
        if (!node.isActive()) {
            throw new IllegalStateException("cannot start writing logs to a finished node " + node);
        }
        this.node = node;
    }

    @Override public void onLoad(FlowNode node) {
        this.node = node;
    }

    @Override public AnnotatedLargeText<? extends FlowNode> getLogText() {
        FlowExecutionOwner owner = node.getExecution().getOwner();
        return LogStorage.of(owner).stepLog(node, !node.isActive());
    }

    /**
     * Used from <tt>console.jelly</tt> to write annotated log to the given output.
     */
    @Restricted(DoNotUse.class) // Jelly
    public void writeLogTo(long offset, XMLOutput out) throws IOException {
        getLogText().writeHtmlTo(offset, out.asWriter());
    }

    /**
     * Creates a sink to print output from a step.
     * Will use {@link LogActionImpl} if necessary.
     * @param node a node which wishes to print output
     * @param filter an optional log filter (must be {@link Serializable})
     * @return a stream
     */
    @SuppressWarnings("deprecation") // LogActionImpl here for backward compatibility
    public static @Nonnull TaskListener listenerFor(@Nonnull FlowNode node, @CheckForNull ConsoleLogFilter filter) throws IOException, InterruptedException {
        FlowExecutionOwner owner = node.getExecution().getOwner();
        if (LogActionImpl.isOld(owner)) {
            return LogActionImpl.stream(node, filter);
        } else {
            if (node.getAction(AnnotatedLogAction.class) == null) {
                node.addAction(new AnnotatedLogAction(node));
            }
            TaskListener listener = LogStorage.of(owner).nodeListener(node);
            return filter != null ? new FilteringTaskListener(listener, filter) : listener;
        }
    }

    private static class FilteringTaskListener implements TaskListener {
        private static final long serialVersionUID = 1;
        /**
         * The listener we are delegating to, which was expected to be remotable.
         * Note that we ignore all of its methods other than {@link TaskListener#getLogger}.
         */
        private final @Nonnull TaskListener delegate;
        /**
         * A filter.
         * Note that null is passed for the {@code build} parameter, since that would not be available on an agent.
         */
        @SuppressFBWarnings(value="SE_BAD_FIELD", justification="The filter is expected to be serializable.")
        private final @Nonnull ConsoleLogFilter filter;
        private transient PrintStream logger;
        FilteringTaskListener(TaskListener delegate, ConsoleLogFilter filter) {
            if (filter != null && !(filter instanceof Serializable)) {
                throw new IllegalArgumentException("Cannot pass a nonserializable " + filter.getClass());
            }
            this.delegate = delegate;
            this.filter = filter;
        }
        @SuppressWarnings("deprecation")
        @Override public PrintStream getLogger() {
            if (logger == null) {
                OutputStream base = delegate.getLogger();
                try {
                    // TODO the compatibility code in ConsoleLogFilter fails to delegate to the old overload when given a null argument
                    if (Util.isOverridden(ConsoleLogFilter.class, filter.getClass(), "decorateLogger", Run.class, OutputStream.class)) {
                        base = filter.decorateLogger((Run) null, base);
                    } else {
                        base = filter.decorateLogger((AbstractBuild) null, base);
                    }
                } catch (Exception x) {
                    LOGGER.log(Level.WARNING, null, x);
                }
                try {
                    logger = new PrintStream(base, false, "UTF-8");
                } catch (UnsupportedEncodingException x) {
                    throw new AssertionError(x);
                }
            }
            return logger;
        }
    }

}