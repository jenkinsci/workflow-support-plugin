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

import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.core.JVM;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import hudson.Util;
import hudson.model.Action;
import hudson.util.IOUtils;
import hudson.util.RobustReflectionConverter;
import hudson.util.XStream2;
import org.jenkinsci.plugins.workflow.actions.FlowNodeAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
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
 * For these reasons, this implementation should only be used for {@link FlowDurabilityHint} values of
 *  {@link FlowDurabilityHint#NO_PROMISES} or {@link FlowDurabilityHint#SURVIVE_CLEAN_RESTART}.
 */
public class LumpFlowNodeStorage extends FlowNodeStorage {
    private final File dir;

    private final FlowExecution exec;

    /** Lazy-loaded mapping. */
    private transient HashMap<String, Tag> nodes = null;

    File getStoreFile() throws IOException {
        return new File(dir, "flowNodeStore.xml");
    }

    public LumpFlowNodeStorage(FlowExecution exec, File dir) {
        this.exec = exec;
        this.dir = dir;
        this.nodes = null;
    }

    /** Loads the nodes listing, lazily - so loading the {@link FlowExecution} doesn't trigger a more complex load. */
    HashMap<String, Tag> getOrLoadNodes() throws IOException {
        if (nodes == null) {
            if (dir.exists()) {
                File storeFile = getStoreFile();
                if (storeFile.exists()) {
                    HashMap<String, Tag> roughNodes = (HashMap<String, Tag>) (XSTREAM.fromXML(getStoreFile()));
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
        if (!delayWritingActions) {
            flush();
        }
    }

    @Override
    public void storeNode(FlowNode n) throws IOException {
        storeNode(n, false);
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
        if (nodes != null) {
            // TODO reuse a single buffer if we can, and consider using async FileChannel operations.
            if (!dir.exists()) {
                IOUtils.mkdirs(dir);
            }
            OutputStream os = new BufferedOutputStream(
                    Files.newOutputStream(getStoreFile().toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            );
            XSTREAM.toXMLUTF8(nodes, os); // Hah, no atomic nonsense, just write and write and write!
            os.close();
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
        XSTREAM.registerConverter(new Converter() {
            private final RobustReflectionConverter ref = new RobustReflectionConverter(XSTREAM.getMapper(), JVM.newReflectionProvider());

            @Override public boolean canConvert(Class type) {
                return Tag.class.isAssignableFrom(type);
            }

            @Override public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
                ref.marshal(source, writer, context);
            }

            @Override public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
                try {
                    Tag n = (Tag) ref.unmarshal(reader, context);
                    return n;
                } catch (RuntimeException x) {
                    x.printStackTrace();
                    throw x;
                }
            }
        });

        try {
            // TODO ugly, but we do not want public getters and setters for internal state.
            // Really FlowNode ought to have been an interface and the concrete implementations defined here, by the storage.
            FlowNode$exec = FlowNode.class.getDeclaredField("exec");
            FlowNode$exec.setAccessible(true);
            FlowNode_setActions = FlowNode.class.getDeclaredMethod("setActions", List.class);
            FlowNode_setActions.setAccessible(true);
        } catch (NoSuchFieldException|NoSuchMethodException e) {
            throw new Error(e);
        }
    }
}
