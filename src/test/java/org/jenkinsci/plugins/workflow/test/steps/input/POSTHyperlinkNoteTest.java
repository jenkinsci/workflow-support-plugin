package org.jenkinsci.plugins.workflow.test.steps.input;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousStepExecution;
import org.jenkinsci.plugins.workflow.support.steps.input.POSTHyperlinkNote;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import org.jvnet.hudson.test.TestExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.htmlunit.HttpMethod;
import org.htmlunit.MockWebConnection;
import org.htmlunit.Page;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlAnchor;
import org.htmlunit.html.HtmlPage;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.model.queue.QueueTaskFuture;
import jenkins.model.Jenkins;

public class POSTHyperlinkNoteTest {

    @Rule
    public JenkinsRule jr = new JenkinsRule();

    @Test
    @Issue("SECURITY-2881")
    public void urlsAreSafeFromJavascriptInjection() throws Exception {
        testSanitization("whatever/'+alert(1)+'");
    }

    @Test
    @Ignore("webclient does not support unicode URLS and this is passed as /jenkins/whatever/%F0%9F%99%88%F0%9F%99%89%F0%9F%99%8A%F0%9F%98%80%E2%98%BA")
    public void testPassingMultiByteCharacters() throws Exception {
        // this is actually illegal in HTML4 but common -> https://www.w3.org/TR/html40/appendix/notes.html#non-ascii-chars
        // browsers infer the URL from the charset and then encode the escaped characters...
        testSanitization("whatever/🙈🙉🙊😀☺");
    }

    @Test
    public void testPassingSingleByte() throws Exception {
        testSanitization("whatever/something?withparameter=baa");
    }

    void testSanitization(String fragment) throws Exception {
        WorkflowJob project = jr.createProject(WorkflowJob.class);
        project.setDefinition(new CpsFlowDefinition("security2881(params.TEST_URL)\n", true));
        project.addProperty(new ParametersDefinitionProperty(new StringParameterDefinition("TEST_URL", "WHOOPS")));

        QueueTaskFuture<WorkflowRun> scheduleBuild = project.scheduleBuild2(0, new ParametersAction(new StringParameterValue("TEST_URL", fragment)));
        WorkflowRun run = jr.assertBuildStatus(Result.SUCCESS, scheduleBuild);
        WebClient wc = jr.createWebClient();

        HtmlPage page = wc.getPage(run, "console");
        HtmlAnchor anchor = page.getAnchorByText("SECURITY-2881");
        assertThat(anchor, notNullValue());
        MockWebConnection mwc = new MockWebConnection();
        mwc.setDefaultResponse("<html><body>Hello</body></html>");
        wc.setWebConnection(mwc);
        System.out.println(anchor);
        Page p = anchor.click();

        // the click executes an ajax request - and so we need to wait until that has completed
        // ideally we would pass zero here as we have already clicked the javascript should have
        // started executing - but this is not always the case
        wc.waitForBackgroundJavaScriptStartingBefore(500);

        // check we have an interaction at the correct place and its not a javascript issue.
        WebRequest request = mwc.getLastWebRequest();
        assertThat(request, notNullValue());
        assertThat(request.getHttpMethod(), is(HttpMethod.POST));
        URL url = request.getUrl();
        System.out.println(url.toExternalForm());
        assertThat(url, allOf(hasProperty("host", is(new URL(jr.jenkins.getConfiguredRootUrl()).getHost())),
                              hasProperty("file", is(jr.contextPath + '/' + fragment))));
    }

    public static class Security2881ConsoleStep extends Step {

        private final String urlFragment;

        @DataBoundConstructor
        public Security2881ConsoleStep(String urlFragment) {
            this.urlFragment = urlFragment;
        }

        @Override
        public StepExecution start(StepContext context) throws Exception {
            return new Security2881ConsoleStepExecution(context, urlFragment);
        }

        @TestExtension 
        public static final class DescriptorImpl extends StepDescriptor {

            @Override public String getFunctionName() {
                return "security2881";
            }

            @NonNull
            @Override public String getDisplayName() {
                return "Security2881";
            }

            @Override public Set<? extends Class<?>> getRequiredContext() {
                return Collections.singleton(TaskListener.class);
            }

            @Override public String argumentsToString(@NonNull Map<String, Object> namedArgs) {
                return null;
            }
        }

        public static class Security2881ConsoleStepExecution extends SynchronousStepExecution<Void> {

            private final String urlFragment;

            protected Security2881ConsoleStepExecution(StepContext context, String urlFragment) {
                super(context);
                this.urlFragment = urlFragment;
            }

            @Override
            protected Void run() throws Exception {
                TaskListener taskListener = getContext().get(TaskListener.class);
                // use the same URL for CORS.
                taskListener.getLogger().print(POSTHyperlinkNote.encodeTo(Jenkins.get().getConfiguredRootUrl() + urlFragment, "SECURITY-2881"));
                return null;
            }
        }
    }
}
