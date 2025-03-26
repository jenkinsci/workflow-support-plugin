/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
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

package org.jenkinsci.plugins.workflow.support.steps.input;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.console.ConsoleAnnotationDescriptor;
import hudson.console.HyperlinkNote;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * Hyperlink which sends a POST request to the specified URL.
 */
public final class POSTHyperlinkNote extends HyperlinkNote {

    private static final Logger LOGGER = Logger.getLogger(POSTHyperlinkNote.class.getName());

    @SuppressFBWarnings("HSM_HIDING_METHOD")
    public static String encodeTo(String url, String text) {
        try {
            return new POSTHyperlinkNote(url, text.length()).encode() + text;
        } catch (IOException e) {
            // impossible, but don't make this a fatal problem
            LOGGER.log(Level.WARNING, "Failed to serialize " + POSTHyperlinkNote.class, e);
            return text;
        }
    }

    private final String url;

    public POSTHyperlinkNote(String url, int length) {
        super("#", length);
        if (url.startsWith("/")) {
            StaplerRequest2 req = Stapler.getCurrentRequest2();
            // When req is not null?
            if (req != null) {
                url = req.getContextPath() + url;
            } else {
                Jenkins j = Jenkins.getInstanceOrNull();
                if (j != null) {
                    String rootUrl = j.getRootUrl();
                    if (rootUrl != null) {
                        url = rootUrl + url.substring(1);
                    } else {
                        // hope that / works, i.e., that there is no context path
                        // TODO: Does not works when there is a content path, p.e. http://localhost:8080/jenkins
                        // This message log should be an error.
                        LOGGER.warning("You need to define the root URL of Jenkins");
                    }
                }
            }
        }
        this.url = url;
    }

    @Override protected String extraAttributes() {
        // TODO perhaps add hoverNotification
        return " data-encoded-url='" + encodeForJavascript(url) + "' class='post-hyperlink-note-button'";
    }

    /**
     * Encode the String (using URLEncoding and then base64 encoding) so we can safely pass it to javascript where it can be decoded safely.
     * Javascript strings are UTF-16 and the endianness depends on the platform so we use URL encoding to ensure the String is all 7bit clean ascii and base64 encoding to fix passing any "unsafe" characters.
     */
    private static String encodeForJavascript(String str) {
        // https://developer.mozilla.org/en-US/docs/Glossary/Base64#the_unicode_problem
        String encode = URLEncoder.encode(str, StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().encodeToString(encode.getBytes(StandardCharsets.UTF_8));
    }

    // TODO why does there need to be a descriptor at all?
    @Extension public static final class DescriptorImpl extends ConsoleAnnotationDescriptor {
        @Override public String getDisplayName() {
            return "POST Hyperlinks";
        }
    }

}
