/*
 * The MIT License
 *
 * Copyright 2019 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support;

import hudson.console.LineTransformationOutputStream;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Run;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.log.TaskListenerDecorator;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.DynamicContext;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

public class DynamicContextTest {

    private static final Logger LOGGER = Logger.getLogger(DynamicContextTest.class.getName());

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public JenkinsRule r = new JenkinsRule();

    @Rule public LoggerRule logging = new LoggerRule().record(DynamicContext.class, Level.FINE);

    @Test public void smokes() throws Exception {
        r.jenkins.setNumExecutors(1);
        WorkflowJob p = r.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
            "decorate(message: 'outer') {\n" +
            "  echo 'one'\n" +
            "  decorate {\n" +
            "    echo 'two'\n" +
            "    node {\n" +
            "      echo 'three'\n" +
            "      decorate(message: 'inner') {\n" +
            "        echo 'four'\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}", true));
        WorkflowRun b = r.buildAndAssertSuccess(p);
        r.assertLogContains("[outer] one", b);
        r.assertLogNotContains("] [outer] one", b);
        r.assertLogContains("[outer] two", b);
        r.assertLogNotContains("] [outer] two", b);
        r.assertLogContains("[p #1 1/1] [outer] three", b);
        r.assertLogNotContains("] [p #1 1/1] [outer] three", b);
        r.assertLogContains("[inner] [p #1 1/1] [outer] four", b);
        r.assertLogNotContains("] [inner] [p #1 1/1] [outer] four", b);
    }
    private static final class DecoratorImpl extends TaskListenerDecorator {
        private final String message;
        DecoratorImpl(String message) {
            this.message = message;
        }
        @Override public OutputStream decorate(OutputStream logger) throws IOException, InterruptedException {
            return new LineTransformationOutputStream() {
                final String prefix = "[" + message + "] ";
                @Override protected void eol(byte[] b, int len) throws IOException {
                    if (new String(b, 0, len, StandardCharsets.UTF_8).contains(prefix)) {
                        // See caveat in DynamicContext about idempotency.
                    } else {
                        logger.write(prefix.getBytes());
                    }
                    logger.write(b, 0, len);
                }
                @Override public void close() throws IOException {
                    super.close();
                    logger.close();
                }
                @Override public void flush() throws IOException {
                    logger.flush();
                }
            };
        }
        @Override public String toString() {
            return "DecoratorImpl[" + message + "]";
        }
    }
    private static final class DecoratorContext extends DynamicContext.Typed<TaskListenerDecorator> {
        @Override protected Class<TaskListenerDecorator> type() {
            return TaskListenerDecorator.class;
        }
        @Override protected TaskListenerDecorator get(DelegatedContext context) throws IOException, InterruptedException {
            // Exercising lookup of something which is special-cased in DefaultStepContext.get.
            Run<?, ?> build = context.get(Run.class);
            assertNotNull(build);
            // PlaceholderExecutable defines Computer and again DefaultStepContext translates that to Node.
            Node node = context.get(Node.class);
            if (node == null) {
                return null;
            }
            // Literally in the context.
            Executor exec = context.get(Executor.class);
            if (exec == null) {
                return null;
            }
            String message = build + " " + (exec.getNumber() + 1) + "/" + node.getNumExecutors();
            // Recursive lookup of object of same type from an enclosing scope.
            TaskListenerDecorator original = context.get(TaskListenerDecorator.class);
            DecoratorImpl subsequent = new DecoratorImpl(message);
            LOGGER.log(Level.INFO, "merging {0} with {1}", new Object[] {original, subsequent});
            return TaskListenerDecorator.merge(original, subsequent);
        }
        @Override public String toString() {
            return "DecoratorContext";
        }
    }
    public static final class DecoratorStep extends Step {
        @DataBoundConstructor public DecoratorStep() {}
        @DataBoundSetter public @CheckForNull String message;
        @Override public StepExecution start(StepContext context) throws Exception {
            return new Execution(context, message);
        }
        private static final class Execution extends StepExecution {
            private final @CheckForNull String message;
            Execution(StepContext context, @CheckForNull String message) {
                super(context);
                this.message = message;
            }
            @Override public boolean start() throws Exception {
                BodyInvoker invoker = getContext().newBodyInvoker();
                if (message != null) {
                    TaskListenerDecorator original = getContext().get(TaskListenerDecorator.class);
                    DecoratorImpl subsequent = new DecoratorImpl(message);
                    LOGGER.log(Level.INFO, "merging {0} with {1}", new Object[] {original, subsequent});
                    invoker.withContext(TaskListenerDecorator.merge(original, subsequent));
                } else {
                    DynamicContext original = getContext().get(DynamicContext.class);
                    DecoratorContext subsequent = new DecoratorContext();
                    LOGGER.log(Level.INFO, "merging {0} with {1}", new Object[] {original, subsequent});
                    invoker.withContext(DynamicContext.merge(original, subsequent));
                }
                invoker.withCallback(BodyExecutionCallback.wrap(getContext())).start();
                return false;
            }
        }
        @TestExtension public static final class DescriptorImpl extends StepDescriptor {
            @Override public Set<? extends Class<?>> getRequiredContext() {
                return Collections.emptySet();
            }
            @Override public String getFunctionName() {
                return "decorate";
            }
            @Override public boolean takesImplicitBlockArgument() {
                return true;
            }
        }
    }

}
