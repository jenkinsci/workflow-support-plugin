/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.actions;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Item;
import hudson.security.AccessControlled;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.TransientActionFactory;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Display of all {@link WorkspaceActionImpl}s for a build.
 */
@Restricted(NoExternalUse.class)
public final class WorkspaceRunAction implements Action {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceRunAction.class.getName());

    private final FlowExecutionOwner.Executable build;
    public final FlowExecutionOwner owner;

    WorkspaceRunAction(FlowExecutionOwner.Executable build, FlowExecutionOwner owner) {
        this.build = build;
        this.owner = owner;
    }

    private boolean hasNoWorkspacePermission() {
        return (build instanceof AccessControlled && !((AccessControlled) build).hasPermission(Item.WORKSPACE));
    }

    @Override public String getIconFileName() {
        return hasNoWorkspacePermission() ? null : "folder.png";
    }

    @Override public String getDisplayName() {
        return hasNoWorkspacePermission() ? null : Messages.workspaces();
    }

    @Override public String getUrlName() {
        return hasNoWorkspacePermission() ? null : "ws";
    }

    public List<WorkspaceActionImpl> getActions() {
        FlowExecution exec;
        try {
            exec = owner.get();
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
            // Broken flow, cannot display anything.
            return Collections.emptyList();
        }
        List<WorkspaceActionImpl> r = new ArrayList<>();
        for (FlowNode node : new DepthFirstScanner().allNodes(exec)) {
            r.addAll(node.getActions(WorkspaceActionImpl.class));
        }
        Collections.reverse(r);
        return r;
    }

    @Extension public static final class Factory extends TransientActionFactory<FlowExecutionOwner.Executable> {

        @Override public Class<FlowExecutionOwner.Executable> type() {
            return FlowExecutionOwner.Executable.class;
        }

        @Override public Collection<? extends Action> createFor(FlowExecutionOwner.Executable target) {
            FlowExecutionOwner owner = target.asFlowExecutionOwner();
            if (owner == null) {
                return Collections.emptySet();
            }
            return Collections.singleton(new WorkspaceRunAction(target, owner));
        }

    }

}
