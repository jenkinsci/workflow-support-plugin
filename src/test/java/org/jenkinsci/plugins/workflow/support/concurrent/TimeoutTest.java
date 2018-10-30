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

package org.jenkinsci.plugins.workflow.support.concurrent;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.IntStream;
import jenkins.util.Timer;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.LoggerRule;

public class TimeoutTest {

    @Rule public LoggerRule logging = new LoggerRule().record(Timeout.class, Level.FINER);
    
    @Test public void passed() throws Exception {
        try (Timeout timeout = Timeout.limit(5, TimeUnit.SECONDS)) {
            Thread.sleep(1_000);
        }
    }

    @Test public void failed() throws Exception {
        try (Timeout timeout = Timeout.limit(5, TimeUnit.SECONDS)) {
            Thread.sleep(10_000);
            fail("should have timed out");
        } catch (InterruptedException x) {
            // good
        }
    }

    @Test(expected = InterruptedException.class)
    public void testInterruptedException() throws InterruptedException {
        try (Timeout timeout = Timeout.limit(5, TimeUnit.SECONDS)) {
            Thread.sleep(10_000);
            fail("should have timed out");
        }
    }

    @Test public void hung() throws Exception {
        /* see disabled code in Timeout:
        final AtomicBoolean stop = new AtomicBoolean();
        Thread t = Thread.currentThread();
        Timer.get().submit(() -> {
            while (!stop.get()) {
                System.err.println(t.getName());
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException x) {
                    x.printStackTrace();
                }
            }
        });
        */
        try (Timeout timeout = Timeout.limit(1, TimeUnit.SECONDS)) {
            for (int i = 0; i < 5; i++) {
                try /* (WithThreadName naming = new WithThreadName(" cycle #" + i)) */ {
                    Thread.sleep(10_000);
                    fail("should have timed out");
                } catch (InterruptedException x) {
                    // OK
                }
            }
        }
        Thread.sleep(6_000);
        /*
        stop.set(true);
        */
    }

    @Test public void starvation() throws Exception {
        Map<Integer, Future<?>> hangers = new TreeMap<>();
        IntStream.range(0, 15).forEachOrdered(i -> hangers.put(i, Timer.get().submit(() -> {
            try (Timeout timeout = Timeout.limit(5, TimeUnit.SECONDS)) {
                System.err.println("starting #" + i);
                Thread.sleep(Long.MAX_VALUE);
                fail("should have timed out");
            } catch (InterruptedException x) {
                System.err.println("interrupted #" + i);
            }
        })));
        for (Map.Entry<Integer, Future<?>> hanger : hangers.entrySet()) {
            System.err.println("joining #" + hanger.getKey());
            hanger.getValue().get(30, TimeUnit.SECONDS);
            System.err.println("joined #" + hanger.getKey());
        }
    }

}
