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
import org.junit.ClassRule;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Ignore;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.For;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

@Issue("SECURITY-699")
public class SerializationSecurityTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule public RestartableJenkinsRule rr = new RestartableJenkinsRule();
    
    /** @see SerializableClass#callWriteObject */
    @For(RiverWriter.class)
    @Test public void writeObjectChecksSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Hack {\n" +
                "  @NonCPS private void writeObject(ObjectOutputStream out) {\n" +
                "    Jenkins.instance.systemMessage = 'oops'\n" +
                "  }\n" +
                "}\n" +
                "def hack = new Hack()\n" +
                "sleep 1\n" +
                "echo(/should not still have $hack/)", true));
            safe(r, p.scheduleBuild2(0).get());
        });
    }

    /** @see SerializableClass#callWriteReplace */
    @For(RiverWriter.class)
    @Test public void writeReplaceChecksSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Hack {\n" +
                "  @NonCPS private Object writeReplace() {\n" +
                "    Jenkins.instance.systemMessage = 'oops'\n" +
                "    this\n" +
                "  }\n" +
                "}\n" +
                "def hack = new Hack()\n" +
                "sleep 1\n" +
                "echo(/should not still have $hack/)", true));
            safe(r, p.scheduleBuild2(0).get());
        });
    }

    /** @see RiverMarshaller#doWriteObject */
    @For(RiverWriter.class)
    @Test public void writeExternalChecksSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Hack implements Externalizable {\n" +
                "  @NonCPS void writeExternal(ObjectOutput out) {\n" +
                "    Jenkins.instance.systemMessage = 'oops'\n" +
                "  }\n" +
                "  @NonCPS void readExternal(ObjectInput inp) {}\n" +
                "}\n" +
                "def hack = new Hack()\n" +
                "sleep 1\n" +
                "echo(/should not still have $hack/)", true));
            safe(r, p.scheduleBuild2(0).get());
        });
    }

    /** @see SerializableClass#callReadObject */
    @For(RiverReader.class)
    @Test public void readObjectChecksSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Hack {\n" +
                "  @NonCPS private void readObject(ObjectInputStream inp) {\n" +
                "    Jenkins.instance.systemMessage = 'oops'\n" +
                "  }\n" +
                "}\n" +
                "def hack = new Hack()\n" +
                "semaphore 'wait'\n" +
                "echo(/should not still have $hack/)", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        rr.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
        // doubtful readObjectNoData could even be called under these circumstances, since the script is saved between restarts
    }

    /** @see SerializableClass#callReadResolve */
    @For(RiverReader.class)
    @Test public void readResolveChecksSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Hack {\n" +
                "  @NonCPS private Object readResolve() {\n" +
                "    Jenkins.instance.systemMessage = 'oops'\n" +
                "  }\n" +
                "}\n" +
                "def hack = new Hack()\n" +
                "semaphore 'wait'\n" +
                "echo(/should not still have $hack/)", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        rr.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
    }

    /** @see RiverUnmarshaller#doReadNewObject */
    @For(RiverReader.class)
    @Test public void readExternalChecksSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Hack implements Externalizable {\n" +
                "  @NonCPS void writeExternal(ObjectOutput out) {}\n" +
                "  @NonCPS void readExternal(ObjectInput inp) {\n" +
                "    Jenkins.instance.systemMessage = 'oops'\n" +
                "  }\n" +
                "}\n" +
                "def hack = new Hack()\n" +
                "semaphore 'wait'\n" +
                "echo(/should not still have $hack/)", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        rr.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
    }

    /** @see SerializableClass#callNoArgConstructor */
    @For(RiverReader.class)
    @Test public void externalizableNoArgConstructorChecksSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Hack implements Externalizable {\n" +
                "  Hack(boolean x) {}\n" +
                "  Hack() {" +
                "    Jenkins.instance.systemMessage = 'oops'\n" +
                "  }\n" +
                "  @NonCPS void writeExternal(ObjectOutput out) {}\n" +
                "  @NonCPS void readExternal(ObjectInput inp) {}\n" +
                "}\n" +
                "def hack = new Hack(true)\n" +
                "semaphore 'wait'\n" +
                "echo(/should not still have $hack/)", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        rr.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
    }

    /**
     * Seems to be an undocumented (?) special feature of JBoss Marshalling.
     * @see SerializableClass#callObjectInputConstructor
     */
    @For(RiverReader.class)
    @Test public void externalizableObjectInputConstructorChecksSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Hack implements Externalizable {\n" +
                "  Hack() {}\n" +
                "  Hack(ObjectInput inp) {" +
                "    Jenkins.instance.systemMessage = 'oops'\n" +
                "  }\n" +
                "  @NonCPS void writeExternal(ObjectOutput out) {}\n" +
                "  @NonCPS void readExternal(ObjectInput inp) {}\n" +
                "}\n" +
                "def hack = new Hack()\n" +
                "semaphore 'wait'\n" +
                "echo(/should not still have $hack/)", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        rr.then(r -> {
            SemaphoreStep.success("wait/1", null);
            safe(r, r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1)));
        });
    }

    @Ignore("does not currently work (fails on `new Replacer`), since CpsWhitelist & GroovyClassLoaderWhitelist are not in .all()")
    @Test public void harmlessCallsPassSandbox() {
        rr.then(r -> {
            WorkflowJob p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition(
                "class Fine {\n" +
                "  @NonCPS private Object writeReplace() {\n" +
                "    new Replacer()\n" +
                "  }\n" +
                "}\n" +
                "class Replacer {\n" +
                "  @NonCPS private Object readResolve() {\n" +
                "    'something safe'\n" +
                "  }\n" +
                "}\n" +
                "def fine = new Fine()\n" +
                "semaphore 'wait'\n" +
                "echo(/but we do have $fine/)", true));
            SemaphoreStep.waitForStart("wait/1", p.scheduleBuild2(0).waitForStart());
        });
        rr.then(r -> {
            SemaphoreStep.success("wait/1", null);
            r.assertLogContains("but we do have something safe", r.assertBuildStatusSuccess(r.waitForCompletion(r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1))));
        });
    }

    private static void safe(JenkinsRule r, WorkflowRun b) throws Exception {
        assertNull(r.jenkins.getSystemMessage());
        assertEquals(Result.FAILURE, b.getResult());
        r.waitForMessage("staticMethod jenkins.model.Jenkins getInstance", b);
        r.assertLogNotContains("should not still have", b);
    }

}
