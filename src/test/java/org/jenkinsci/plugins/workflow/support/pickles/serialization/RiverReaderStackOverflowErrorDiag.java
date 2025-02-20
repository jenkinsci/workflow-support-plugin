/*
 * The MIT License
 *
 * Copyright 2025 CloudBees, Inc.
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

import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import static org.junit.Assume.assumeNotNull;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsSessionRule;

public final class RiverReaderStackOverflowErrorDiag {

    private static Path input;

    @BeforeClass public static void args() {
        var path = System.getenv("BUILD_DIR");
        assumeNotNull("Define $BUILD_DIR to run", path);
        input = Path.of(path);
    }

    @Rule public JenkinsSessionRule rr = new JenkinsSessionRule();

    @Test public void run() throws Throwable {
        var jobDir = rr.getHome().toPath().resolve("jobs/xxx");
        var buildDir = jobDir.resolve("builds/1");
        FileUtils.copyDirectory(input.toFile(), buildDir.toFile());
        Files.writeString(jobDir.resolve("config.xml"), "<flow-definition/>"); // minimal WorkflowJob
        var buildXml = buildDir.resolve("build.xml");
        Files.writeString(buildXml, Files.readString(buildXml).
            replace("<completed>true</completed>", "<completed>false</completed>").
            replace("<done>true</done>", "<done>false</done>").
            replaceFirst("<head>1:[0-9]+</head>", "<!-- no heads -->"));
        System.err.println("Loading " + input);
        rr.then(r -> {
            var build = r.jenkins.getItemByFullName("xxx", WorkflowJob.class).getBuildByNumber(1);
            try {
                ((CpsFlowExecution) build.getExecution()).programPromise.get();
                System.err.println("Loaded.");
            } catch (Exception x) {
                x.printStackTrace();
                build.writeWholeLogTo(System.err);
            }
        });
    }

}
