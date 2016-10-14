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

import com.google.common.annotations.Beta;
import hudson.console.ConsoleNote;
import hudson.console.HudsonExceptionNote;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.model.StreamBuildListener;
import hudson.remoting.RemoteOutputStream;
import hudson.util.AbstractTaskListener;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Replacement for {@link StreamBuildListener} that delegates all methods to {@link #getLogger} rather than a private field.
 * This allows it to be sanely subclassed, for example to delegate to another listener, or to use a serial form that does not involve {@link RemoteOutputStream}.
 */
@Beta // would be best done in AbstractTaskListener
public abstract class LessAbstractTaskListener extends AbstractTaskListener {

    @SuppressWarnings("rawtypes")
    @Override public void annotate(ConsoleNote ann) throws IOException {
        ann.encodeTo(getLogger());
    }

    private PrintWriter _error(String prefix, String msg) {
        PrintStream out = getLogger();
        out.print(prefix);
        out.println(msg);
        try {
            annotate(new HudsonExceptionNote());
        } catch (IOException e) {
            // swallow
        }
        return new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
    }

    @Override public PrintWriter error(String msg) {
        return _error("ERROR: ", msg);
    }

    @Override public PrintWriter error(String format, Object... args) {
        return error(String.format(format, args));
    }

    @Override public PrintWriter fatalError(String msg) {
        return _error("FATAL: ", msg);
    }

    @Override public PrintWriter fatalError(String format, Object... args) {
        return fatalError(String.format(format, args));
    }

    /** Optional method in case a subclass wishes to implement {@link BuildListener}. */
    public void started(List<Cause> causes) {
        PrintStream l = getLogger();
        if (causes == null || causes.isEmpty()) {
            l.println("Started");
        } else {
            for (Cause cause : causes) {
                // TODO see comment in StreamBuildListener
                cause.print(this);
            }
        }
    }

    /** Optional method in case a subclass wishes to implement {@link BuildListener}. */
    public void finished(Result result) {
        getLogger().println("Finished: " + result);
    }

}
