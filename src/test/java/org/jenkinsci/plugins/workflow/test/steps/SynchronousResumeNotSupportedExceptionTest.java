package org.jenkinsci.plugins.workflow.test.steps;

import java.util.Set;
import java.util.logging.Logger;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.StepExecutions;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.PrefixedOutputStream;
import org.jvnet.hudson.test.RealJenkinsRule;
import org.jvnet.hudson.test.TailLog;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.model.TaskListener;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

public class SynchronousResumeNotSupportedExceptionTest {
    private static final Logger LOGGER = Logger.getLogger(SynchronousResumeNotSupportedExceptionTest.class.getName());

    @Rule
    public RealJenkinsRule rjr = new RealJenkinsRule();

    @Test
    public void test_SynchronousResumeNotSupportedException_ShouldSuggestRetry() throws Throwable {
        rjr.startJenkins();
        rjr.runRemotely(j -> {
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("simulatedSleep 20", true));
            try(var tailLog = new TailLog(j, "p", 1).withColor(PrefixedOutputStream.Color.YELLOW)) {
                var run = p.scheduleBuild2(0).waitForStart();
                j.waitForMessage("Going to sleep for", run);
            }
        });

        LOGGER.info("Restarting jenkins forcibly");
        rjr.stopJenkinsForcibly();
        rjr.startJenkins();
        LOGGER.info("Jenkins has restarted");

        rjr.runRemotely(j -> {
            var b = j.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            var w = new FlowGraphWalker(b.getExecution());
            Throwable error = null;
            for (FlowNode n: w) {
                if (n.getError() != null) {
                    error = n.getError().getError();
                }
            }
            assertThat(error.getMessage(), allOf(
                containsString("nonresumable"),
                containsString("retry"),
                containsString("simulatedSleep"),
                containsString("retires")));
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
            return StepExecutions.synchronous(context, c -> {
                var buildLogger = context.get(TaskListener.class).getLogger();
                buildLogger.println("Simulated sleep step started");
                buildLogger.println("Going to sleep for " + sleepSeconds + " seconds");
                Thread.sleep(sleepSeconds * 1000L);
                buildLogger.println("Simulated sleep step completed");
                return null;
            });
        }

        @TestExtension
        public static final class DescriptorImpl extends StepDescriptor {

            @Override
            public Set<? extends Class<?>> getRequiredContext() {
                return Set.of();
            }

            @Override
            public String getFunctionName() {
                return "simulatedSleep";
            }
        }
    }
}
