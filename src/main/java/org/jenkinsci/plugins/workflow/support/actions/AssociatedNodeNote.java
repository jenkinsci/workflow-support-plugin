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

import hudson.MarkupText;
import hudson.console.ConsoleAnnotator;
import hudson.console.ConsoleNote;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

/**
 * Indicates what a line of text came from.
 */
class AssociatedNodeNote extends ConsoleNote<Object> {

    private static final long serialVersionUID = 1L;

    /** {@link FlowNode#getId} */
    final String id;

    AssociatedNodeNote(String id) {
        this.id = id;
    }

    @Override public ConsoleAnnotator<?> annotate(Object context, MarkupText text, int charPos) {
        // TODO could include some information useful for JavaScript tools operating on whole-build log, as in https://github.com/jenkinsci/workflow-job-plugin/pull/21
        // Possibly these could work via ConsoleAnnotatorFactory, though it seems there is no way currently to get the original ConsoleNote from a MarkupText.
        return null;
    }

}
