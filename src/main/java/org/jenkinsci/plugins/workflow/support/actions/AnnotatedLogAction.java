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
import hudson.util.ByteArrayOutputStream2;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
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
import org.jenkinsci.plugins.workflow.actions.PersistentAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.Beta;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.framework.io.ByteBuffer;

/**
 * A marker for a node which had some log text.
 */
@Restricted(Beta.class)
public class AnnotatedLogAction extends LogAction implements FlowNodeAction, PersistentAction {

    private static final Logger LOGGER = Logger.getLogger(AnnotatedLogAction.class.getName());

    /**
     * Division between a {@link FlowNode#getId} and a regular log line.
     * Could use anything, but nicer to use something visually distinct from typical output,
     * and unlikely to be produced by non-step output such as SCM loading.
     */
    public static final String NODE_ID_SEP = "¦";

    private static final byte[] INFIX = NODE_ID_SEP.getBytes(StandardCharsets.UTF_8);

    @Restricted(NoExternalUse.class) // Jelly
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

    private static byte[] prefix(FlowNode node) {
        return prefix(node.getId());
    }

    private static byte[] prefix(String id) {
        return (id + NODE_ID_SEP).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Scans {@link FlowExecutionOwner#getLog} for lines annotated as belonging to this node.
     * <p>{@inheritDoc}
     */
    @Override public AnnotatedLargeText<? extends FlowNode> getLogText() {
        ByteBuffer buf = new ByteBuffer();
        try (InputStream whole = node.getExecution().getOwner().getLog(0); InputStream wholeBuffered = new BufferedInputStream(whole)) {
            ByteArrayOutputStream2 baos = new ByteArrayOutputStream2();
            byte[] prefix = prefix(node);
            READ: while (true) {
                int c = wholeBuffered.read();
                if (c == -1) {
                    break;
                }
                baos.write(c);
                if (c == '\n') {
                    byte[] linebuf = baos.getBuffer();
                    int len = baos.size();
                    if (len >= prefix.length) {
                        boolean matches = true;
                        for (int i = 0; i < prefix.length; i++) {
                            if (linebuf[i] != prefix[i]) {
                                matches = false;
                                break;
                            }
                        }
                        if (matches) {
                            // This line in fact belongs to our node, so copy it out.
                            buf.write(linebuf, prefix.length, len - prefix.length);
                        }
                    }
                    baos.reset();
                }
            }
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
        }
        return new AnnotatedLargeText<>(buf, StandardCharsets.UTF_8, !node.isActive(), node);
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

    /** For testing. */
    static @Nonnull TaskListener decorate(@Nonnull TaskListener raw, @Nonnull String id) {
        return new DecoratedTaskListener(raw, null, prefix(id));
    }

    private static class DecoratedTaskListener implements TaskListener {
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
                final OutputStream initial = new BufferedOutputStream(delegate.getLogger());
                // We apply the prefix and any filter in this order, since the filter should not be able to mangle or hide node prefixes.
                OutputStream decorated = new LineTransformationOutputStream() {
                    @Override protected void eol(byte[] b, int len) throws IOException {
                        synchronized (initial) { // to match .println etc.
                            initial.write(prefix);
                            initial.write(b, 0, len);
                            initial.flush();
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
        ByteArrayOutputStream2 baos = new ByteArrayOutputStream2();
        READ:
        while (true) {
            int c = buffered.read();
            if (c == -1) {
                break;
            }
            baos.write(c);
            if (c == '\n') {
                byte[] buf = baos.getBuffer();
                int len = baos.size();
                int idx = Bytes.indexOf(buf, INFIX);
                int restLen = len - idx - INFIX.length;
                if (idx == -1 || /* would be nice if indexOf took a bound */ restLen < 0) {
                    stripped.write(buf, 0, len);
                } else {
                    stripped.write(buf, idx + INFIX.length, restLen);
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
        private String currentId;
        NodeConsoleAnnotationOutputStream(Writer out, ConsoleAnnotator<? super T> ann, T context) {
            super(out, ann, context, StandardCharsets.UTF_8);
            this.out = out;
        }
        @Override protected void eol(byte[] in, int sz) throws IOException {
            if (sz < 0 || sz > in.length) {
                throw new IllegalArgumentException(sz + " vs. " + in.length);
            }
            String id = null;
            int idx = Bytes.indexOf(in, INFIX);
            int skip = idx + INFIX.length;
            if (idx != -1 && skip <= sz) {
                id = new String(in, 0, idx, StandardCharsets.UTF_8);
                if (!id.equals(currentId)) {
                    if (currentId != null) {
                        out.write("</span>");
                    }
                    out.write("<span class=\"pipeline-node-" + id + "\">");
                }
                in = Arrays.copyOfRange(in, skip, sz);
                sz -= skip;
                assert sz >= 0 && sz <= in.length;
            } else if (currentId != null) {
                out.write("</span>");
            }
            super.eol(in, sz);
            currentId = id;
        }
    }

}
