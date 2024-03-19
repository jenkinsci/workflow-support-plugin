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

import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.FreeStyleProject;
import hudson.model.Messages;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import java.util.regex.Pattern;

import hudson.model.StringParameterDefinition;
import jenkins.plugins.git.GitSampleRepoRule;
import org.hamcrest.Matcher;
import org.hamcrest.core.SubstringMatcher;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.JenkinsSessionRule;

@Issue("JENKINS-26834")
public class RunWrapperTest {

    @ClassRule public static BuildWatcher buildWatcher = new BuildWatcher();
    @Rule public JenkinsSessionRule sessions = new JenkinsSessionRule();
    @Rule public GitSampleRepoRule sampleRepo1 = new GitSampleRepoRule();
    @Rule public GitSampleRepoRule sampleRepo2 = new GitSampleRepoRule();

    @Test public void historyAndPickling() throws Throwable {
        sessions.then(j -> {
                WorkflowJob p = j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "def b0 = currentBuild\n" +
                    "for (b = b0; b != null; b = b.previousBuild) {\n" +
                    "  semaphore 'basics'\n" +
                    "  echo \"number=${b.number} result=${b.result}\"\n" +
                    "}", true));
                SemaphoreStep.success("basics/1", null);
                WorkflowRun b1 = j.buildAndAssertSuccess(p);
                j.assertLogContains("number=1 result=null", b1);
                WorkflowRun b2 = p.scheduleBuild2(0).getStartCondition().get();
                SemaphoreStep.success("basics/2", null);
                SemaphoreStep.waitForStart("basics/3", b2);
                j.waitForMessage("number=2 result=null", b2);
                j.assertLogNotContains("number=1", b2);
        });
        sessions.then(j -> {
                WorkflowJob p = j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b2 = p.getBuildByNumber(2);
                SemaphoreStep.success("basics/3", b2);
                j.assertBuildStatusSuccess(j.waitForCompletion(b2));
                j.assertLogContains("number=1 result=SUCCESS", b2);
        });
    }

    @Test public void updateSelf() throws Throwable {
        sessions.then(j -> {
                WorkflowJob p = j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition(
                    "currentBuild.result = 'UNSTABLE'\n" +
                    "currentBuild.description = 'manipulated'\n" +
                    "currentBuild.displayName = 'special'\n" +
                    "def pb = currentBuild.previousBuild; if (pb != null) {pb.displayName = 'verboten'}", true));
                WorkflowRun b1 = j.buildAndAssertStatus(Result.UNSTABLE, p);
                assertEquals("manipulated", b1.getDescription());
                assertEquals("special", b1.getDisplayName());
                WorkflowRun b2 = j.buildAndAssertStatus(Result.FAILURE, p);
                assertEquals(SecurityException.class, b2.getExecution().getCauseOfFailure().getClass());
                assertEquals("manipulated", b2.getDescription());
                assertEquals("special", b2.getDisplayName());
                assertEquals("special", b1.getDisplayName());
        });
    }

    @Issue("JENKINS-30412")
    @Test public void getChangeSets() throws Throwable {
        sessions.then(j -> {
                sampleRepo1.init();
                sampleRepo2.init();
                WorkflowJob p = j.createProject(WorkflowJob.class, "p");
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
                j.assertLogContains("changeSets: ", j.buildAndAssertSuccess(p));
                sampleRepo1.write("onefile", "stuff");
                sampleRepo1.git("add", "onefile");
                sampleRepo1.git("commit", "--message=stuff");
                assertThat(JenkinsRule.getLog(j.buildAndAssertSuccess(p)), containsRegexp(
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
                assertThat(JenkinsRule.getLog(j.buildAndAssertSuccess(p)), containsRegexp(
                    "changeSets: kind=git; entries=[a-f0-9]{40} by .+ ~ .+ on .+: more stuff: (edit onefile; add anotherfile|add anotherfile; edit onefile), " +
                    "[a-f0-9]{40} by .+ ~ .+ on .+: amended: edit onefile & " +
                    "kind=git; entries=[a-f0-9]{40} by .+ ~ .+ on .+: second repo: add elsewhere"));
        });
    }

    @Issue("JENKINS-37366")
    @Test public void projectInfoFromCurrentBuild() throws Throwable {
        sessions.then(j -> {
                MockFolder folder = j.createFolder("this-folder");
                WorkflowJob p = folder.createProject(WorkflowJob.class, "this-job");
                p.setDefinition(new CpsFlowDefinition(
                        "echo \"currentBuild.fullDisplayName='${currentBuild.fullDisplayName}'\"\n" +
                        "echo \"currentBuild.projectName='${currentBuild.projectName}'\"\n" +
                        "echo \"currentBuild.fullProjectName='${currentBuild.fullProjectName}'\"\n", true));
                WorkflowRun b = j.buildAndAssertSuccess(p);
                { // TODO mojibake until https://github.com/jenkinsci/workflow-job-plugin/pull/89 is released and can be consumed as a test dependency; expect this-folder » this-job #1
                    j.assertLogContains("currentBuild.fullDisplayName='this-folder", b);
                    j.assertLogContains("this-job #1'", b);
                }
                j.assertLogContains("currentBuild.projectName='this-job'", b);
                j.assertLogContains("currentBuild.fullProjectName='this-folder/this-job'", b);
        });
    }

    @Issue("JENKINS-42952")
    @Test public void duration() throws Throwable {
        sessions.then(j -> {
                WorkflowJob p = j.createProject(WorkflowJob.class, "this-job");
                p.setDefinition(new CpsFlowDefinition(
                        "echo \"currentBuild.duration='${currentBuild.duration}'\"\n" +
                                "echo \"currentBuild.durationString='${currentBuild.durationString}'\"\n", true));
                WorkflowRun b = j.buildAndAssertSuccess(p);
                j.assertLogNotContains("currentBuild.duration='0'", b);
                j.assertLogNotContains("currentBuild.durationString='" + Messages.Run_NotStartedYet() + "'", b);
        });
    }


    @Issue("JENKINS-37366")
    @Test public void getCurrentResult() throws Throwable {
        sessions.then(j -> {
                MockFolder folder = j.createFolder("this-folder");
                WorkflowJob p = folder.createProject(WorkflowJob.class, "current-result-job");
                p.setDefinition(new CpsFlowDefinition(
                        "echo \"initial currentBuild.currentResult='${currentBuild.currentResult}'\"\n" +
                        "currentBuild.result = 'UNSTABLE'\n" +
                        "echo \"final currentBuild.currentResult='${currentBuild.currentResult}'\"\n" +
                        "echo \"resultIsBetterOrEqualTo FAILURE: ${currentBuild.resultIsBetterOrEqualTo('FAILURE')}\"\n" +
                        "echo \"resultIsWorseOrEqualTo SUCCESS: ${currentBuild.resultIsWorseOrEqualTo('SUCCESS')}\"\n",
                        true));
                WorkflowRun b = j.buildAndAssertStatus(Result.UNSTABLE, p);
                j.assertLogContains("initial currentBuild.currentResult='" + Result.SUCCESS.toString() + "'", b);
                j.assertLogContains("final currentBuild.currentResult='" + Result.UNSTABLE.toString() + "'", b);
                j.assertLogContains("resultIsBetterOrEqualTo FAILURE: true", b);
                j.assertLogContains("resultIsWorseOrEqualTo SUCCESS: true", b);
        });
    }

    @Issue("JENKINS-36528")
    @Test public void freestyleEnvVars() throws Throwable {
        sessions.then(j -> {
                WorkflowJob p = j.createProject(WorkflowJob.class, "pipeline-job");
                FreeStyleProject f = j.createProject(FreeStyleProject.class, "freestyle-job");
                f.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("param", "default")));
                p.setDefinition(new CpsFlowDefinition(
                        "def b = build(job: 'freestyle-job', parameters: [string(name: 'param', value: 'something')])\n" +
                                "echo \"b.buildVariables.BUILD_TAG='${b.buildVariables.BUILD_TAG}'\"\n" +
                                "echo \"b.buildVariables.param='${b.buildVariables.param}'\"\n", true));
                WorkflowRun b = j.buildAndAssertSuccess(p);
                j.assertLogContains("b.buildVariables.BUILD_TAG='jenkins-freestyle-job-1'", b);
                j.assertLogContains("b.buildVariables.param='something'", b);
        });
    }

    @Test
    @Issue("JENKINS-54227")
    public void buildCauseTest() throws Throwable {
        sessions.then(j -> {
            WorkflowJob job = j.createProject(WorkflowJob.class, "test");
            // test with a single build cause
            job.setDefinition(new CpsFlowDefinition("echo currentBuild.getBuildCauses().toString()\n"
                                                    + "assert currentBuild.getBuildCauses().size() == 1\n"
                                                    + "assert currentBuild.getBuildCauses()[0].userId == 'tester'\n",
                                                    true));
            WorkflowRun run = j.assertBuildStatusSuccess(job.scheduleBuild2(0, new CauseAction(new Cause
                    .UserIdCause("tester"))));
            j.assertLogContains("[{\"_class\":\"hudson.model.Cause$UserIdCause\",\"shortDescription\":\"Started by user anonymous\",\"userId\":\"tester\",\"userName\":\"anonymous\"}]",run);

            // test with mutiple build causes
            job.setDefinition(new CpsFlowDefinition("echo currentBuild.getBuildCauses().toString()\n"
                                                    + "assert currentBuild.getBuildCauses().size() == 2\n"
                                                    + "assert currentBuild.getBuildCauses()[0].note == 'this is a note'\n"
                                                    + "assert currentBuild.getBuildCauses()[1].userId == 'tester'\n",
                                                    true));

            run = j.assertBuildStatusSuccess(job.scheduleBuild2(0,new CauseAction(new Cause
                .RemoteCause("upstream.host","this is a note"), new Cause.UserIdCause("tester"))));
            j.assertLogContains("[{\"_class\":\"hudson.model.Cause$RemoteCause\",\"shortDescription\":\"Started by "
                                  + "remote host upstream.host with note: this is a note\",\"addr\":\"upstream"
                                  + ".host\",\"note\":\"this is a note\"},{\"_class\":\"hudson.model"
                                  + ".Cause$UserIdCause\",\"shortDescription\":\"Started by user anonymous\","
                                  + "\"userId\":\"tester\",\"userName\":\"anonymous\"}]", run);

            // test filtering build causes
            job.setDefinition(new CpsFlowDefinition("echo currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')"
                                                    + ".toString()\n"
                                                    + "assert currentBuild.getBuildCauses('hudson.model"
                                                    + ".Cause$UserIdCause').size() == 1\n"
                                                    + "assert currentBuild.getBuildCauses('hudson.model"
                                                    + ".Cause$UserIdCause')[0].userId == 'tester2'\n",
                                                    true));

            run = j.assertBuildStatusSuccess(job.scheduleBuild2(0,new CauseAction(new Cause
                    .RemoteCause("upstream.host","this is a note"), new Cause.UserIdCause("tester2"))));
            j.assertLogContains("[{\"_class\":\"hudson.model"
                                  + ".Cause$UserIdCause\",\"shortDescription\":\"Started by user anonymous\","
                                  + "\"userId\":\"tester2\",\"userName\":\"anonymous\"}]", run);

        });

    }

    @Issue("JENKINS-31576")
    @Test
    public void upstreamBuilds() throws Throwable {
        sessions.then(j -> {
                WorkflowJob first = j.createProject(WorkflowJob.class, "first-job");
                WorkflowJob second = j.createProject(WorkflowJob.class, "second-job");
                WorkflowJob third = j.createProject(WorkflowJob.class, "third-job");
                first.setDefinition(new CpsFlowDefinition("build job: 'second-job'\n", true));
                second.setDefinition(new CpsFlowDefinition("build job: 'third-job'\n", true));
                third.setDefinition(new CpsFlowDefinition("currentBuild.upstreamBuilds?.each { b ->\n" +
                        "  echo \"b: ${b.getFullDisplayName()}\"\n" +
                        "}\n", true));

                WorkflowRun firstRun = j.buildAndAssertSuccess(first);
                WorkflowRun secondRun = second.getBuildByNumber(1);
                j.assertBuildStatusSuccess(secondRun);
                WorkflowRun thirdRun = third.getBuildByNumber(1);
                j.assertBuildStatusSuccess(thirdRun);

                j.assertLogContains("b: " + firstRun.getFullDisplayName(), thirdRun);
                j.assertLogContains("b: " + secondRun.getFullDisplayName(), thirdRun);
        });
    }

    // Like org.hamcrest.text.MatchesPattern.matchesPattern(String) but doing a substring, not whole-string, match:
    private static Matcher<String> containsRegexp(final String rx) {
        return new SubstringMatcher("containing the regexp", false, rx) {
            @Override protected boolean evalSubstringOf(String string) {
                return Pattern.compile(rx).matcher(string).find();
            }
        };
    }

}
