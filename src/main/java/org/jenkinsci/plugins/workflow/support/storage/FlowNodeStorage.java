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

package org.jenkinsci.plugins.workflow.support.storage;

import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowActionStorage;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;

import java.io.IOException;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Abstraction of various ways to persist {@link FlowNode}, for those {@link FlowExecution}s
 * who wants to store them within Jenkins.
 *
 * A flow graph has a characteristic that it's additive.
 *
 * This is clearly a useful internal abstraction to decouple {@link FlowDefinition} implementation
 * from storage mechanism, but not sure if this should be exposed to users.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class FlowNodeStorage implements FlowActionStorage {
    /**
     * @return null
     *      If no node of the given ID has been persisted before.
     */
    public abstract @CheckForNull FlowNode getNode(String id) throws IOException;

    /** Registers node in this storage, potentially persisting to disk.
     *  {@link #flushNode(FlowNode)} will guarantee it is persisted.
     */
    public abstract void storeNode(@Nonnull FlowNode n) throws IOException;

    /**
     * Register the given node to the storage, potentially flushing to disk,
     *  and optionally marking the node as deferring writes.
     * <p> This should be invoked with delayWritingAction=true generally.
     *
     *  Generally {@link #autopersist(FlowNode)} should be automatically invoked before Step execution begins
     *  unless the step implements {@link StepDescriptor#delayWritingFlownodeActions()}.
     *
     * @param n Node to store
     * @param delayWritingActions If true, node will avoid persisting actions except on explicit flush or when you call
     *                            {@link #autopersist(FlowNode)}.
     * @throws IOException
     */
    public void storeNode(@Nonnull FlowNode n, boolean delayWritingActions) throws IOException {
        storeNode(n); // Default impl, override if you support delaying writes
    }

    /** Marks node as needing to flush with EVERY write to the {@link FlowNode#actions} from now on, and invoke {@link #flushNode(FlowNode)}
     *  if we're waiting to write anything.
     */
    public void autopersist(@Nonnull FlowNode n) throws IOException {
        flushNode(n);
    }

    /** Persists node fully to disk, ensuring it is written out to storage. */
    public void flushNode(@Nonnull FlowNode n) throws IOException {
        // Only needs implementation if you're not guaranteeing persistence at all times
    }

    /** Invoke this to insure any unwritten {@link FlowNode} data is persisted to disk.
     *  Should be invoked by {@link FlowExecution#notifyShutdown()} to ensure disk state is persisted.
     */
    public void flush() throws IOException {
        // Only needs implementation if you're not already guaranteeing persistence at all times
    }

    /** Used in testing durability, so we can be left with just what's on disk. */
    void reset() {

    }
}
