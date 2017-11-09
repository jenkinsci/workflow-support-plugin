package org.jenkinsci.plugins.workflow.support.storage;

import hudson.XmlFile;
import hudson.model.Action;
import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tries to test the storage engine
 */
public class SimpleXStreamStorageTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public JenkinsRule j = new JenkinsRule();

    File storageDir;
    XmlFile file;

    @Before
    public void setup() {
        File dir = new File(j.jenkins.getRootDir(), "storageTest");
        dir.delete();
        storageDir = new File(dir, "storage");
        file = new XmlFile(new File(dir, "execution.xml"));
    }

    /** Tests that basic nodes read and write correctly */
    @Test
    public void verifyBasicPersist() throws Exception {
        MockFlowExecution mock = new MockFlowExecution();
        SimpleXStreamFlowNodeStorage storage = new SimpleXStreamFlowNodeStorage(mock, storageDir);
        mock.setStorage(storage);
        file.write(mock);

        // Just any old node
        AtomNode simple = new StorageTestUtils.SimpleAtomNode(mock, "simple");
        mock.saveActions(simple, Collections.EMPTY_LIST);

        // Node with actions added after storing
        AtomNode notQuiteAsSimple = new StorageTestUtils.SimpleAtomNode(mock, "actionAddedAfterStore", simple);
        storage.storeNode(notQuiteAsSimple);
        notQuiteAsSimple.addAction(new LabelAction("displayLabel"));

        // Node saved with explicit actions set
        StorageTestUtils.SimpleAtomNode directlySaveActions = new StorageTestUtils.SimpleAtomNode(mock, "explictSaveActions", notQuiteAsSimple);
        List<Action> acts = new ArrayList<Action>();
        acts.add(new LabelAction("yetAnotherLabel"));
        acts.add(new BodyInvocationAction());
        storage.saveActions(directlySaveActions, acts);
        directlySaveActions.setActions(acts);  // Doesn't trigger writethrough

        // Deferred save
        AtomNode deferredSave = new StorageTestUtils.SimpleAtomNode(mock, "deferredSave", notQuiteAsSimple);
        storage.storeNode(deferredSave, true);
        deferredSave.addAction(new LabelAction("I was deferred but should still be written"));

        storage.flush();

        // Now we try to read it back
        MockFlowExecution mock2 = new MockFlowExecution();
        FlowNodeStorage storageAfterRead = new SimpleXStreamFlowNodeStorage(mock2, storageDir);

        StorageTestUtils.assertNodesMatch(simple, storageAfterRead.getNode(simple.getId()));
        StorageTestUtils.assertNodesMatch(notQuiteAsSimple, storageAfterRead.getNode(notQuiteAsSimple.getId()));
        StorageTestUtils.assertNodesMatch(directlySaveActions, storageAfterRead.getNode(directlySaveActions.getId()));
        StorageTestUtils.assertNodesMatch(deferredSave, storageAfterRead.getNode(deferredSave.getId()));

        // Ensures we can still read the correct node from deferred version
        StorageTestUtils.assertNodesMatch(storage.getNode(deferredSave.getId()), storage.getNode(deferredSave.getId()));
    }

    /** Verify that when nodes are explicitly flushed they do */
    @Test
    public void testDeferWriteAndFlush() throws Exception {
        MockFlowExecution mock = new MockFlowExecution();
        SimpleXStreamFlowNodeStorage storage = new SimpleXStreamFlowNodeStorage(mock, storageDir);
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
        FlowNodeStorage storageAfterRead = new SimpleXStreamFlowNodeStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(directlyStored, storageAfterRead.getNode(directlyStored.getId()));
        Assert.assertNull(storageAfterRead.getNode(deferredWriteNode.getId()));

        // Flush the deferred one and confirm it's on disk now
        storage.flushNode(deferredWriteNode);
        storageAfterRead = new SimpleXStreamFlowNodeStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(deferredWriteNode, storageAfterRead.getNode(deferredWriteNode.getId()));

        // Add an action and re-read to confirm that it doesn't autopersist still
        deferredWriteNode.addAction(new BodyInvocationAction());
        storageAfterRead = new SimpleXStreamFlowNodeStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        Assert.assertEquals(1, storageAfterRead.getNode(deferredWriteNode.getId()).getActions().size());

        // Mark node for autopersist and confirm it actually does now by adding a new action
        storage.autopersist(deferredWriteNode);
        deferredWriteNode.addAction(new TimingAction());
        storageAfterRead = new SimpleXStreamFlowNodeStorage(mock2, storageDir);
        mock2.setStorage(storageAfterRead);
        StorageTestUtils.assertNodesMatch(deferredWriteNode, storageAfterRead.getNode(deferredWriteNode.getId()));
    }
}
