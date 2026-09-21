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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

class PickleResolverTest {
    
    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    private long resetPickleResolutionTimeout;

    @BeforeEach
    void beforeEach() {
        resetPickleResolutionTimeout = PickleResolver.RESOLUTION_TIMEOUT_SECONDS;
    }

    @AfterEach
    void afterEach() {
        PickleResolver.RESOLUTION_TIMEOUT_SECONDS =  resetPickleResolutionTimeout;
    }

    @Test
    void timeout() throws Throwable {
        sessions.then(r -> {
            var p = r.jenkins.createProject(WorkflowJob.class, "stuckPickle");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            def x = new org.jenkinsci.plugins.workflow.support.pickles.serialization.PickleResolverTest.StuckPickle.Marker()
                            semaphore 'wait'
                            echo x.getClass().getName()""", false));
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

    @SuppressWarnings("unused")
    public static class StuckPickle extends Pickle {

        @Override
        public ListenableFuture<Marker> rehydrate(FlowExecutionOwner owner) {
            return new TryRepeatedly<>(1) {

                @Override
                protected Marker tryResolve() {
                    return null;
                }

                @Override
                protected FlowExecutionOwner getOwner() {
                    return owner;
                }

                @Override
                public String toString() {
                    return "StuckPickle for " + owner;
                }
            };
        }

        public static class Marker {}

        @TestExtension("timeout")
        public static final class Factory extends SingleTypedPickleFactory<Marker> {

            @Override
            protected Pickle pickle(Marker object) {
                return new StuckPickle();
            }
        }
    }
}
