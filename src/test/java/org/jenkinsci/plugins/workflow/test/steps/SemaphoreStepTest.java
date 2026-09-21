package org.jenkinsci.plugins.workflow.test.steps;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class SemaphoreStepTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    private JenkinsRule j;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void hardKill() throws Exception {
        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p");
        p1.setDefinition(new CpsFlowDefinition("echo 'locked!'; semaphore 'wait'"));
        WorkflowRun b = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);

        b.doKill();
        j.waitForMessage("Hard kill!", b);
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
    }
}
