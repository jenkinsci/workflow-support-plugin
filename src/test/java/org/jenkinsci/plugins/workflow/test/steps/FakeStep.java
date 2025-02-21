package org.jenkinsci.plugins.workflow.test.steps;

import java.io.Serializable;
import java.util.Set;

import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.model.TaskListener;

public class FakeStep extends Step implements Serializable {

    private final int sleepSeconds;

    @DataBoundConstructor
    public FakeStep(int sleepSeconds) {
        this.sleepSeconds = sleepSeconds;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new FakeExecution(context, this);
    }

    public static final class FakeExecution extends SynchronousNonBlockingStepExecution {

        private static final long serialVersionUID = 1L;
        private final FakeStep step;

        FakeExecution(StepContext context, FakeStep step) {
            super(context);
            this.step = step;
        }

        @Override
        protected Object run() throws Exception {
            var buildLogger = getContext().get(TaskListener.class).getLogger();
            buildLogger.println("fake step started");
            buildLogger.println("going to sleep for " + step.sleepSeconds + " seconds");
            Thread.sleep(step.sleepSeconds * 1000L);
            buildLogger.println("fake step completed");
            return null;
        }
    }

    @Extension
    public static final class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of();
        }

        @Override
        public String getFunctionName() {
            return "fake";
        }

        @Override
        public String getDisplayName() {
            return "Fake step";
        }
    }
}
