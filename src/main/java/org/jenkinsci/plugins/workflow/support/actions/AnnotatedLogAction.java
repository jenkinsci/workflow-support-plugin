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

import com.google.common.base.Charsets;
import hudson.console.AnnotatedLargeText;
import hudson.console.ConsoleNote;
import hudson.console.LineTransformationOutputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.framework.io.ByteBuffer;

/**
 * A marker for a node which had some log text.
 */
@Restricted(NoExternalUse.class)
public class AnnotatedLogAction extends LogAction implements FlowNodeAction {

    private static final Logger LOGGER = Logger.getLogger(AnnotatedLogAction.class.getName());

    public transient FlowNode node;

    private AnnotatedLogAction(FlowNode node) {
        this.node = node;
    }

    @Override public void onLoad(FlowNode node) {
        this.node = node;
    }

    @Override public AnnotatedLargeText<? extends FlowNode> getLogText() {
        ByteBuffer buf = new ByteBuffer();
        try (InputStream whole = node.getExecution().getOwner().getLog(); InputStream wholeBuffered = new BufferedInputStream(whole)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            while (true) {
                int c = wholeBuffered.read();
                if (c == -1) {
                    break;
                }
                baos.write(c);
                if (c == '\n') {
                    byte[] line = baos.toByteArray(); // TODO find a more efficient way to do this; ByteBufferInput?
                    // Cf. PlainTextConsoleOutputStream, ConsoleAnnotationOutputStream
                    int start = 0;
                    while (true) {
                        int next = ConsoleNote.findPreamble(line, start, line.length - start);
                        if (next == -1) {
                            break;
                        }
                        ByteArrayInputStream bais = new ByteArrayInputStream(line, next, line.length - next);
                        DataInputStream dis = new DataInputStream(bais);
                        ConsoleNote<?> note;
                        try {
                            note = ConsoleNote.readFrom(dis);
                        } catch (IOException | ClassNotFoundException x) {
                            // ignore load errors here, not our problem
                            note = null;
                        }
                        if (note instanceof AssociatedNodeNote && node.getId().equals(((AssociatedNodeNote) note).id)) {
                            // This line in fact belongs to our node, so copy it out.
                            buf.write(line, 0, line.length); // TODO should we strip out the AssociatedNodeNote?
                            break;
                        }
                        start = line.length - bais.available();
                    }
                    baos.reset();
                }
            }
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
        }
        return new AnnotatedLargeText<>(buf, Charsets.UTF_8, !node.isRunning(), node);
    }

    // TODO probably need an API method in LogAction to obtain all log text from a block node and descendants (e.g., a parallel branch)

    /**
     * Used from <tt>console.jelly</tt> to write annotated log to the given output.
     */
    @Restricted(DoNotUse.class) // Jelly
    public void writeLogTo(long offset, XMLOutput out) throws IOException {
        getLogText().writeHtmlTo(offset, out.asWriter());
    }

    public static OutputStream decorate(final OutputStream raw, FlowNode node) {
        if (node.getAction(AnnotatedLogAction.class) == null) {
            node.addAction(new AnnotatedLogAction(node));
        }
        final String id = node.getId();
        return new LineTransformationOutputStream() {
            @Override protected void eol(byte[] b, int len) throws IOException {
                synchronized (raw) { // when raw is a PrintStream, as from DefaultStepContext, println etc. also synchronize
                    new AssociatedNodeNote(id).encodeTo(raw);
                    raw.write(b, 0, len);
                }
            }
        };
    }

}
