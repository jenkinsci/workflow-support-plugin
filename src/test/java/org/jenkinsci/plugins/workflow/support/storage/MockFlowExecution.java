package org.jenkinsci.plugins.workflow.support.storage;

import hudson.model.Action;
import hudson.model.Result;
import hudson.model.Saveable;
import hudson.security.ACL;
import jenkins.model.CauseOfInterruption;
import org.acegisecurity.Authentication;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock {@link org.jenkinsci.plugins.workflow.flow.FlowExecution} for testing storage
 * @author Sam Van Oort
 */
 class MockFlowExecution extends FlowExecution {

    List<FlowNode> heads = new ArrayList<FlowNode>();
    FlowNodeStorage storage;

    MockFlowExecution(@Nonnull FlowNodeStorage storage) {
        this.storage = storage;
    }

    MockFlowExecution() {

    }

    @Override
    public void start() throws IOException {

    }

    @Override
    public FlowExecutionOwner getOwner() {
        return null;
    }

    @Override
    public List<FlowNode> getCurrentHeads() {
        return heads;
    }

    public MockFlowExecution setCurrentHeads(List<FlowNode> heads) {
        this.heads = heads;
        return this;
    }

    @Override
    public boolean isCurrentHead(FlowNode n) {
        return this.heads.contains(n);
    }

    @Override
    public void interrupt(Result r, CauseOfInterruption... causes) throws IOException, InterruptedException {

    }

    @Override
    public void addListener(GraphListener listener) {

    }

    @Override
    public void onLoad() {

    }

    public FlowNodeStorage getStorage() {
        return storage;
    }

    public MockFlowExecution setStorage(@Nonnull FlowNodeStorage store) {
        this.storage = store;
        return this;
    }

    @CheckForNull
    @Override
    public FlowNode getNode(String id) throws IOException {
        return getStorage().getNode(id);
    }

    @Nonnull
    @Override
    public Authentication getAuthentication() {
        return ACL.SYSTEM;
    }

    @Override
    public List<Action> loadActions(FlowNode node) throws IOException {
        return getStorage().loadActions(node);
    }

    @Override
    public void saveActions(FlowNode node, List<Action> actions) throws IOException {
        getStorage().saveActions(node, actions);
    }
}
