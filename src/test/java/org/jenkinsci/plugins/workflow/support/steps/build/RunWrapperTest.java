/*
 * The MIT License
 *
 * Copyright 2015 Jesse Glick.
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

package org.jenkinsci.plugins.workflow.support.steps.build;

import hudson.model.Result;
import java.util.regex.Pattern;
import jenkins.plugins.git.GitSampleRepoRule;
import org.hamcrest.Matcher;
import org.hamcrest.core.SubstringMatcher;
import org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.junit.Assert.*;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.RestartableJenkinsRule;

@Issue("JENKINS-26834")
public class RunWrapperTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public RestartableJenkinsRule r = new RestartableJenkinsRule();
    @Rule public GitSampleRepoRule sampleRepo1 = new GitSampleRepoRule();
    @Rule public GitSampleRepoRule sampleRepo2 = new GitSampleRepoRule();

    @Test public void historyAndPickling() {
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                ScriptApproval.get().approveSignature("staticMethod org.codehaus.groovy.runtime.ScriptBytecodeAdapter compareNotEqual java.lang.Object java.lang.Object"); // TODO JENKINS-27390
                WorkflowJob p = r.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "def b0 = currentBuild\n" +
                    "for (b = b0; b != null; b = b.previousBuild) {\n" +
                    "  semaphore 'basics'\n" +
                    "  echo \"number=${b.number} result=${b.result}\"\n" +
                    "}", true));
                SemaphoreStep.success("basics/1", null);
                WorkflowRun b1 = r.j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());
                r.j.assertLogContains("number=1 result=null", b1);
                WorkflowRun b2 = p.scheduleBuild2(0).getStartCondition().get();
                SemaphoreStep.success("basics/2", null);
                SemaphoreStep.waitForStart("basics/3", b2);
                r.j.waitForMessage("number=2 result=null", b2);
                r.j.assertLogNotContains("number=1", b2);
            }
        });
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = r.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b2 = p.getBuildByNumber(2);
                SemaphoreStep.success("basics/3", b2);
                r.j.assertBuildStatusSuccess(r.j.waitForCompletion(b2));
                r.j.assertLogContains("number=1 result=SUCCESS", b2);
            }
        });
    }

    @Test public void updateSelf() {
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                ScriptApproval.get().approveSignature("staticMethod org.codehaus.groovy.runtime.ScriptBytecodeAdapter compareNotEqual java.lang.Object java.lang.Object"); // TODO JENKINS-27390
                WorkflowJob p = r.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "currentBuild.result = 'UNSTABLE'\n" +
                    "currentBuild.description = 'manipulated'\n" +
                    "currentBuild.displayName = 'special'\n" +
                    "def pb = currentBuild.previousBuild; if (pb != null) {pb.displayName = 'verboten'}", true));
                WorkflowRun b1 = r.j.assertBuildStatus(Result.UNSTABLE, p.scheduleBuild2(0).get());
                assertEquals("manipulated", b1.getDescription());
                assertEquals("special", b1.getDisplayName());
                WorkflowRun b2 = r.j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0).get());
                assertEquals(SecurityException.class, b2.getExecution().getCauseOfFailure().getClass());
                assertEquals("manipulated", b2.getDescription());
                assertEquals("special", b2.getDisplayName());
                assertEquals("special", b1.getDisplayName());
            }
        });
    }

    @Issue("JENKINS-30412")
    @Test public void getChangeSets() {
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                sampleRepo1.init();
                sampleRepo2.init();
                WorkflowJob p = r.j.jenkins.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "node {dir('1') {git($/" + sampleRepo1 + "/$)}; dir('2') {git($/" + sampleRepo2 + "/$)}}\n" +
                    "echo(/changeSets: ${summarize currentBuild}/)\n" +
                    "@NonCPS def summarize(b) {\n" +
                    "  b.changeSets.collect {cs ->\n" +
                    "    /kind=${cs.kind}; entries=/ + cs.collect {entry ->\n" +
                    "      /${entry.commitId} by ${entry.author.id} ~ ${entry.author.fullName} on ${new Date(entry.timestamp)}: ${entry.msg}: / + entry.affectedFiles.collect {file ->\n" +
                    "        /${file.editType.name} ${file.path}/\n" +
                    "      }.join('; ')\n" +
                    "    }.join(', ')\n" +
                    "  }.join(' & ')\n" +
                    "}", true));
                r.j.assertLogContains("changeSets: ", r.j.assertBuildStatusSuccess(p.scheduleBuild2(0)));
                sampleRepo1.write("onefile", "stuff");
                sampleRepo1.git("add", "onefile");
                sampleRepo1.git("commit", "--message=stuff");
                assertThat(JenkinsRule.getLog(r.j.assertBuildStatusSuccess(p.scheduleBuild2(0))), containsRegexp(
                    "changeSets: kind=git; entries=[a-f0-9]{40} by .+ ~ .+ on .+: stuff: add onefile"));
                sampleRepo1.write("onefile", "more stuff");
                sampleRepo1.write("anotherfile", "stuff");
                sampleRepo1.git("add", "onefile", "anotherfile");
                sampleRepo1.git("commit", "--message=more stuff");
                sampleRepo1.write("onefile", "amended");
                sampleRepo1.git("add", "onefile");
                sampleRepo1.git("commit", "--message=amended");
                sampleRepo2.write("elsewhere", "stuff");
                sampleRepo2.git("add", "elsewhere");
                sampleRepo2.git("commit", "--message=second repo");
                assertThat(JenkinsRule.getLog(r.j.assertBuildStatusSuccess(p.scheduleBuild2(0))), containsRegexp(
                    "changeSets: kind=git; entries=[a-f0-9]{40} by .+ ~ .+ on .+: more stuff: (edit onefile; add anotherfile|add anotherfile; edit onefile), " +
                    "[a-f0-9]{40} by .+ ~ .+ on .+: amended: edit onefile & " +
                    "kind=git; entries=[a-f0-9]{40} by .+ ~ .+ on .+: second repo: add elsewhere"));
            }
        });
    }

    @Issue("JENKINS-37366")
    @Test public void projectInfoFromCurrentBuild() {
        r.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                MockFolder folder = r.j.createFolder("this-folder");
                WorkflowJob p = folder.createProject(WorkflowJob.class, "this-job");
                p.setDefinition(new CpsFlowDefinition(
                        "echo \"currentBuild.fullDisplayName='${currentBuild.fullDisplayName}'\"\n" +
                        "echo \"currentBuild.projectName='${currentBuild.projectName}'\"\n" +
                        "echo \"currentBuild.fullProjectName='${currentBuild.fullProjectName}'\"\n", true));
                WorkflowRun b = r.j.assertBuildStatusSuccess(p.scheduleBuild2(0).get());
                r.j.assertLogContains("currentBuild.fullDisplayName='this-folder » this-job #1'", b);
                r.j.assertLogContains("currentBuild.projectName='this-job'", b);
                r.j.assertLogContains("currentBuild.fullProjectName='this-folder/this-job'", b);
            }
        });
    }

    // Like org.hamcrest.text.MatchesPattern.matchesPattern(String) but doing a substring, not whole-string, match:
    private static Matcher<String> containsRegexp(final String rx) {
        return new SubstringMatcher(rx) {
            @Override protected boolean evalSubstringOf(String string) {
                return Pattern.compile(rx).matcher(string).find();
            }
            @Override protected String relationship() {
                return "containing the regexp";
            }
        };
    }

}
