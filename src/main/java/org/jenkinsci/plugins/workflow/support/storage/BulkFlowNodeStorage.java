/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
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

import hudson.Util;
import hudson.model.Action;
import hudson.util.IOUtils;
import hudson.util.XStream2;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.support.PipelineIOUtils;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * {@link FlowNodeStorage} implementation that stores all the {@link FlowNode}s together in one file for efficient bulk I/O
 *
 * <p>This defers persisting until {@link #flush()} is called (or until we flush individual nodes explicitly or by
 *  storing them without specifying delayWritingActions=true. It also doesn't use the atomic write operations.
 *
 *  Performance characteristics: much better use of the filesystem and far more efficient read/write if you do it all at once.
 *  HOWEVER, if you insist on explicitly writing out each node, this reverts to overall O(n^2) performance, where n is node count.
 *
 * For these reasons, this implementation should <strong>only</strong> be used where {@link FlowDurabilityHint#isPersistWithEveryStep()}
 * is <strong>false</strong>.
 */
public class BulkFlowNodeStorage extends FlowNodeStorage {
    private final File dir;

    private final FlowExecution exec;

    /** Lazy-loaded mapping. */
    private transient HashMap<String, Tag> nodes = null;

    /** If true, we've been modified since last flush. */
    private boolean isModified = false;

    File getStoreFile() throws IOException {
        return new File(dir, "flowNodeStore.xml");
    }

    public BulkFlowNodeStorage(FlowExecution exec, File dir) {
        this.exec = exec;
        this.dir = dir;
        this.nodes = null;
        this.setAvoidAtomicWrite(true);
    }

    /** Loads the nodes listing, lazily - so loading the {@link FlowExecution} doesn't trigger a more complex load. */
    HashMap<String, Tag> getOrLoadNodes() throws IOException {
        if (nodes == null) {
            if (dir.exists()) {
                File storeFile = getStoreFile();
                if (storeFile.exists()) {
                    HashMap<String, Tag> roughNodes = null;
                    try {
                        roughNodes = (HashMap<String, Tag>) (XSTREAM.fromXML(getStoreFile()));
                    } catch (Exception ex) {
                       nodes = new HashMap<String, Tag>();
                       throw new IOException("Failed to read nodes", ex);
                    }
                    if (roughNodes == null) {
                        nodes = new HashMap<String, Tag>();
                        throw new IOException("Unable to load nodes, invalid data");
                    }
                    for (Tag t : roughNodes.values()) {
                        FlowNode fn = t.node;
                        try {
                            FlowNode$exec.set(fn, exec);
                        } catch (IllegalAccessException e) {
                            throw (IllegalAccessError) new IllegalAccessError("Failed to set owner").initCause(e);
                        }
                        t.storeActions();
                        for (FlowNodeAction a : Util.filter(t.actions(), FlowNodeAction.class)) {
                            a.onLoad(fn);
                        }
                    }
                    nodes = roughNodes;
                } else {
                    nodes = new HashMap<String, Tag>();
                }
            } else {
                IOUtils.mkdirs(dir);
                nodes = new HashMap<String, Tag>();
            }
        }
        return nodes;
    }

    @Override
    @CheckForNull
    public FlowNode getNode(@Nonnull String id) throws IOException {
        Tag t = getOrLoadNodes().get(id);
        return (t != null) ? t.node : null;
    }

    public void storeNode(@Nonnull FlowNode n, boolean delayWritingActions) throws IOException {
        Tag t = getOrLoadNodes().get(n.getId());
        if (t != null) {
            t.node = n;
            List<Action> act = n.getActions();
            t.actions = act.toArray(new Action[act.size()]);
        } else {
            getOrLoadNodes().put(n.getId(), new Tag(n, n.getActions()));
        }
        isModified = true;
        if (!delayWritingActions) {
            flush();
        }
    }

    @Override
    public void storeNode(FlowNode n) throws IOException {
        flushNode(n);
    }

    /**
     * Persists a single FlowNode to disk (if not already persisted).
     * @param n Node to persist
     * @throws IOException
     */
    @Override
    public void flushNode(@Nonnull FlowNode n) throws IOException {
        storeNode(n, false);
    }

    /** Force persisting any nodes that had writing deferred */
    @Override
    public void flush() throws IOException {
        if (nodes != null && isModified) {
            if (!dir.exists()) {
                IOUtils.mkdirs(dir);
            }
            PipelineIOUtils.writeByXStream(nodes, getStoreFile(), XSTREAM, !this.isAvoidAtomicWrite());

            isModified = false;
        }
    }

    public List<Action> loadActions(@Nonnull FlowNode node) throws IOException {
        Tag t = getOrLoadNodes().get(node.getId());
        return (t != null) ? t.actions() : Collections.<Action>emptyList();
    }

    /**
     * Just stores this one node
     */
    public void saveActions(@Nonnull FlowNode node, @Nonnull List<Action> actions) throws IOException {
        HashMap<String, Tag> map = getOrLoadNodes();
        Tag t = map.get(node.getId());
        if (t != null) {
            t.node = node;
            List<Action> act = node.getActions();
            t.actions = act.toArray(new Action[act.size()]);
        } else {
            map.put(node.getId(), new Tag(node, actions));
        }
        isModified = true;
    }

    /** Have we written everything to disk that we need to, or is there something waiting to be written */
    public boolean isPersistedFully() {
        return !isModified;
    }


    /**
     * To group node and their actions together into one object.
     */
    private static class Tag {
        /* @Nonnull except perhaps after deserialization */ FlowNode node;
        private @CheckForNull Action[] actions;

        private Tag(@Nonnull FlowNode node, @Nonnull List<Action> actions) {
            this.node = node;
            this.actions = actions.isEmpty() ? null : actions.toArray(new Action[actions.size()]);
        }

        private void storeActions() {  // We've already loaded the actions, may as well store them to the FlowNode
            try {
                FlowNode_setActions.invoke(this.node, actions());
            } catch (InvocationTargetException|IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
        }

        public @Nonnull List<Action> actions() {
            return actions != null ? Arrays.asList(actions) : Collections.<Action>emptyList();
        }
    }

    public static final XStream2 XSTREAM = new XStream2();

    private static final Field FlowNode$exec;
    private static final Method FlowNode_setActions;

    static {
        // Aliases reduce the amount of data persisted to disk
        XSTREAM.alias("Tag", Tag.class);
        // Maybe alias for UninstantiatedDescribable too, if we add a structs dependency
        XSTREAM.aliasPackage("cps.n", "org.jenkinsci.plugins.workflow.cps.nodes");
        XSTREAM.aliasPackage("wf.a", "org.jenkinsci.plugins.workflow.actions");
        XSTREAM.aliasPackage("s.a", "org.jenkinsci.plugins.workflow.support.actions");
        XSTREAM.aliasPackage("cps.a", "org.jenkinsci.plugins.workflow.cps.actions");
        try {
            // Ugly, but we do not want public getters and setters for internal state on FlowNodes.
            FlowNode$exec = FlowNode.class.getDeclaredField("exec");
            FlowNode$exec.setAccessible(true);
            FlowNode_setActions = FlowNode.class.getDeclaredMethod("setActions", List.class);
            FlowNode_setActions.setAccessible(true);
        } catch (NoSuchFieldException|NoSuchMethodException e) {
            throw new Error(e);
        }
    }
}
