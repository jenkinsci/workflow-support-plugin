package org.jenkinsci.plugins.workflow.support.pickles.serialization;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

import java.util.List;

/**
 * Tests deserializing program.dat, useful to catch
 */
public class DeserializeUpdate {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule
    public JenkinsRule r = new JenkinsRule();

    private static InputStepExecution getInputStepExecution(WorkflowRun run, String inputMessage) throws Exception {
        InputAction ia = run.getAction(InputAction.class);
        List<InputStepExecution> execList = ia.getExecutions();
        return execList.stream().filter(e -> inputMessage.equals(e.getInput().getMessage())).findFirst().orElse(null);
    }

    @Test
    @LocalData
    public void testBasicSerializeDeserialize() throws Exception {
        WorkflowJob job = Jenkins.getInstance().getItemByFullName("serial-format", WorkflowJob.class);
        WorkflowRun run = job.getLastBuild();
        Assert.assertTrue(run.isBuilding());
        InputStepExecution ise = getInputStepExecution(run, "give me cheese");
        ise.doProceedEmpty();
        r.waitForCompletion(run);
        r.assertBuildStatusSuccess(run);
    }
}
