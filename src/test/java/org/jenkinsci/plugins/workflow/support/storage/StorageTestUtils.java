package org.jenkinsci.plugins.workflow.support.storage;

import hudson.model.Action;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Utilities for verifying storage
 * @author Sam Van Oort
 */
class StorageTestUtils {

    /** Test that the basic fields and the actions for nodes match */
    public static void assertNodesMatch(FlowNode expected, FlowNode actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getClass().toString(), actual.getClass().toString());
        assertArrayEquals(expected.getParentIds().toArray(), actual.getParentIds().toArray());

        List<Action> expectedActionList = expected.getActions();
        List<Action> actualActionList = actual.getActions();

        assertEquals(expectedActionList.size(), actualActionList.size());
        for (int i=0; i<expectedActionList.size(); i++) {
            try {
                Action expectedAction = expectedActionList.get(i);
                Action actualAction = actualActionList.get(i);

                assertEquals(expectedAction.getClass().toString(), actualAction.getClass().toString());
                assertEquals(expectedAction.getDisplayName(), actualAction.getDisplayName());
            } catch (AssertionError ae) {
                System.out.println("Assertion violated with flownode actions at index "+i);
                throw ae;
            }

        }
    }

    /** Trivial impl for testing */
    static class SimpleAtomNode extends AtomNode {

        @Override
        protected String getTypeDisplayName() {
            return "atom";
        }

        @Override
        public synchronized void setActions(List<Action> actions) {
            super.setActions(actions);
        }

        SimpleAtomNode(FlowExecution exec, String id, FlowNode... parents) {
            super(exec, id, parents);
        }

        SimpleAtomNode(FlowExecution exec, String id) {
            super(exec, id);
        }
    }
}
