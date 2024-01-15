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
import hudson.console.AnnotatedLargeText;
import hudson.model.TaskListener;
import java.io.IOException;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.PersistentAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.log.LogStorage;
import org.jenkinsci.plugins.workflow.log.TaskListenerDecorator;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * A marker for a node which had some log text using {@link LogStorage#nodeListener}.
 */
@Restricted(NoExternalUse.class) // for use from DefaultStepContext only
public class LogStorageAction extends LogAction implements FlowNodeAction, PersistentAction {

    @SuppressFBWarnings(value="PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification="Retain API compatibility.")
    public transient FlowNode node;

    private LogStorageAction(FlowNode node) {
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
     * Used from <code>console.jelly</code> to write annotated log to the given output.
     */
    @Restricted(DoNotUse.class) // Jelly
    public void writeLogTo(long offset, XMLOutput out) throws IOException {
        // Similar to Run#writeWholeLogTo but terminates even if node.isActive(). Adapated from WorkflowRun.writeLogTo.
        long pos = offset;
        while (true) {
            long pos2 = getLogText().writeHtmlTo(pos, out.asWriter());
            if (pos2 <= pos) {
                break;
            }
            pos = pos2;
        }
    }

    /**
     * Creates a sink to print output from a step.
     * Will use {@link LogActionImpl} if necessary.
     * @param node a node which wishes to print output
     * @param decorator an optional decorator to pass to {@link TaskListenerDecorator#apply}
     * @return a stream
     */
    @SuppressWarnings("deprecation") // LogActionImpl here for backward compatibility
    public static @NonNull TaskListener listenerFor(@NonNull FlowNode node, @CheckForNull TaskListenerDecorator decorator) throws IOException, InterruptedException {
        FlowExecutionOwner owner = node.getExecution().getOwner();
        if (LogActionImpl.isOld(owner) || node.getAction(LogActionImpl.class) != null) {
            return LogActionImpl.stream(node, decorator);
        } else {
            if (node.getAction(LogStorageAction.class) == null) {
                node.addAction(new LogStorageAction(node));
            }
            return TaskListenerDecorator.apply(LogStorage.of(owner).nodeListener(node), owner, decorator);
        }
    }

}
