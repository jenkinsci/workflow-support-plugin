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

package org.jenkinsci.plugins.workflow.support.pickles.serialization;

import hudson.model.Result;
import org.jboss.marshalling.reflect.SerializableClass;
import org.jboss.marshalling.river.RiverMarshaller;
import org.jboss.marshalling.river.RiverUnmarshaller;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.For;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.BuildWatcherExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

@Issue("SECURITY-699")
class SerializationSecurityTest {

    @SuppressWarnings("unused")
    @RegisterExtension
    private static final BuildWatcherExtension BUILD_WATCHER = new BuildWatcherExtension();

    @RegisterExtension
    private final JenkinsSessionExtension sessions = new JenkinsSessionExtension();

    /** @see SerializableClass#callWriteObject */
    @For(RiverWriter.class)
    @Test
    void writeObjectChecksSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Hack {
                              @NonCPS private void writeObject(ObjectOutputStream out) {
                                Jenkins.instance.systemMessage = 'oops'
                              }
                            }
                            def hack = new Hack()
                            sleep 1
                            echo(/should not still have $hack/)""", true));
            safe(r, p.scheduleBuild2(0).get());
        });
    }

    /** @see SerializableClass#callWriteReplace */
    @For(RiverWriter.class)
    @Test
    void writeReplaceChecksSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Hack {
                              @NonCPS private Object writeReplace() {
                                Jenkins.instance.systemMessage = 'oops'
                                this
                              }
                            }
                            def hack = new Hack()
                            sleep 1
                            echo(/should not still have $hack/)""", true));
            safe(r, p.scheduleBuild2(0).get());
        });
    }

    /** @see RiverMarshaller#doWriteObject */
    @For(RiverWriter.class)
    @Test
    void writeExternalChecksSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Hack implements Externalizable {
                              @NonCPS void writeExternal(ObjectOutput out) {
                                Jenkins.instance.systemMessage = 'oops'
                              }
                              @NonCPS void readExternal(ObjectInput inp) {}
                            }
                            def hack = new Hack()
                            sleep 1
                            echo(/should not still have $hack/)""", true));
            safe(r, p.scheduleBuild2(0).get());
        });
    }

    /** @see SerializableClass#callReadObject */
    @For(RiverReader.class)
    @Test
    void readObjectChecksSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Hack {
                              @NonCPS private void readObject(ObjectInputStream inp) {
                                Jenkins.instance.systemMessage = 'oops'
                              }
                            }
                            def hack = new Hack()
                            semaphore 'wait'
                            echo(/should not still have $hack/)""", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        sessions.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
        // doubtful readObjectNoData could even be called under these circumstances, since the script is saved between restarts
    }

    /** @see SerializableClass#callReadResolve */
    @For(RiverReader.class)
    @Test
    void readResolveChecksSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Hack {
                              @NonCPS private Object readResolve() {
                                Jenkins.instance.systemMessage = 'oops'
                              }
                            }
                            def hack = new Hack()
                            semaphore 'wait'
                            echo(/should not still have $hack/)""", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        sessions.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
    }

    /** @see RiverUnmarshaller#doReadNewObject */
    @For(RiverReader.class)
    @Test
    void readExternalChecksSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Hack implements Externalizable {
                              @NonCPS void writeExternal(ObjectOutput out) {}
                              @NonCPS void readExternal(ObjectInput inp) {
                                Jenkins.instance.systemMessage = 'oops'
                              }
                            }
                            def hack = new Hack()
                            semaphore 'wait'
                            echo(/should not still have $hack/)""", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        sessions.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
    }

    /** @see SerializableClass#callNoArgConstructor */
    @For(RiverReader.class)
    @Test
    void externalizableNoArgConstructorChecksSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Hack implements Externalizable {
                              Hack(boolean x) {}
                              Hack() {\
                                Jenkins.instance.systemMessage = 'oops'
                              }
                              @NonCPS void writeExternal(ObjectOutput out) {}
                              @NonCPS void readExternal(ObjectInput inp) {}
                            }
                            def hack = new Hack(true)
                            semaphore 'wait'
                            echo(/should not still have $hack/)""", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        sessions.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
    }

    /**
     * Seems to be an undocumented (?) special feature of JBoss Marshalling.
     * @see SerializableClass#callObjectInputConstructor
     */
    @For(RiverReader.class)
    @Test
    void externalizableObjectInputConstructorChecksSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Hack implements Externalizable {
                              Hack() {}
                              Hack(ObjectInput inp) {\
                                Jenkins.instance.systemMessage = 'oops'
                              }
                              @NonCPS void writeExternal(ObjectOutput out) {}
                              @NonCPS void readExternal(ObjectInput inp) {}
                            }
                            def hack = new Hack()
                            semaphore 'wait'
                            echo(/should not still have $hack/)""", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        sessions.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
    }

    @Disabled("does not currently work (fails on `new Replacer`), since CpsWhitelist & GroovyClassLoaderWhitelist are not in .all()")
    @Test
    void harmlessCallsPassSandbox() throws Throwable {
        sessions.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                    """
                            class Fine {
                              @NonCPS private Object writeReplace() {
                                new Replacer()
                              }
                            }
                            class Replacer {
                              @NonCPS private Object readResolve() {
                                'something safe'
                              }
                            }
                            def fine = new Fine()
                            semaphore 'wait'
                            echo(/but we do have $fine/)""", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        sessions.then(r -> {
            SemaphoreStep.success("wait/1", null);
            r.assertLogContains("but we do have something safe", r.assertBuildStatusSuccess(r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1))));
        });
    }

    private static void safe(JenkinsRule r, WorkflowRun b) throws Exception {
        assertNull(r.jenkins.getSystemMessage());
        assertEquals(Result.FAILURE, b.getResult());
        r.assertLogNotContains("should not still have", b);
        r.assertLogContains("staticMethod jenkins.model.Jenkins getInstance", b);
    }
}
