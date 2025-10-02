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

import java.lang.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.util.SystemProperties;

/**
 * Utility to temporarily append some information to the name of the current thread.
 * This is helpful for making thread dumps more readable and informative:
 * stack trace elements do not contain any information about object identity.
 */
public final class WithThreadName implements AutoCloseable {
    /** Save original thread name to recover it in {@link #close} call.
     * Remains {@code null} if activity of this class is {@link #disabled}
     * on a particular deployment.
     */
    private final String original;

    /** Optional toggle via JVM properties to skip work here,
     *  and forfeit easy debugging, e.g. on systems where
     *  java.lang.Thread.setNativeName(Native Method) aka
     *  JVM_SetNativeThreadName() and further platform
     *  specific implementation takes inexplicably long.
     */
    private final static Boolean disabled = SystemProperties.getBoolean("DISABLE_WithThreadName");

    /** Help gauge how much and how often this code gets called */
    private static final Logger LOGGER = Logger.getLogger(WithThreadName.class.getName());

    /**
     * Sets the current thread’s name.
     * @param suffix text to append to the original name
     */
    public WithThreadName(String suffix) {
        if (disabled) {
            original = null;
            LOGGER.log(Level.FINE, "SKIP: Neutered WithThreadName(\"{0}\")", suffix);
            return;
        }

        Thread t = Thread.currentThread();
        original = t.getName();
        t.setName(original + suffix);
    }

    /**
     * Restores the original name.
     */
    @Override public void close() {
        if (disabled) {
            /* We did not track origin nor suffix here to be fast when skipping, so eh */
            LOGGER.log(Level.FINE, "SKIP: Neutered WithThreadName.close()");
            return;
        }

        Thread.currentThread().setName(original);
    }

}
