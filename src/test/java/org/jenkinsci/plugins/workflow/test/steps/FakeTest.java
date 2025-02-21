package org.jenkinsci.plugins.workflow.test.steps;

import java.util.logging.Logger;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graph.FlowGraphWalker;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class FakeTest {

    @ClassRule
    public static BuildWatcher bw = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule rj = new RestartableJenkinsRule();

    private static final Logger LOGGER = Logger.getLogger(FakeTest.class.getName());

    @Test
    public void fakeStepExecutes() {
        rj.then(j -> {
            WorkflowJob p = j.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("fake 20", true));
            var run = p.scheduleBuild2(0).waitForStart();
            j.waitForMessage("fake", run);
            LOGGER.info("Jenkins will restart now");
        });

        rj.thenWithHardShutdown( j -> {
            LOGGER.info("Jenkins has restarted");
            var b = j.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            var w = new FlowGraphWalker(b.getExecution());
            Throwable error = null;
            for (FlowNode n: w) {
                if (n.getError() != null) {
                    error = n.getError().getError();
                }
            }
            assertThat("error is not null", error, notNullValue());
            assertThat(error.getMessage(), is("Resume after a restart not supported for non-blocking synchronous "
                                              + "steps, failed step is fake, you may wrap this block into a retry step with nonresumable condition"));
        });
    }
}
