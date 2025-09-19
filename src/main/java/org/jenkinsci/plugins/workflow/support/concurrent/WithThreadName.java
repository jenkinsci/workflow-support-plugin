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

/**
 * Utility to temporarily append some information to the name of the current thread.
 * This is helpful for making thread dumps more readable and informative:
 * stack trace elements do not contain any information about object identity.
 */
public final class WithThreadName implements AutoCloseable {
    /** Save original thread name to recover it in {@link #close} call. */
    private final String original;

    /**
     * Sets the current thread’s name.
     * @param suffix text to append to the original name
     */
    public WithThreadName(String suffix) {
        Thread t = Thread.currentThread();
        original = t.getName();
        t.setName(original + suffix);
    }

    /**
     * Restores the original name.
     */
    @Override public void close() {
        Thread.currentThread().setName(original);
    }

}
