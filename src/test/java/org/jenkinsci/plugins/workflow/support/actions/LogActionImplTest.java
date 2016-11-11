/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.actions;

import com.google.inject.Inject;
import hudson.EnvVars;
import hudson.model.TaskListener;
import java.util.LinkedList;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.AbstractStepDescriptorImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.AbstractStepImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;

public class LogActionImplTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsRule r = new JenkinsRule();

    @Test public void logsAndBlocks() throws Exception {
        WorkflowJob p = r.createProject(WorkflowJob.class);
        p.setDefinition(new CpsFlowDefinition("parallel a: {chatty('LBBL') {echo(/atom step in A with $remaining commands to go/)}}, b: {chatty('BL') {echo(/atom step in B with $remaining commands to go/)}}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("logging from LBBL with 3 commands to go", b);
        r.assertLogContains("atom step in A with 2 commands to go", b);
        r.assertLogContains("atom step in A with 1 commands to go", b);
        r.assertLogContains("logging from LBBL with 0 commands to go", b);
        r.assertLogContains("atom step in B with 1 commands to go", b);
        /* TODO misuse of FlowNode.isRunning in WorkflowRun.copyLogs prevents this from appearing unless we access TaskListener from start(); should be fixed by JENKINS-38381:
        r.assertLogContains("logging from BL with 0 commands to go", b);
        */
    }
    public static class ChattyStep extends AbstractStepImpl {
        public final String pattern;
        @DataBoundConstructor public ChattyStep(String pattern) {this.pattern = pattern;}
        @TestExtension("logsAndBlocks") public static class DescriptorImpl extends AbstractStepDescriptorImpl {
            public DescriptorImpl() {
                super(Execution.class);
            }
            @Override public String getFunctionName() {return "chatty";}
            @Override public boolean takesImplicitBlockArgument() {return true;}
        }
        public static class Execution extends AbstractStepExecutionImpl {
            @Inject(optional=true) transient ChattyStep step;
            String pattern;
            LinkedList<Boolean> commands; // L ~ false to log, B ~ true to run block
            @Override public boolean start() throws Exception {
                commands = new LinkedList<>();
                pattern = step.pattern;
                for (char c : pattern.toCharArray()) {
                    if (c == 'L') {
                        commands.add(false);
                    } else {
                        assert c == 'B';
                        commands.add(true);
                    }
                }
                run();
                return false;
            }
            private void run() throws Exception {
                StepContext context = getContext();
                if (commands.isEmpty()) {
                    context.onSuccess(null);
                } else if (commands.pop()) {
                    context.newBodyInvoker().withCallback(new Callback()).withContext(new EnvVars("remaining", Integer.toString(commands.size()))).start();
                } else {
                    context.get(TaskListener.class).getLogger().println("logging from " + pattern + " with " + commands.size() + " commands to go");
                    run();
                }
            }
            @Override public void stop(Throwable cause) throws Exception {}
            private final class Callback extends BodyExecutionCallback {
                @Override public void onSuccess(StepContext context, Object result) {
                    try {
                        run();
                    } catch (Exception x) {
                        context.onFailure(x);
                    }
                }
                @Override public void onFailure(StepContext context, Throwable t) {
                    context.onFailure(t);
                }
            }
        }
    }

}
