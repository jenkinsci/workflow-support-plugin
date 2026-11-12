/*
 * The MIT License
 *
 * Copyright 2026 CloudBees, Inc.
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

package org.jenkinsci.plugins.workflow.support.storage;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.For;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

@For(BulkFlowNodeStorage.class)
class BulkFlowNodeStorageScaleTest {

    @RegisterExtension
    private final JenkinsSessionExtension rr = new JenkinsSessionExtension();

    @Test
    void lotsOfEntities() throws Throwable {
        rr.then(r -> {
            var p = r.createProject(WorkflowJob.class, "p");
            p.setDefinition(new CpsFlowDefinition("""
                for (int i = 0; i < 1000; i++) {
                  echo "step #$i ${'<>' * 100}"
                }
                """, true));
            r.buildAndAssertSuccess(p);
        });
        rr.then(r -> {
            var b = r.jenkins.getItemByFullName("p", WorkflowJob.class).getBuildByNumber(1);
            assertThat(new DepthFirstScanner().allNodes(b.getExecution()), hasSize(1002));
        });
    }

}
