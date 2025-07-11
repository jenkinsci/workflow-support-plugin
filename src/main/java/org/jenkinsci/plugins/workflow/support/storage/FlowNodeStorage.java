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

import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowActionStorage;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import java.io.IOException;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Abstraction of various ways to persist {@link FlowNode}, for those {@link FlowExecution}s
 * who want to store them within Jenkins.
 *
 * A flow graph has a characteristic that it is additive.
 *
 * Flow nodes may be stored in memory or directly persisted to disk at any given moment, but invoking {@link #flush()}
 *  should always guarantee that everything currently in memory is written.
 * @author Kohsuke Kawaguchi
 * @author Sam Van Oort
 */
public abstract class FlowNodeStorage implements FlowActionStorage {
    // Set up as "avoid" because an unset field will default to false when deserializing and not explicitly set.
    private transient boolean avoidAtomicWrite = false;

    /** If true, we use non-atomic write of XML files for this storage. See {@link hudson.util.AtomicFileWriter}. */
    public boolean isAvoidAtomicWrite() {
        return avoidAtomicWrite;
    }

    /** Set whether we should avoid using atomic write for node files (ensures valid node data if write is interrupted) or not.
     */
    public void setAvoidAtomicWrite(boolean avoidAtomicWrite){
        this.avoidAtomicWrite = avoidAtomicWrite;
    }

    /**
     * @return null
     *      If no node of the given ID has been persisted before.
     */
    public abstract @CheckForNull FlowNode getNode(String id) throws IOException;

    /** Registers node in this storage, potentially persisting to disk.
     *  {@link #flushNode(FlowNode)} will guarantee it is persisted.
     */
    public abstract void storeNode(@NonNull FlowNode n) throws IOException;

    /**
     * Register the given node to the storage, potentially flushing to disk,
     *  and optionally marking the node as deferring writes.
     * <p> This should be invoked with delayWritingAction=true until you have a fully configured node to write out.
     *
     *  Generally {@link #autopersist(FlowNode)} should be automatically invoked before Step execution begins
     *   unless the step is block-scoped (in which case the FlowNode will handle this).
     *
     * @param n Node to store
     * @param delayWritingActions If true, node will avoid persisting actions except on explicit flush or when you call
     *                            {@link #autopersist(FlowNode)}.
     * @throws IOException
     */
    public void storeNode(@NonNull FlowNode n, boolean delayWritingActions) throws IOException {
        storeNode(n); // Default impl, override if you support delaying writes
    }

    /**
     * Flushes the node if needed, and if supported, marks it as needing to flush with EVERY write to the {@link FlowNode#actions}.
     */
    public void autopersist(@NonNull FlowNode n) throws IOException {
        flushNode(n);
    }

    /** Persists node fully to disk, ensuring it is written out to storage. */
    public void flushNode(@NonNull FlowNode n) throws IOException {
        // Only needs implementation if you're not guaranteeing persistence at all times
    }

    /** Invoke this to insure any unwritten {@link FlowNode} data is persisted to disk.
     *  Should be invoked to ensure disk state is persisted.
     */
    public void flush() throws IOException {
        // Only needs implementation if you're not already guaranteeing persistence at all times
    }

    /** Have we written everything to disk that we need to, or is there something waiting to be written by invoking {@link #flush()}? */
    public boolean isPersistedFully() {
        return true;
    }
}
