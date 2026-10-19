package org.jenkinsci.plugins.workflow.support.storage;


import hudson.model.Result;
import hudson.util.RobustReflectionConverter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.recipes.LocalData;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tries to test the storage engine
 */
public class SimpleXStreamStorageTest extends AbstractStorageTest {

    @Rule public LoggerRule logger = new LoggerRule().record(RobustReflectionConverter.class, Level.FINE).capture(50);

    @Override
    public FlowNodeStorage instantiateStorage(MockFlowExecution exec, File storageDirectory) {
        return new SimpleXStreamFlowNodeStorage(exec, storageDirectory);
    }

    /** Verify that when nodes are explicitly flushed they do write to disk. */
    @Test
    public void testDeferWriteAndFlush() throws Exception {
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
        Assert.assertNull(storageAfterRead.getNode(deferredWriteNode.getId()));


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
        Assert.assertEquals(1, storageAfterRead.getNode(deferredWriteNode.getId()).getActions().size());

        // Mark node for autopersist and confirm it actually does now by adding a new action
        storage.autopersist(deferredWriteNode);
        assert storage.isPersistedFully();
        deferredWriteNode.addAction(new TimingAction());
        storageAfterRead = instantiateStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(deferredWriteNode, storageAfterRead.getNode(deferredWriteNode.getId()));
    }

    @LocalData
    @Test public void actionDeserializationShouldBeRobust() throws Exception {
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
        b.delete();
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

    public static class ReentrantOnLoadAction extends hudson.model.InvisibleAction implements org.jenkinsci.plugins.workflow.actions.FlowNodeAction, org.jenkinsci.plugins.workflow.actions.PersistentAction {
        private final String targetNodeId;
        private transient FlowNode resolvedTarget;
        private static volatile boolean failNextLoad;

        public ReentrantOnLoadAction(String targetNodeId) {
            this.targetNodeId = targetNodeId;
        }

        @Override
        public void onLoad(FlowNode parent) {
            if (failNextLoad) {
                failNextLoad = false;
                throw new IllegalStateException("Simulated transient onLoad failure");
            }
            try {
                this.resolvedTarget = parent.getExecution().getNode(targetNodeId);
            } catch (Exception x) {
                throw new RuntimeException(x);
            }
        }
    }

    @Test
    public void reentrantActionOnLoadDoesNotDeadlockOrRecurse() throws Exception {
        MockFlowExecution mock = new MockFlowExecution();
        FlowNodeStorage storage = instantiateStorage(mock, storageDir);
        mock.setStorage(storage);
        file.write(mock);

        AtomNode nodeA = new StorageTestUtils.SimpleAtomNode(mock, "nodeA");
        AtomNode nodeB = new StorageTestUtils.SimpleAtomNode(mock, "nodeB", nodeA);
        nodeA.addAction(new ReentrantOnLoadAction("nodeA"));
        nodeB.addAction(new ReentrantOnLoadAction("nodeA"));
        storage.storeNode(nodeA, false);
        storage.storeNode(nodeB, false);

        MockFlowExecution mockAfterRead = new MockFlowExecution();
        FlowNodeStorage storageAfterRead = instantiateStorage(mockAfterRead, storageDir);
        mockAfterRead.setStorage(storageAfterRead);

        ReentrantOnLoadAction.failNextLoad = true;
        Assert.assertThrows(IllegalStateException.class, () -> storageAfterRead.getNode("nodeB"));

        FlowNode loadedB = storageAfterRead.getNode("nodeB");
        Assert.assertNotNull(loadedB);
        ReentrantOnLoadAction actionB = loadedB.getAction(ReentrantOnLoadAction.class);
        Assert.assertNotNull(actionB);
        Assert.assertNotNull(actionB.resolvedTarget);
        Assert.assertEquals("nodeA", actionB.resolvedTarget.getId());
    }

    public static class ConcurrentOnLoadAction extends hudson.model.InvisibleAction implements org.jenkinsci.plugins.workflow.actions.FlowNodeAction, org.jenkinsci.plugins.workflow.actions.PersistentAction {
        private final String targetNodeId;
        private static CountDownLatch entered;
        private static CountDownLatch release;

        ConcurrentOnLoadAction(String targetNodeId) {
            this.targetNodeId = targetNodeId;
        }

        @Override
        public void onLoad(FlowNode parent) {
            entered.countDown();
            try {
                if (!entered.await(10, TimeUnit.SECONDS) || !release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent node loading did not reach the test barrier");
                }
                if (targetNodeId != null) {
                    parent.getExecution().getNode(targetNodeId);
                }
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(x);
            } catch (IOException x) {
                throw new UncheckedIOException(x);
            }
        }
    }

    private FlowNodeStorage concurrentLoadStorage(boolean cyclic) throws Exception {
        MockFlowExecution writer = new MockFlowExecution();
        FlowNodeStorage storage = instantiateStorage(writer, storageDir);
        writer.setStorage(storage);
        AtomNode nodeA = new StorageTestUtils.SimpleAtomNode(writer, "nodeA");
        nodeA.addAction(new ConcurrentOnLoadAction(cyclic ? "nodeB" : null));
        storage.storeNode(nodeA, false);
        if (cyclic) {
            AtomNode nodeB = new StorageTestUtils.SimpleAtomNode(writer, "nodeB");
            nodeB.addAction(new ConcurrentOnLoadAction("nodeA"));
            storage.storeNode(nodeB, false);
        }
        MockFlowExecution reader = new MockFlowExecution();
        FlowNodeStorage reloaded = instantiateStorage(reader, storageDir);
        reader.setStorage(reloaded);
        return reloaded;
    }

    private ExecutorService loadingThreads() {
        return Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Test
    public void concurrentActionCycleFailsWithoutDeadlock() throws Exception {
        ConcurrentOnLoadAction.entered = new CountDownLatch(2);
        ConcurrentOnLoadAction.release = new CountDownLatch(0);
        FlowNodeStorage storage = concurrentLoadStorage(true);
        ExecutorService threads = loadingThreads();
        try {
            Future<FlowNode> nodeA = threads.submit(() -> storage.getNode("nodeA"));
            Future<FlowNode> nodeB = threads.submit(() -> storage.getNode("nodeB"));
            Assert.assertTrue(ConcurrentOnLoadAction.entered.await(10, TimeUnit.SECONDS));
            int failures = 0;
            for (Future<FlowNode> loaded : java.util.List.of(nodeA, nodeB)) {
                try {
                    Assert.assertNotNull(loaded.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException x) {
                    Assert.assertTrue(x.getCause() instanceof UncheckedIOException);
                    Assert.assertTrue(x.getCause().getCause().getMessage().startsWith("Cyclic FlowNode action initialization"));
                    failures++;
                }
            }
            Assert.assertEquals(2, failures);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    public void concurrentCallerWaitsForActionInitialization() throws Exception {
        ConcurrentOnLoadAction.entered = new CountDownLatch(1);
        ConcurrentOnLoadAction.release = new CountDownLatch(1);
        FlowNodeStorage storage = concurrentLoadStorage(false);
        ExecutorService threads = loadingThreads();
        try {
            Future<FlowNode> first = threads.submit(() -> storage.getNode("nodeA"));
            Assert.assertTrue(ConcurrentOnLoadAction.entered.await(10, TimeUnit.SECONDS));
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<FlowNode> second = threads.submit(() -> {
                secondStarted.countDown();
                return storage.getNode("nodeA");
            });
            Assert.assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            Assert.assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            ConcurrentOnLoadAction.release.countDown();
            Assert.assertSame(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            ConcurrentOnLoadAction.release.countDown();
            threads.shutdownNow();
        }
    }

    @Test
    public void nestedActionCycleDoesNotCachePartiallyInitializedNodes() throws Exception {
        MockFlowExecution writer = new MockFlowExecution();
        FlowNodeStorage storage = instantiateStorage(writer, storageDir);
        writer.setStorage(storage);
        AtomNode nodeA = new StorageTestUtils.SimpleAtomNode(writer, "nodeA");
        AtomNode nodeB = new StorageTestUtils.SimpleAtomNode(writer, "nodeB");
        nodeA.addAction(new ReentrantOnLoadAction("nodeB"));
        nodeB.addAction(new ReentrantOnLoadAction("nodeA"));
        storage.storeNode(nodeA, false);
        storage.storeNode(nodeB, false);

        MockFlowExecution reader = new MockFlowExecution();
        FlowNodeStorage reloaded = instantiateStorage(reader, storageDir);
        reader.setStorage(reloaded);
        RuntimeException failure = Assert.assertThrows(RuntimeException.class, () -> reloaded.getNode("nodeA"));
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        Assert.assertTrue(cause instanceof IOException);
        Assert.assertTrue(cause.getMessage().startsWith("Cyclic FlowNode action initialization"));

        // Replace the cyclic action on disk. Neither failed load may be cached.
        storage.storeNode(new StorageTestUtils.SimpleAtomNode(writer, "nodeA"), false);
        FlowNode loadedB = reloaded.getNode("nodeB");
        Assert.assertSame(reloaded.getNode("nodeA"), loadedB.getAction(ReentrantOnLoadAction.class).resolvedTarget);
    }
}

