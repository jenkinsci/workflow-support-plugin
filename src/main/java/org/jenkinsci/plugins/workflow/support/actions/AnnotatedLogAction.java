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

import com.google.common.base.Predicates;
import com.google.common.primitives.Bytes;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleAnnotationOutputStream;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleLogFilter;
import hudson.console.LineTransformationOutputStream;
import hudson.model.AbstractBuild;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.LinearBlockHoppingScanner;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.framework.io.ByteBuffer;

/**
 * A marker for a node which had some log text.
 */
public class AnnotatedLogAction extends LogAction implements FlowNodeAction {

    private static final Logger LOGGER = Logger.getLogger(AnnotatedLogAction.class.getName());

    /** Could use anything, but nicer to use something visually distinct from typical output, and unlikely to be produced by non-step output such as SCM loading. */
    @Restricted(NoExternalUse.class) // tests only
    public static final String NODE_ID_SEP = "¦";

    private static final byte[] INFIX = NODE_ID_SEP.getBytes(StandardCharsets.UTF_8);

    @Restricted(NoExternalUse.class) // Jelly
    public transient FlowNode node;

    private AnnotatedLogAction(FlowNode node) {
        if (!isRunning(node)) {
            throw new IllegalStateException("cannot start writing logs to a finished node " + node);
        }
        this.node = node;
    }

    @Override public void onLoad(FlowNode node) {
        this.node = node;
    }

    private static byte[] prefix(FlowNode node) {
        return (node.getId() + NODE_ID_SEP).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Scans {@link FlowExecutionOwner#getLog} for lines annotated as belonging to this node.
     * <p>{@inheritDoc}
     */
    @Override public AnnotatedLargeText<? extends FlowNode> getLogText() {
        ByteBuffer buf = new ByteBuffer();
        try (InputStream whole = node.getExecution().getOwner().getLog(0); InputStream wholeBuffered = new BufferedInputStream(whole)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] prefix = prefix(node);
            READ: while (true) {
                int c = wholeBuffered.read();
                if (c == -1) {
                    break;
                }
                baos.write(c);
                if (c == '\n') {
                    byte[] line = baos.toByteArray(); // TODO find a more efficient way to do this; ByteBufferInput?
                    if (line.length >= prefix.length) {
                        boolean matches = true;
                        for (int i = 0; i < prefix.length; i++) {
                            if (line[i] != prefix[i]) {
                                matches = false;
                                break;
                            }
                        }
                        if (matches) {
                            // This line in fact belongs to our node, so copy it out.
                            buf.write(line, prefix.length, line.length - prefix.length);
                        }
                    }
                    baos.reset();
                }
            }
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
        }
        return new AnnotatedLargeText<>(buf, StandardCharsets.UTF_8, !isRunning(node), node);
    }

    // TODO probably need an API method in LogAction to obtain all log text from a block node and descendants (e.g., a parallel branch)

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
    @Restricted(NoExternalUse.class) // for use from DefaultStepContext only
    public static @Nonnull TaskListener listenerFor(@Nonnull FlowNode node, @CheckForNull ConsoleLogFilter filter) throws IOException, InterruptedException {
        FlowExecutionOwner owner = node.getExecution().getOwner();
        if (Util.isOverridden(FlowExecutionOwner.class, owner.getClass(), "getLog", long.class)) {
            return decorate(owner.getListener(), filter, node);
        } else { // old WorkflowRun which uses copyLogs
            return LogActionImpl.stream(node, filter);
        }
    }

    /**
     * Wraps a raw log sink so that each line printed will be annotated as having come from the specified node.
     * The result is remotable to the extent that the input was.
     */
    private static @Nonnull TaskListener decorate(@Nonnull TaskListener raw, @CheckForNull ConsoleLogFilter filter, @Nonnull FlowNode node) {
        if (node.getAction(AnnotatedLogAction.class) == null) {
            node.addAction(new AnnotatedLogAction(node));
        }
        byte[] prefix = prefix(node);
        return new DecoratedTaskListener(raw, filter, prefix);
    }

    /**
     * Unlike {@link FlowNode#isRunning}, handles {@link BlockStartNode}s.
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

    private static class DecoratedTaskListener extends LessAbstractTaskListener {
        private static final long serialVersionUID = 1;
        /**
         * The listener we are delegating to, which was expected to be remotable.
         * Note that we ignore all of its methods other than {@link TaskListener#getLogger}.
         */
        private final @Nonnull TaskListener delegate;
        /**
         * An optional filter.
         * Note that null is passed for the {@code build} parameter, since that would not be available on an agent.
         */
        @SuppressFBWarnings(value="SE_BAD_FIELD", justification="The filter is expected to be serializable.")
        private final @CheckForNull ConsoleLogFilter filter;
        private final @Nonnull byte[] prefix;
        private transient PrintStream logger;
        DecoratedTaskListener(TaskListener delegate, ConsoleLogFilter filter, byte[] prefix) {
            if (filter != null && !(filter instanceof Serializable)) {
                throw new IllegalArgumentException("Cannot pass a nonserializable " + filter.getClass());
            }
            this.delegate = delegate;
            this.filter = filter;
            this.prefix = prefix;
        }
        @SuppressWarnings("deprecation")
        @Override public PrintStream getLogger() {
            if (logger == null) {
                final PrintStream initial = delegate.getLogger();
                // We apply the prefix and any filter in this order, since the filter should not be able to mangle or hide node prefixes.
                OutputStream decorated = new LineTransformationOutputStream() {
                    @Override protected void eol(byte[] b, int len) throws IOException {
                        synchronized (initial) { // to match .println etc.
                            initial.write(prefix);
                            initial.write(b, 0, len);
                        }
                    }
                };
                if (filter != null) {
                    try {
                        // TODO the compatibility code in ConsoleLogFilter fails to delegate to the old overload when given a null argument
                        if (Util.isOverridden(ConsoleLogFilter.class, filter.getClass(), "decorateLogger", Run.class, OutputStream.class)) {
                            decorated = filter.decorateLogger((Run) null, decorated);
                        } else {
                            decorated = filter.decorateLogger((AbstractBuild) null, decorated);
                        }
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, null, x);
                    }
                }
                try {
                    logger = new PrintStream(decorated, false, "UTF-8");
                } catch (UnsupportedEncodingException x) {
                    throw new AssertionError(x);
                }
            }
            return logger;
        }
    }

    /**
     * Copies a “raw” log decorated with node annotations to a sink with no such annotations.
     */
    public static void strip(InputStream decorated, OutputStream stripped) throws IOException {
        InputStream buffered = new BufferedInputStream(decorated);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        READ:
        while (true) {
            int c = buffered.read();
            if (c == -1) {
                break;
            }
            baos.write(c);
            if (c == '\n') {
                byte[] line = baos.toByteArray(); // TODO as above
                int idx = Bytes.indexOf(line, INFIX);
                if (idx == -1) {
                    stripped.write(line);
                } else {
                    stripped.write(line, idx + INFIX.length, line.length - idx - INFIX.length);
                }
                baos.reset();
            }
        }
    }

    /**
     * Decorates an HTML stream with output coming from nodes wrapped in a {@code pipeline-node-<ID>} CSS class based on {@link FlowNode#getId}.
     */
    public static <T> ConsoleAnnotationOutputStream<T> annotateHtml(Writer out, ConsoleAnnotator<? super T> ann, T context) {
        return new NodeConsoleAnnotationOutputStream<>(out, ann, context);
    }
    private static class NodeConsoleAnnotationOutputStream<T> extends ConsoleAnnotationOutputStream<T> {
        private final Writer out;
        NodeConsoleAnnotationOutputStream(Writer out, ConsoleAnnotator<? super T> ann, T context) {
            super(out, ann, context, StandardCharsets.UTF_8);
            this.out = out;
        }
        @Override protected void eol(byte[] in, int sz) throws IOException {
            assert sz >= 0 && sz <= in.length;
            String id = null;
            int idx = Bytes.indexOf(in, INFIX);
            if (idx != -1) {
                id = new String(in, 0, idx, StandardCharsets.UTF_8);
                out.write("<span class=\"pipeline-node-" + id + "\">");
                int skip = idx + INFIX.length;
                in = Arrays.copyOfRange(in, skip, sz);
                sz -= skip;
                assert sz >= 0 && sz <= in.length;
            }
            /* Produces more natural-looking output (MarkupText excludes NL) but is makes it impossible to specify display: none to hide a line (there will still be vertical whitespace):
            int eol = sz;
            while (eol > 0) {
                byte c = in[eol - 1];
                if (c == '\n' || c == '\r') {
                    eol--;
                } else {
                    break;
                }
            }
            super.eol(in, eol);
            if (id != null) {
                out.write("</span>");
            }
            for (int i = eol; i < sz; i++) {
                out.write(in[i]);
            }
             */
            super.eol(in, sz);
            if (id != null) {
                out.write("</span>");
            }
            // TODO try to coalesce content, so that <span class="pipeline-node-123">+ echo hello\n</span><span class="pipeline-node-123">hello\n</span> becomes <span class="pipeline-node-123">+ echo hello\nhello\n</span>
        }
    }

}
