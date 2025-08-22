package org.jenkinsci.plugins.workflow.test.steps;

import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;

public class SemaphoreStepTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public BuildWatcher watcher = new BuildWatcher();

    @Test
    public void hardKill() throws Exception {
        WorkflowJob p1 = j.jenkins.createProject(WorkflowJob.class, "p");
        p1.setDefinition(new CpsFlowDefinition("echo 'locked!'; semaphore 'wait'"));
        WorkflowRun b = p1.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);

        b.doKill();
        j.waitForMessage("Hard kill!", b);
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));
    }
}
