/*
 * The MIT License
 *
 * Copyright 2020 CloudBees, Inc.
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
package org.jenkinsci.plugins.workflow.support.visualization.table;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

public class FlowGraphTableTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void smokes() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                "echo('Hello, world!')\n" +
                "timeout(time: 1, unit: 'MINUTES') {\n" +
                "  echo('Hello again, world!')\n" +
                "}\n" +
                "echo('Goodbye, world!')\n", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        FlowGraphTable t = new FlowGraphTable(b.getExecution());
        t.build();
        // Flow start, timeout step and body start, 3 echo steps.
        assertThat(t.getRows(), hasSize(6));
    }

    @Test
    public void parallelSmokes() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
                "echo('Hello, world!')\n" +
                "parallel(one: {\n" +
                "  timeout(time: 1, unit: 'MINUTES') {\n" +
                "    echo('Hello, branch one!')\n" +
                "  }\n" +
                "}, two: {\n" +
                "  timeout(time: 1, unit: 'MINUTES') {\n" +
                "    echo('Hello, branch two!')\n" +
                "  }\n" +
                "})\n" +
                "echo('Goodbye, world!')\n", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        FlowGraphTable t = new FlowGraphTable(b.getExecution());
        t.build();
        // Flow start, parallel step start, 2 parallel branch starts, 2 timeout step starts, 2 timeout body starts, 4 echo steps.
        assertThat(t.getRows(), hasSize(12));
    }

    @Issue("JENKINS-62545")
    @LocalData // There is no known way to reproduce the issue from scratch, so we use a fake build with redacted flow node XML files from a real build that had the problem.
    @Test
    public void corruptedFlowGraph() throws Exception {
        WorkflowJob p = r.jenkins.getItemByFullName("test0", WorkflowJob.class);
        WorkflowRun b = p.getBuildByNumber(1);
        FlowGraphTable t = new FlowGraphTable(b.getExecution());
        try {
            t.build();
            fail("Table construction should fail");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), equalTo("Saw StepStartNode[id=199, exec=CpsFlowExecution[Owner[test0/1:test0 #1]]] twice when finding siblings of StepStartNode[id=199, exec=CpsFlowExecution[Owner[test0/1:test0 #1]]]"));
        }
    }

    @Test
    public void rowDisplayName() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition(
            "stage('start') {\n" +
            "  echo 'some message'\n" +
            "  node {\n" +
            "    isUnix()\n" +
            "  }\n" +
            "}\n" +
            "stage('main') {\n" +
            "  parallel quick: {\n" +
            "    echo 'done'\n" +
            "  }, slow: {\n" +
            "    semaphore 'wait'\n" +
            "  }\n" +
            "}", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);
        FlowGraphTable t = new FlowGraphTable(b.getExecution());
        t.build();
        SemaphoreStep.success("wait/1", null);
        assertThat(t.getRows().stream().map(FlowGraphTable.Row::getDisplayName).toArray(String[]::new), arrayContaining(
            "Start of Pipeline",
            "stage",
            "stage block (start)",
            "echo",
            "node",
            "node block",
            "isUnix",
            "stage",
            "stage block (main)",
            "parallel",
            "parallel block (Branch: quick)",
            "echo",
            "parallel block (Branch: slow)",
            "semaphore"));
    }

}
