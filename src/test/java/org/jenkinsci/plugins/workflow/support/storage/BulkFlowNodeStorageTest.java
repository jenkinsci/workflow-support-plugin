/*
 * The MIT License
 *
 * Copyright 2024 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.storage;

import hudson.model.Result;
import hudson.util.RobustReflectionConverter;
import java.io.File;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.recipes.LocalData;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

public final class BulkFlowNodeStorageTest {

    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public LoggerRule logger = new LoggerRule().record(RobustReflectionConverter.class, Level.FINE).capture(50);

    @Test public void orderOfEntries() throws Exception {
        var p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("for (int i = 1; i <= 40; i++) {echo(/step #$i/)}", true));
        var b = r.buildAndAssertSuccess(p);
        var entryList = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(b.getRootDir(), "workflow-completed/flowNodeStore.xml")).getDocumentElement().getChildNodes();
        var ids = new ArrayList<String>();
        for (int i = 0; i < entryList.getLength(); i++) {
            var entry = entryList.item(i);
            if (entry.getNodeType() == Node.ELEMENT_NODE) {
                ids.add(((Element) entry).getElementsByTagName("*").item(0).getTextContent());
            }
        }
        assertThat(ids, is(IntStream.rangeClosed(2, 43).mapToObj(Integer::toString).collect(Collectors.toList())));
    }

    @LocalData
    @Test public void actionDeserializationShouldBeRobust() throws Exception {
        /*
        var p = r.createProject(WorkflowJob.class);
        p.addProperty(new DurabilityHintJobProperty(FlowDurabilityHint.PERFORMANCE_OPTIMIZED));
        p.setDefinition(new CpsFlowDefinition(
                "stage('test') {\n" +
                "  semaphore('wait')\n" +
                "}\n", true));
        var b = p.scheduleBuild2(0).waitForStart();
        SemaphoreStep.waitForStart("wait/1", b);
        ((CpsFlowExecution) b.getExecution()).getStorage().flush();
        Thread.sleep(30*1000);
        */
        var p = r.jenkins.getItemByFullName("test0", WorkflowJob.class);
        var b = p.getLastBuild();
        // Build is unresumable because the local data was created with PERFORMANCE_OPTIMIZED without a clean shutdown.
        r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b));
        // Existing flow nodes should still be preserved though.
        var stageBodyStartNode = (StepStartNode) b.getExecution().getNode("4");
        assertThat(stageBodyStartNode, not(nullValue()));
        var label = stageBodyStartNode.getPersistentAction(LabelAction.class);
        assertThat(label.getDisplayName(), equalTo("test"));
    }
    // Used to create @LocalData for above test:
    /*
    public static class MyAction extends InvisibleAction implements PersistentAction {
        private final String value = "42";
    }
    @TestExtension("actionDeserializationShouldBeRobust")
    public static class MyActionAdder implements GraphListener.Synchronous {
        @Override
        public void onNewHead(FlowNode node) {
            node.addAction(new MyAction());
        }
    }*/
}
