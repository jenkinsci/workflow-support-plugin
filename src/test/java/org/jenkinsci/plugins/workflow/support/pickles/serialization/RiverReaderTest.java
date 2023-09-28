/*
 * The MIT License
 *
 * Copyright 2023 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.pickles.serialization;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;

import hudson.model.Result;
import java.util.logging.Level;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsSessionRule;
import org.jvnet.hudson.test.LoggerRule;

public final class RiverReaderTest {

    @Rule public final JenkinsSessionRule rr = new JenkinsSessionRule();
    @Rule public final LoggerRule logging = new LoggerRule().record(RiverReader.class, Level.FINE).capture(2000);
    @ClassRule public static final BuildWatcher bw = new BuildWatcher();

    @Test public void stackOverflow() throws Throwable {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("class R {Object f}; def x = new R(); for (int i = 0; i < 1000; i++) {def y = new R(); y.f = x; x = y}; semaphore 'wait'", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).getStartCondition().get());
        });
        rr.then(r -> {
            WorkflowRun b = r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            SemaphoreStep.success("wait/1", null);
            r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b));
            r.assertLogContains("java.lang.StackOverflowError", b);
            r.assertLogContains("at org.jboss.marshalling.river.RiverUnmarshaller.doReadNewObject", b);
            assertThat(logging.getMessages(), hasItem("StackOverflowError trace:                                            R"));
        });
    }

}
