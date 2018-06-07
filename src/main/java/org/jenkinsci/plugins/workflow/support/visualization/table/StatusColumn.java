package org.jenkinsci.plugins.workflow.support.visualization.table;

import hudson.Extension;
import org.jenkinsci.plugins.workflow.visualization.table.FlowNodeViewColumn;
import org.jenkinsci.plugins.workflow.visualization.table.FlowNodeViewColumnDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Kohsuke Kawaguchi
 */
public class StatusColumn extends FlowNodeViewColumn {
    @DataBoundConstructor
    public StatusColumn() {
    }

    @Extension
    public static class DescriptorImpl extends FlowNodeViewColumnDescriptor {
        @Override
        public FlowNodeViewColumn getDefaultInstance() {
            return new StatusColumn();
        }

        @Override
        public String getDisplayName() {
            return "Status";
        }
    }
}
