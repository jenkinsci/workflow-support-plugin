package org.jenkinsci.plugins.workflow.support.storage;

import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;

/**
 * Actually attempts to test the storage engine
 */
class BulkStorageTest extends AbstractStorageTest {

    @Override
    protected FlowNodeStorage instantiateStorage(MockFlowExecution exec, File storageDirectory) {
        return new BulkFlowNodeStorage(exec, storageDir);
    }

    /** Tests the bulk-flushing behavior works as advertised. */
    @Test
    void testDeferWriteAndFlush() throws Exception {
        MockFlowExecution mock = new MockFlowExecution();
        FlowNodeStorage storage = instantiateStorage(mock, storageDir);
        mock.setStorage(storage);
        file.write(mock);

        // Non-deferred write
        AtomNode directlyStored = new StorageTestUtils.SimpleAtomNode(mock, "directlyStored");
        directlyStored.addAction(new LabelAction("directStored"));
        storage.storeNode(directlyStored, false);
        assert storage.isPersistedFully();
        // If we added the action after, it wouldn't write out, because lump storage only writes on flush

        // Read and confirm the non-deferred one wrote
        MockFlowExecution mock2 = new MockFlowExecution();
        FlowNodeStorage storageAfterRead = instantiateStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(directlyStored, storageAfterRead.getNode(directlyStored.getId()));

        // Node with actions added after storing, and deferred write
        AtomNode deferredWriteNode = new StorageTestUtils.SimpleAtomNode(mock, "deferredWrite");
        storage.storeNode(deferredWriteNode, true);
        assert !storage.isPersistedFully();
        deferredWriteNode.addAction(new LabelAction("displayLabel"));

        // Read back and confirm the deferred write didn't flush to disk
        storageAfterRead = instantiateStorage(mock2, storageDir);
        assert storageAfterRead.isPersistedFully();
        mock2.setStorage(storageAfterRead);
        assertNull(storageAfterRead.getNode(deferredWriteNode.getId()));
        StorageTestUtils.assertNodesMatch(directlyStored, storageAfterRead.getNode(directlyStored.getId())); // Make sure we didn't corrupt old node either

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
    }
}
