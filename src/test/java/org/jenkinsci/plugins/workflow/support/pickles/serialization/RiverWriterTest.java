/*
 * The MIT License
 *
 * Copyright 2017 CloudBees, Inc.
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

import com.google.common.util.concurrent.ListenableFuture;
import hudson.Functions;
import java.io.File;
import java.io.NotSerializableException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.logging.Level;
import static org.hamcrest.Matchers.containsString;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.pickles.PickleFactory;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.LoggerRule;

public class RiverWriterTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    @Rule public LoggerRule logging = new LoggerRule().recordPackage(RiverWriter.class, Level.FINE);

    @Test public void trivial() throws Exception {
        File f = tmp.newFile();
        FlowExecutionOwner owner = FlowExecutionOwner.dummyOwner();
        try (RiverWriter w = new RiverWriter(f, owner, Collections.<PickleFactory>emptySet())) {
            w.writeObject(Collections.singletonList("hello world"));
        }
        Object o;
        try (RiverReader r = new RiverReader(f, RiverWriterTest.class.getClassLoader(), owner)) {
            o = r.restorePickles(new ArrayList<ListenableFuture<?>>()).get().readObject();
        }
        assertEquals(Collections.singletonList("hello world"), o);
    }

    @Issue("JENKINS-26137")
    @Test public void errors() throws Exception {
        File f = tmp.newFile();
        FlowExecutionOwner owner = FlowExecutionOwner.dummyOwner();
        try (RiverWriter w = new RiverWriter(f, owner, Collections.<PickleFactory>emptySet())) {
            w.writeObject(Collections.singletonList(new NotActuallySerializable()));
            fail();
        } catch (NotSerializableException x) {
            /*
            original:
            Caused by: an exception which occurred:
                in field bad
                in object java.util.Collections$SingletonList@7791a8b4
            now:
            Caused by: an exception which occurred:
                in field org.jenkinsci.plugins.workflow.support.pickles.serialization.RiverReaderTest$NotActuallySerializable.bad
                in object org.jenkinsci.plugins.workflow.support.pickles.serialization.RiverReaderTest$NotActuallySerializable@6b09bb57
                in object java.util.Collections$SingletonList@6b09bb76
            */
            assertThat(Functions.printThrowable(x), containsString(NotActuallySerializable.class.getName() + ".bad"));
        }
    }
    private static class NotActuallySerializable implements Serializable {
        String good = "OK";
        Object bad = new Object();
    }

    // TODO pickle resolution

}
