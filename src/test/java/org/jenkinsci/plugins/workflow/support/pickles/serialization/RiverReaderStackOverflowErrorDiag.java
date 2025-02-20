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

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.SimpleClassResolver;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import static org.jenkinsci.plugins.workflow.support.pickles.serialization.RiverReader.parseHeader;

// mvnd test-compile exec:java -Dexec.mainClass=org.jenkinsci.plugins.workflow.support.pickles.serialization.RiverReaderStackOverflowErrorDiag -Dexec.classpathScope=test -Dexec.arguments=/path/to/program.dat
public final class RiverReaderStackOverflowErrorDiag {

    public static void main(String[] args) throws Exception {
        var programDat = Path.of(args[0]);
        // TODO probably need to specify a megawar also
        System.err.println("Will load " + programDat.toRealPath());
        Script script = new GroovyShell().parse("public class WorkflowScript extends Script implements Serializable {def run() {}}");
        var classLoader = script.getClass().getClassLoader();
        try (var in = Files.newInputStream(programDat)) {
            var din = new DataInputStream(in);
            parseHeader(din); // ignore offset, not bothering to read pickles
            var config = new MarshallingConfiguration();
            config.setClassResolver(new SimpleClassResolver(classLoader));
            var eu = new RiverMarshallerFactory().createUnmarshaller(config);
            eu.start(Marshalling.createByteInput(din));
            var g = eu.readObject();
            System.err.println("Loaded " + g);
        }
    }

}
