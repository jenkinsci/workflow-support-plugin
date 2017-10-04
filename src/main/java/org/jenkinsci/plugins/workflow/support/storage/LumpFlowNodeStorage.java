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

import com.google.common.cache.CacheBuilder;
import com.thoughtworks.xstream.converters.Converter;
import com.thoughtworks.xstream.converters.MarshallingContext;
import com.thoughtworks.xstream.converters.UnmarshallingContext;
import com.thoughtworks.xstream.core.JVM;
import com.thoughtworks.xstream.io.HierarchicalStreamReader;
import com.thoughtworks.xstream.io.HierarchicalStreamWriter;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.Action;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link FlowNodeStorage} that stores all the nodes in one megafile
 * But defers persisting it until needed.
 *
 * This implementation should only be used for {@link FlowDurabilityHint} values of
 *  {@link FlowDurabilityHint#NO_PROMISES} or {@link FlowDurabilityHint#SURVIVE_CLEAN_RESTART}.
 *
 * TODO handle the changes in how Tag works vs. {@link SimpleXStreamFlowNodeStorage}
 * I.E. we always persist node and tag together.
 */
public class LumpFlowNodeStorage extends FlowNodeStorage {
    private final File dir;
    private final File storeFile;

    private final FlowExecution exec;

    private transient HashMap<String, Tag> nodes = null;


    public LumpFlowNodeStorage(FlowExecution exec, File dir) {
        this.exec = exec;
        this.dir = dir;
        this.storeFile = new File(dir, "flowNodeStore.xml");
        this.nodes = new HashMap<String, Tag>();
    }


    HashMap<String, Tag> getNodes() throws IOException {
        if (nodes == null) {
            // Unsafe and dirty but ought to work mostly
            return (HashMap<String, Tag>)(XSTREAM.fromXML(storeFile));
        }
        return nodes;
    }


    @Override
    @CheckForNull
    public FlowNode getNode(@Nonnull String id) throws IOException {
        Tag t = getNodes().get(id);
        return (t != null) ? t.node : null;
    }

    public void storeNode(@Nonnull FlowNode n, boolean delayWritingActions) throws IOException {
        Tag t = getNodes().get(n.getId());
        if (t != null) {
            t.node = n;
            t.actions = (Action[])(n.getActions().toArray());
        } else {
            getNodes().put(n.getId(), new Tag(n, n.getActions()));
        }
        if (!delayWritingActions) {
            flush();
        }
    }

    @Override
    public void storeNode(FlowNode n) throws IOException {
        storeNode(n, false);
    }

    public void autopersist(@Nonnull FlowNode n) throws IOException {
        // TODO determine if I actually DO anything???? How to obey this?
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
            OutputStream os = new BufferedOutputStream(
                    Files.newOutputStream(storeFile.toPath(), StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            );
            XSTREAM.toXMLUTF8(nodes, os); // Hah, no atomic nonsense, just write and write and write!
            os.close();
        }
    }

    public List<Action> loadActions(@Nonnull FlowNode node) throws IOException {
        Tag t = getNodes().get(node.getId());
        return (t != null) ? t.actions() : Collections.<Action>emptyList();
    }

    /**
     * Just stores this one node
     */
    public void saveActions(@Nonnull FlowNode node, @Nonnull List<Action> actions) throws IOException {
        Tag t = getNodes().get(node.getId());
        if (t != null) {
            t.node = node;
            t.actions = (Action[])(node.getActions().toArray());
        }
    }

    private Tag load(String id) throws IOException {
        XmlFile nodeFile = getNodeFile(id);
        Tag v = (Tag) nodeFile.read();
        if (v.node == null) {
            throw new IOException("failed to load flow node from " + nodeFile + ": " + nodeFile.asString());
        }
        try {
            FlowNode$exec.set(v.node, exec);
        } catch (IllegalAccessException e) {
            throw (IllegalAccessError) new IllegalAccessError("Failed to set owner").initCause(e);
        }
        v.storeActions();
        for (FlowNodeAction a : Util.filter(v.actions(), FlowNodeAction.class)) {
            a.onLoad(v.node);
        }
        return v;
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
    private static final Field FlowNode$parents;
    private static final Field FlowNode$parentIds;
    private static final Method FlowNode_setActions;

    static {
        XSTREAM.registerConverter(new Converter() {
            private final RobustReflectionConverter ref = new RobustReflectionConverter(XSTREAM.getMapper(), JVM.newReflectionProvider());
            // IdentityHashMap could leak memory. WeakHashMap compares by equals, which will fail with NPE in FlowNode.hashCode.
            private final Map<FlowNode,String> ids = CacheBuilder.newBuilder().weakKeys().<FlowNode,String>build().asMap();
            @Override public boolean canConvert(Class type) {
                return FlowNode.class.isAssignableFrom(type);
            }
            @Override public void marshal(Object source, HierarchicalStreamWriter writer, MarshallingContext context) {
                ref.marshal(source, writer, context);
            }
            @Override public Object unmarshal(HierarchicalStreamReader reader, UnmarshallingContext context) {
                try {
                    FlowNode n = (FlowNode) ref.unmarshal(reader, context);
                    ids.put(n, reader.getValue());
                    try {
                        @SuppressWarnings("unchecked") List<FlowNode> parents = (List<FlowNode>) FlowNode$parents.get(n);
                        if (parents != null) {
                            @SuppressWarnings("unchecked") List<String> parentIds = (List<String>) FlowNode$parentIds.get(n);
                            assert parentIds == null;
                            parentIds = new ArrayList<String>(parents.size());
                            for (FlowNode parent : parents) {
                                String id = ids.get(parent);
                                assert id != null;
                                parentIds.add(id);
                            }
                            FlowNode$parents.set(n, null);
                            FlowNode$parentIds.set(n, parentIds);
                        }
                    } catch (Exception x) {
                        assert false : x;
                    }
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
            FlowNode$parents = FlowNode.class.getDeclaredField("parents");
            FlowNode$parents.setAccessible(true);
            FlowNode$parentIds = FlowNode.class.getDeclaredField("parentIds");
            FlowNode$parentIds.setAccessible(true);
            FlowNode_setActions = FlowNode.class.getDeclaredMethod("setActions", List.class);
            FlowNode_setActions.setAccessible(true);
        } catch (NoSuchFieldException|NoSuchMethodException e) {
            throw new Error(e);
        }
    }

    @Override
    void reset() {
        this.nodes.clear();
    }
}
