package org.jenkinsci.plugins.workflow.test.steps;

import java.util.Set;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.StepExecutions;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsSessionRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.model.TaskListener;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

public class SynchronousResumeNotSupportedExceptionTest {

    @ClassRule
    public static BuildWatcher bw = new BuildWatcher();

    @Rule
    public JenkinsSessionRule rjr = new JenkinsSessionRule();

    @Test
    public void test_SynchronousResumeNotSupportedException_ShouldShowFailedStep_AndSuggestRetry() throws Throwable {
        rjr.then(j -> {
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("simulatedSleep 20", true));
            var run = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("Going to sleep for", run);
        });
        rjr.then(j -> {
            var b = j.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            j.waitForCompletion(b);
            assertThat(b.getLog(), allOf(
                containsString("nonresumable"),
                containsString("retry"),
                containsString("simulatedSleep"),
                containsString("retries")));
        });
    }

    @SuppressWarnings("unused")
    public static class SimulatedSleepStep extends Step {

        private final int sleepSeconds;

        @DataBoundConstructor
        public SimulatedSleepStep(int sleepSeconds) {
            this.sleepSeconds = sleepSeconds;
        }

        @Override
        public StepExecution start(StepContext context) throws Exception {
            return StepExecutions.synchronousNonBlockingVoid(context, c -> {
                var buildLogger = context.get(TaskListener.class).getLogger();
                buildLogger.println("Simulated sleep step started");
                buildLogger.println("Going to sleep for " + sleepSeconds + " seconds");
                Thread.sleep(sleepSeconds * 1000L);
                buildLogger.println("Simulated sleep step completed");
            });
        }

        @TestExtension
        public static final class DescriptorImpl extends StepDescriptor {

            @Override
            public Set<? extends Class<?>> getRequiredContext() {
                return Set.of(TaskListener.class);
            }

            @Override
            public String getFunctionName() {
                return "simulatedSleep";
            }
        }
    }
}
