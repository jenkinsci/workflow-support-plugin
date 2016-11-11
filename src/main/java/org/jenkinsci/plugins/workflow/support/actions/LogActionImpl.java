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

package org.jenkinsci.plugins.workflow.support.actions;

import com.google.common.base.Charsets;
import com.google.common.base.Predicates;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleLogFilter;
import hudson.model.AbstractBuild;
import hudson.model.TaskListener;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.LinearBlockHoppingScanner;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepContextParameter;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.framework.io.ByteBuffer;

/**
 * {@link LogAction} implementation that stores per-node log file under {@link FlowExecutionOwner#getRootDir()}.
 *
 * @author Kohsuke Kawaguchi
 */
public class LogActionImpl extends LogAction implements FlowNodeAction {

    private static final Logger LOGGER = Logger.getLogger(LogActionImpl.class.getName());

    /**
     * Get or create the streaming log handle for a given flow node.
     * @param node the node
     * @param filter
     * @return a listener
     */
    @SuppressFBWarnings("OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE") // stream closed later
    public static @Nonnull TaskListener stream(final @Nonnull FlowNode node, @CheckForNull ConsoleLogFilter filter) throws IOException, InterruptedException {
        LogActionImpl la = node.getAction(LogActionImpl.class);
        if (la == null) {
            // TODO: use UTF-8
            la = new LogActionImpl(node, Charset.defaultCharset());
            node.addAction(la);
        }
        OutputStream os = new FileOutputStream(la.getLogFile(), true);
        if (filter != null) {
            os = filter.decorateLogger((AbstractBuild) null, os);
        }
        final StreamTaskListener result = new StreamTaskListener(os);
        final AtomicReference<GraphListener> graphListener = new AtomicReference<>();
        LOGGER.log(Level.FINE, "opened log for {0}", node.getDisplayFunctionName());
        graphListener.set(new GraphListener.Synchronous() {
            @Override public void onNewHead(FlowNode newNode) {
                if (!isRunning(node)) {
                    node.getExecution().removeListener(graphListener.get());
                    result.getLogger().close();
                    LOGGER.log(Level.FINE, "closed log for {0}", node.getDisplayFunctionName());
                }
            }
        });
        node.getExecution().addListener(graphListener.get());
        return result;
    }

    private transient FlowNode parent;
    private transient volatile File log;
    private String charset;

    private LogActionImpl(FlowNode parent, Charset charset) {
        if (!isRunning(parent)) {
            throw new IllegalStateException("cannot start writing logs to a finished node " + parent);
        }
        this.parent = parent;
        this.charset = charset.name();
    }

    @Restricted(DoNotUse.class) // Jelly
    public FlowNode getParent() {
        return parent;
    }

    @Override
    public AnnotatedLargeText<? extends FlowNode> getLogText() {
        try {
            getLogFile();
            if (!log.exists()) {
                return new AnnotatedLargeText<>(new ByteBuffer(), getCharset(), !isRunning(parent), parent);
            }
            return new AnnotatedLargeText<>(log, getCharset(), !isRunning(parent), parent);
        } catch (IOException e) {
            ByteBuffer buf = new ByteBuffer();
            PrintStream ps;
            try {
                ps = new PrintStream(buf, false, "UTF-8");
            } catch (UnsupportedEncodingException x) {
                throw new AssertionError(x);
            }
            ps.println("Failed to find log file for id="+parent.getId());
            e.printStackTrace(ps);
            ps.close();
            return new AnnotatedLargeText<FlowNode>(buf, Charsets.UTF_8, true, parent);
        }
    }

    /**
     * Unlike {@link FlowNode#isRunning}, handles {@link BlockStartNode}s.
     * Note that {@code WorkflowRun.copyLogs} will still currently refuse to consider steps
     * which fail to obtain a {@link TaskListener} prior to invoking their body
     * (for example by calling {@link StepContext#get} on demand rather than by using {@link StepContextParameter}).
     */
    private static boolean isRunning(FlowNode node) {
        if (node instanceof BlockStartNode) {
            for (FlowNode head : node.getExecution().getCurrentHeads()) {
                if (new LinearBlockHoppingScanner().findFirstMatch(head, Predicates.equalTo(node)) != null) {
                    return true;
                }
            }
            return false;
        } else {
            return node.isRunning();
        }
    }

    /**
     * The actual log file.
     */
    private File getLogFile() throws IOException {
        if (log==null)
            log = new File(parent.getExecution().getOwner().getRootDir(), parent.getId() + ".log");
        return log;
    }

    /**
     * Used from <tt>console.jelly</tt> to write annotated log to the given output.
     */
    @Restricted(DoNotUse.class) // Jelly
    public void writeLogTo(long offset, XMLOutput out) throws IOException {
        AnnotatedLargeText l = getLogText();
        if (l!=null)
            l.writeHtmlTo(offset, out.asWriter());
    }

    @Override
    public void onLoad(FlowNode parent) {
        this.parent = parent;
    }

    private Charset getCharset() {
        if(charset==null)   return Charset.defaultCharset();    // just being defensive
        return Charset.forName(charset);
    }

    @Override public String toString() {
        return "LogActionImpl[" + log + "]";
    }
}
