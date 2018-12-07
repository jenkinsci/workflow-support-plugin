/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.pickles;

import hudson.remoting.ProxyException;
import java.util.logging.Level;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

public class ThrowablePickleTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule rr = new RestartableJenkinsRule();
    @Rule public LoggerRule logging = new LoggerRule().record(ThrowablePickle.class, Level.FINE);

    @Issue("JENKINS-51390")
    @Ignore("Still has problems with new JBoss Marshalling, temporarily disabled while debugging.")
    @Test public void smokes() throws Exception {
        String beName = BadException.class.getName();
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("try {throw new " + beName + "()} catch (x) {semaphore 'wait'; echo(/caught a $x/)}", true));
            WorkflowRun b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
        });
        rr.then(r -> {
            WorkflowRun b = r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            SemaphoreStep.success("wait/1", null);
            r.assertBuildStatusSuccess(r.waitForCompletion(b));
            r.assertLogContains("in field " + beName + ".notSerializable", b);
            r.assertLogContains("assignable to class " + beName, b);
            r.assertLogContains("caught a " + ProxyException.class.getName() + ": " + beName, b);
        });
    }

    public static class BadException extends Exception {

        private final Object notSerializable = new Object();

        @Whitelisted
        public BadException() {}

    }

}
