package org.jenkinsci.plugins.workflow.support.storage;

import hudson.XmlFile;
import hudson.model.Action;
import org.jenkinsci.plugins.workflow.actions.BodyInvocationAction;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.graph.AtomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base test for the storage implementations
 * @author Sam Van Oort
 */
@WithJenkins
abstract class AbstractStorageTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    protected JenkinsRule j;

    protected File storageDir;
    protected XmlFile file;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        j = rule;
        File dir = new File(j.jenkins.getRootDir(), "storageTest");
        dir.delete();
        storageDir = new File(dir, "storage");
        file = new XmlFile(new File(dir, "execution.xml"));
    }

    @AfterEach
    void afterEach() {
        File dir = new File(j.jenkins.getRootDir(), "storageTest");
        dir.delete();
    }

    // Implement me for the implementation we're testing
    protected abstract FlowNodeStorage instantiateStorage(MockFlowExecution exec, File storageDirectory);

    /** Tests that basic nodes read and write correctly */
    @Test
    void verifyBasicPersist() throws Exception {
        MockFlowExecution mock = new MockFlowExecution();
        FlowNodeStorage storage = instantiateStorage(mock, storageDir);
        mock.setStorage(storage);
        assert storage.isPersistedFully();

        // Just any old node
        AtomNode simple = new StorageTestUtils.SimpleAtomNode(mock, "simple");
        mock.saveActions(simple, Collections.EMPTY_LIST);

        // Node with actions added after storing
        AtomNode notQuiteAsSimple = new StorageTestUtils.SimpleAtomNode(mock, "actionAddedAfterStore", simple);
        storage.storeNode(notQuiteAsSimple);
        notQuiteAsSimple.addAction(new LabelAction("displayLabel"));

        // Node saved with explicit actions set
        StorageTestUtils.SimpleAtomNode directlySaveActions = new StorageTestUtils.SimpleAtomNode(mock, "explictSaveActions", notQuiteAsSimple);
        List<Action> acts = new ArrayList<>();
        acts.add(new LabelAction("yetAnotherLabel"));
        acts.add(new BodyInvocationAction());
        storage.saveActions(directlySaveActions, acts);
        directlySaveActions.setActions(acts);  // Doesn't trigger writethrough to persistence, needed for consistency.

        // Deferred save
        AtomNode deferredSave = new StorageTestUtils.SimpleAtomNode(mock, "deferredSave", notQuiteAsSimple);
        storage.storeNode(deferredSave, true);
        deferredSave.addAction(new LabelAction("I was deferred but should still be written"));
        assertFalse(storage.isPersistedFully());

        storage.flush();
        assertTrue(storage.isPersistedFully());

        // Now we try to read it back
        MockFlowExecution mock2 = new MockFlowExecution();
        FlowNodeStorage storageAfterRead = instantiateStorage(mock2, storageDir);
        assertTrue(storage.isPersistedFully());

        StorageTestUtils.assertNodesMatch(simple, storageAfterRead.getNode(simple.getId()));
        StorageTestUtils.assertNodesMatch(notQuiteAsSimple, storageAfterRead.getNode(notQuiteAsSimple.getId()));
        StorageTestUtils.assertNodesMatch(directlySaveActions, storageAfterRead.getNode(directlySaveActions.getId()));
        StorageTestUtils.assertNodesMatch(deferredSave, storageAfterRead.getNode(deferredSave.getId()));

        // Ensures we can still read the correct node from deferred version
        StorageTestUtils.assertNodesMatch(storage.getNode(deferredSave.getId()), storage.getNode(deferredSave.getId()));
    }
}
