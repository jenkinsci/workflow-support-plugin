package org.jenkinsci.plugins.workflow.support.storage;


import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;

/**
 * Tries to test the storage engine
 */
public class SimpleXStreamStorageTest extends AbstractStorageTest {

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
        directlyStored.addAction(new LabelAction("directStored"));

        // Node with actions added after storing, and deferred write
        AtomNode deferredWriteNode = new StorageTestUtils.SimpleAtomNode(mock, "deferredWrite");
        storage.storeNode(deferredWriteNode, true);
        deferredWriteNode.addAction(new LabelAction("displayLabel"));

        // Read and confirm the non-deferred one wrote, and the deferred one didn't
        MockFlowExecution mock2 = new MockFlowExecution();
        FlowNodeStorage storageAfterRead = instantiateStorage(mock2, storageDir);

        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(directlyStored, storageAfterRead.getNode(directlyStored.getId()));
        Assert.assertNull(storageAfterRead.getNode(deferredWriteNode.getId()));


        // Flush the deferred one and confirm it's on disk now
        storage.flushNode(deferredWriteNode);
        storageAfterRead = instantiateStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(deferredWriteNode, storageAfterRead.getNode(deferredWriteNode.getId()));

        // Add an action and re-read to confirm that it doesn't autopersist still
        deferredWriteNode.addAction(new BodyInvocationAction());
        storageAfterRead = instantiateStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        Assert.assertEquals(1, storageAfterRead.getNode(deferredWriteNode.getId()).getActions().size());

        // Mark node for autopersist and confirm it actually does now by adding a new action
        storage.autopersist(deferredWriteNode);
        deferredWriteNode.addAction(new TimingAction());
        storageAfterRead = instantiateStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(deferredWriteNode, storageAfterRead.getNode(deferredWriteNode.getId()));
    }
}
