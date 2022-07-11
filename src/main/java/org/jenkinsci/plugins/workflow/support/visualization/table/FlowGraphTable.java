package org.jenkinsci.plugins.workflow.support.visualization.table;

import edu.umd.cs.findbugs.annotations.Nullable;
import hudson.Util;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.function.Function;
import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.NotExecutedNodeAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.StepNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.LinearBlockHoppingScanner;
import org.jenkinsci.plugins.workflow.visualization.table.FlowNodeViewColumn;
import org.jenkinsci.plugins.workflow.visualization.table.FlowNodeViewColumnDescriptor;

/**
 * Data model behind the tree list view of a flow graph
 *
 * @author Kohsuke Kawaguchi
 */
public class FlowGraphTable {
    private final FlowExecution execution;
    /**
     * Point in time snapshot of all the active heads.
     */
    private List<FlowNode> heads;

    private List<Row> rows;
    private List<FlowNodeViewColumn> columns;

    public FlowGraphTable(@Nullable FlowExecution execution) {
        this.execution = execution;
    }

    public List<Row> getRows() {
        return rows;
    }

    public List<FlowNodeViewColumn> getColumns() {
        return columns;
    }

    /**
     * Builds the tabular view of a flow node graph.
     *
     * Leaving this outside the constructor to enable subtyping to tweak the behaviour.
     */
    public void build() {
        if (execution!=null) {
            Map<FlowNode, Row> rows = createAllRows();
            Row firstRow = buildForwardReferences(rows);
            buildTreeFromGraph(rows);
            buildTreeDepth(firstRow);
            this.rows = Collections.unmodifiableList(order(firstRow));
        } else {
            this.rows = Collections.emptyList();
        }
        this.columns = Collections.unmodifiableList(FlowNodeViewColumnDescriptor.getDefaultInstances());
    }

    /**
     * Creates a {@link Row} for each reachable {@link FlowNode}
     */
    private Map<FlowNode, Row> createAllRows() {
        heads = execution.getCurrentHeads();
        final DepthFirstScanner scanner = new DepthFirstScanner();
        scanner.setup(heads);

        // nodes that we've visited
        final Map<FlowNode,Row> rows = new LinkedHashMap<FlowNode, Row>();

        for (FlowNode n : scanner) {
            Row row = new Row(n);
            rows.put(n, row);
        }
        return rows;
    }

    /**
     * Builds up forward graph edge references from {@link FlowNode#getParents()} back pointers.
     */
    private Row buildForwardReferences(Map<FlowNode, Row> rows) {
        // build up all the forward references
        Row firstRow = null;
        for (Row r : rows.values()) {
            FlowNode n = r.node;
            for (FlowNode p : n.getParents()) {
                rows.get(p).addGraphChild(r);
            }
            if (n.getParents().isEmpty()) {
                if (firstRow==null)
                    firstRow = r;
                else {
                    // in an unlikely case when we find multiple head nodes,
                    // treat them all as siblings
                    firstRow.addGraphSibling(r);
                }
            }

            if (r.isEnd()) {
                BlockEndNode en = (BlockEndNode) r.node;
                Row sr = rows.get(en.getStartNode());

                if (r.hasStartTime && sr.hasStartTime) {  // Block timing is based on the start-to-end times
                    sr.durationMillis = (r.startTimeMillis-sr.startTimeMillis);
                    sr.hasTiming = true;
                }

                assert sr.endNode==null : "start/end mapping should be 1:1";
                sr.endNode = en;
            }
        }
        // graph shouldn't contain any cycle, so there should be at least one 'head node'
        assert firstRow!=null;
        return firstRow;
    }

    private void buildTreeFromGraph(Map<FlowNode, Row> rows) {
    /*
        Convert DAG into Tree

        In DAG, parent/child relationship is a successor relationship. For example,
        if an AtomNode A runs then AtomNode B runs, A is a parent of B.

        In the tree view, we'd like A to be the elder sibling of B. This is where
        we do that translation.

        The general strategy is that
        BlockStartNode has its graph children turned into tree children
        (for example so that a fork start node can have all its branches as tree children.)

        FlowEndNode gets dropped from the tree (and logically thought of as a part of the start node),
        but graph children of FlowEndNode become tree siblings of BlockStartNode.
        (TODO: what if the end node wants to show information, such as in the case of validated merge?)
        addTreeSibling/addTreeChild handles the logic of dropping end node from the tree.

        Other nodes (I'm thinking atom nodes are the only kinds here) have their graph children
        turned into tree siblings.
     */
        for (Row r : rows.values()) {
            if (r.isStart()) {
                for (Row c=r.firstGraphChild; c!=null; c=c.nextGraphSibling) {
                    r.addTreeChild(c);
                }
            } else
            if (r.isEnd()) {
                BlockEndNode en = (BlockEndNode) r.node;
                Row sr = rows.get(en.getStartNode());

                for (Row c=r.firstGraphChild; c!=null; c=c.nextGraphSibling) {
                    sr.addTreeSibling(c);
                }
            } else {
                for (Row c=r.firstGraphChild; c!=null; c=c.nextGraphSibling) {
                    r.addTreeSibling(c);
                }
            }
        }
    }

    /**
     * Sets {@link Row#treeDepth} to the depth of the node from its tree root.
     */
    private void buildTreeDepth(Row r) {
        r.treeDepth = 0;

        Stack<Row> q = new Stack<Row>();
        q.add(r);

        while (!q.isEmpty()) {
            r = q.pop();
            if (r.firstTreeChild!=null) {
                q.add(r.firstTreeChild);
                r.firstTreeChild.treeDepth = r.treeDepth +1;
            }
            if (r.nextTreeSibling!=null) {
                q.add(r.nextTreeSibling);
                r.nextTreeSibling.treeDepth = r.treeDepth;
            }
        }
    }

    /**
     * Order tree into a sequence.
     */
    private List<Row> order(Row r) {
        List<Row> rows = new ArrayList<Row>();

        Stack<Row> ancestors = new Stack<Row>();

        while (true) {
            rows.add(r);

            if (r.firstTreeChild!=null) {
                if (r.nextTreeSibling!=null)
                    ancestors.push(r.nextTreeSibling);
                r = r.firstTreeChild;
            } else
            if (r.nextTreeSibling!=null) {
                r = r.nextTreeSibling;
            } else {
                if (ancestors.isEmpty())
                    break;
                r = ancestors.pop();
            }
        }

        for (int i=0; i<rows.size(); i++) {
            Row newRow = rows.get(i);
            if (newRow.durationMillis == 0 && newRow.hasStartTime) {
                if (newRow.node instanceof BlockStartNode && newRow.endNode == null) { // Block is running & incomplete
                    newRow.durationMillis = System.currentTimeMillis()-newRow.startTimeMillis;
                    newRow.hasTiming = true;
                } else {
                    Row nextRow = newRow.firstGraphChild;
                    if (nextRow.hasStartTime) {
                        newRow.durationMillis = nextRow.startTimeMillis-newRow.startTimeMillis;
                        newRow.hasTiming = true;
                    }
                }
            }
        }

        return rows;
    }

    public static class Row {
        private final FlowNode node;
        private long durationMillis = 0L;
        private final long startTimeMillis;
        private final boolean hasStartTime;
        private boolean hasTiming = false;

        /**
         * We collapse {@link BlockStartNode} and {@link BlockEndNode} into one row.
         * When it happens, this field refers to {@link BlockEndNode} while
         * {@link #node} refers to {@link BlockStartNode}.
         */
        private BlockEndNode endNode;

        // reverse edges of node.parents, which forms DAG
        private Row firstGraphChild;
        private Row nextGraphSibling;

        // tree view
        private Row firstTreeChild;
        private Row nextTreeSibling;

        private int treeDepth = -1;

        private Row(FlowNode node) {
            this.node = node;
            TimingAction act = node.getAction(TimingAction.class);
            if (act != null) {
                this.startTimeMillis = act.getStartTime();
                this.hasStartTime = true;
                if (node.isActive()) {
                    this.durationMillis=System.currentTimeMillis()-this.startTimeMillis;
                    this.hasTiming = true;
                }
            } else {
                this.startTimeMillis = 0L;
                this.hasStartTime = false;
            }
        }

        public FlowNode getNode() {
            return node;
        }

        public int getTreeDepth() {
            return treeDepth;
        }

        public String getDisplayName() {
            if (node instanceof StepNode && node instanceof AtomNode) {
                // TODO make StepAtomNode.effectiveFunctionName into an API
                return node.getDisplayFunctionName();
            } else if (node instanceof StepNode && node instanceof BlockStartNode) {
                if (node.getAction(BodyInvocationAction.class) != null) {
                    // TODO cannot access StepAtomNode.effectiveFunctionName from here
                    LinearBlockHoppingScanner scanner = new LinearBlockHoppingScanner();
                    scanner.setup(node);
                    for (FlowNode start : scanner) {
                        if (start instanceof StepNode && start instanceof BlockStartNode && start.getPersistentAction(BodyInvocationAction.class) == null) {
                            String base = start.getDisplayFunctionName() + " block";
                            LabelAction a = node.getPersistentAction(LabelAction.class);
                            return a != null ? base + " (" + a.getDisplayName() + ")" : base;
                        }
                    }
                } else {
                    return node.getDisplayFunctionName();
                }
            }
            // Fallback, e.g. FlowStartNode:
            return node.getDisplayFunctionName();
        }

        public boolean isHasStartTime() {
            return hasStartTime;
        }

        public long getStartTimeMillis() {
            return this.startTimeMillis;
        }

        public long getDurationMillis() {
            return this.durationMillis;
        }

        private Row getNextGraphSibling() {
            return nextGraphSibling;
        }

        private Row getNextTreeSibling() {
            return nextTreeSibling;
        }

        public String getDurationString() {
            if (!this.hasTiming) {
                return "no timing";
            } else {
                if (this.durationMillis == 0) {
                    return "<1 ms";
                } else {
                    return Util.getTimeSpanString(this.durationMillis);
                }
            }
        }

        public boolean isStart() {
            return node instanceof BlockStartNode;
        }

        boolean isEnd() {
            return node instanceof BlockEndNode;
        }

        public boolean isExecuted() {
            return NotExecutedNodeAction.isExecuted(node);
        }

        void addGraphChild(Row r) {
            if (firstGraphChild ==null) {
                firstGraphChild = r;
            } else {
                firstGraphChild.addGraphSibling(r);
            }
        }

        void addGraphSibling(Row r) {
            Row s = findLastSibling(this, Row::getNextGraphSibling);
            s.nextGraphSibling = r;

            if (s.hasStartTime && r.hasStartTime) {
                s.durationMillis = (r.startTimeMillis-s.startTimeMillis);
                s.hasTiming = true;
            }
        }

        void addTreeChild(Row r) {
            if (r.isEnd())  return;

            if (firstTreeChild ==null)
                firstTreeChild = r;
            else {
                firstTreeChild.addTreeSibling(r);
            }
        }

        void addTreeSibling(Row r) {
            if (r.isEnd())  return;

            Row s = findLastSibling(this, Row::getNextTreeSibling);
            s.nextTreeSibling = r;

            if (s.hasStartTime && r.hasStartTime) { // Store timing
                s.durationMillis = (r.startTimeMillis-s.startTimeMillis);
                s.hasTiming = true;
            }
        }

        private Row findLastSibling(Row r, Function<Row, Row> siblingGetter) {
            Row s = r;
            IdentityHashMap<Row, Boolean> visited = new IdentityHashMap<>(Collections.singletonMap(s, true));
            while (siblingGetter.apply(s) != null) {
                Row nextS = siblingGetter.apply(s);
                if (visited.put(nextS, true) != null) {
                    throw new IllegalStateException("Saw " + nextS.node + " twice when finding siblings of " + r.node);
                }
                s = nextS;
            }
            return s;
        }
    }
}
