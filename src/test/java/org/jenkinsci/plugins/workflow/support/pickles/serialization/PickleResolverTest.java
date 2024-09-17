package org.jenkinsci.plugins.workflow.support.pickles.serialization;

import com.google.common.util.concurrent.ListenableFuture;
import hudson.model.Result;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.pickles.Pickle;
import org.jenkinsci.plugins.workflow.support.pickles.SingleTypedPickleFactory;
import org.jenkinsci.plugins.workflow.support.pickles.TryRepeatedly;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.FlagRule;
import org.jvnet.hudson.test.JenkinsSessionRule;
import org.jvnet.hudson.test.TestExtension;

public class PickleResolverTest {
    @ClassRule
    public static BuildWatcher watcher = new BuildWatcher();

    @Rule
    public JenkinsSessionRule sessions = new JenkinsSessionRule();

    @Rule
    public FlagRule<Long> resetPickleResolutionTimeout = new FlagRule<>(() -> PickleResolver.RESOLUTION_TIMEOUT_SECONDS, x -> PickleResolver.RESOLUTION_TIMEOUT_SECONDS = x);

    @Test
    public void timeout() throws Throwable {
        sessions.then(r -> {
            var p = r.jenkins.createProject(WorkflowJob.class, "stuckPickle");
            p.setDefinition(new CpsFlowDefinition(
                    "def x = new org.jenkinsci.plugins.workflow.support.pickles.serialization.PickleResolverTest.StuckPickle.Marker()\n" +
                    "semaphore 'wait'\n" +
                    "echo x.getClass().getName()", false));
            var b = p.scheduleBuild2(0).waitForStart();
            SemaphoreStep.waitForStart("wait/1", b);
        });
        PickleResolver.RESOLUTION_TIMEOUT_SECONDS = 3;
        sessions.then(r -> {
            var p = r.jenkins.getItemByFullName("stuckPickle", WorkflowJob.class);
            var b = p.getBuildByNumber(1);
            r.assertBuildStatus(Result.FAILURE, r.waitForCompletion(b));
            r.assertLogContains("Timed out: StuckPickle", b);
        });
    }

    public static class StuckPickle extends Pickle {
        @Override
        public ListenableFuture<Marker> rehydrate(FlowExecutionOwner owner) {
            return new TryRepeatedly<Marker>(1) {
                @Override
                protected Marker tryResolve() {
                    return null;
                }
                @Override protected FlowExecutionOwner getOwner() {
                    return owner;
                }
                @Override public String toString() {
                    return "StuckPickle for " + owner;
                }
            };
        }

        public static class Marker {}

        @TestExtension("timeout")
        public static final class Factory extends SingleTypedPickleFactory<Marker> {
            @Override protected Pickle pickle(Marker object) {
                return new StuckPickle();
            }
        }
    }
}
