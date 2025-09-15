package org.jenkinsci.plugins.workflow.support.storage;


import hudson.model.Result;
import hudson.util.RobustReflectionConverter;
import java.io.File;
import java.util.logging.Level;
import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.recipes.LocalData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tries to test the storage engine
 */
class SimpleXStreamStorageTest extends AbstractStorageTest {

    @SuppressWarnings("unused")
    private final LogRecorder logger = new LogRecorder().record(RobustReflectionConverter.class, Level.FINE).capture(50);

    @Override
    protected FlowNodeStorage instantiateStorage(MockFlowExecution exec, File storageDirectory) {
        return new SimpleXStreamFlowNodeStorage(exec, storageDirectory);
    }

    /** Verify that when nodes are explicitly flushed they do write to disk. */
    @Test
    void testDeferWriteAndFlush() throws Exception {
        MockFlowExecution mock = new MockFlowExecution();
        FlowNodeStorage storage = instantiateStorage(mock, storageDir);
        mock.setStorage(storage);
        file.write(mock);

        // Non-deferred write
        AtomNode directlyStored = new StorageTestUtils.SimpleAtomNode(mock, "directlyStored");
        storage.storeNode(directlyStored, false);
        assert storage.isPersistedFully();
        directlyStored.addAction(new LabelAction("directStored"));

        // Node with actions added after storing, and deferred write
        AtomNode deferredWriteNode = new StorageTestUtils.SimpleAtomNode(mock, "deferredWrite");
        storage.storeNode(deferredWriteNode, true);
        deferredWriteNode.addAction(new LabelAction("displayLabel"));
        assert !storage.isPersistedFully();

        // Read and confirm the non-deferred one wrote, and the deferred one didn't
        MockFlowExecution mock2 = new MockFlowExecution();
        FlowNodeStorage storageAfterRead = instantiateStorage(mock2, storageDir);
        assert storageAfterRead.isPersistedFully();

        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(directlyStored, storageAfterRead.getNode(directlyStored.getId()));
        assertNull(storageAfterRead.getNode(deferredWriteNode.getId()));

        // Flush the deferred one and confirm it's on disk now
        storage.flushNode(deferredWriteNode);
        assert storage.isPersistedFully();
        storageAfterRead = instantiateStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(deferredWriteNode, storageAfterRead.getNode(deferredWriteNode.getId()));

        // Add an action and re-read to confirm that it doesn't autopersist still
        deferredWriteNode.addAction(new BodyInvocationAction());
        assert !storage.isPersistedFully();
        storageAfterRead = instantiateStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        assertEquals(1, storageAfterRead.getNode(deferredWriteNode.getId()).getActions().size());

        // Mark node for autopersist and confirm it actually does now by adding a new action
        storage.autopersist(deferredWriteNode);
        assert storage.isPersistedFully();
        deferredWriteNode.addAction(new TimingAction());
        storageAfterRead = instantiateStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(deferredWriteNode, storageAfterRead.getNode(deferredWriteNode.getId()));
    }

    @LocalData
    @Test
    void actionDeserializationShouldBeRobust() throws Exception {
        /*
        var p = j.createProject(WorkflowJob.class);
        p.addProperty(new DurabilityHintJobProperty(FlowDurabilityHint.MAX_SURVIVABILITY));
        p.setDefinition(new CpsFlowDefinition(
                "stage('test') {\n" +
                "  sleep 120\n" +
                "}\n", true));
        var b = p.scheduleBuild2(0).waitForStart();
        Thread.sleep(5*1000);
        ((CpsFlowExecution) b.getExecution()).getStorage().flush();
        */
        var p = j.jenkins.getItemByFullName("test0", WorkflowJob.class);
        var b = p.getLastBuild();
        j.assertBuildStatus(Result.SUCCESS, j.waitForCompletion(b));
        var stageBodyStartNode = (StepStartNode) b.getExecution().getNode("4");
        assertThat(stageBodyStartNode, not(nullValue()));
        var label = stageBodyStartNode.getPersistentAction(LabelAction.class);
        assertThat(label.getDisplayName(), equalTo("test"));
    }
    // Used to create @LocalData for above test:
    /*
    public static class MyAction extends InvisibleAction implements PersistentAction {
        private final String value = "42";
    }
    @TestExtension("actionDeserializationShouldBeRobust")
    public static class MyActionAdder implements GraphListener.Synchronous {
        @Override
        public void onNewHead(FlowNode node) {
            node.addAction(new MyAction());
        }
    }
    */
}
