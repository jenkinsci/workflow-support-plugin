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

import hudson.AbortException;
import hudson.model.AbstractBuild;
import hudson.model.BooleanParameterValue;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.JobParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParameterValue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.RunParameterValue;
import hudson.model.StringParameterValue;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogSet;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import jenkins.scm.RunWithSCM;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.support.actions.EnvironmentAction;
import org.kohsuke.stapler.export.DataWriter;
import org.kohsuke.stapler.export.Model;
import org.kohsuke.stapler.export.ModelBuilder;
import static org.kohsuke.stapler.export.Flavor.JSON;

/**
 * Allows {@link Whitelisted} access to selected attributes of a {@link Run} without requiring Jenkins API imports.
 *
 * NOTE: if modifying this class, please remember to manually update Runwrapper/help.html
 */
public final class RunWrapper implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String externalizableId;
    private final boolean currentBuild;

    public RunWrapper(Run<?,?> build, boolean currentBuild) {
        this.externalizableId = build.getExternalizableId();
        this.currentBuild = currentBuild;
    }

    /**
     * Raw access to the internal build object.
     * Intentionally not {@link Whitelisted}.
     * The result is also not cached, since we want to stop allowing access to a build after it has been deleted.
     */
    public @CheckForNull Run<?,?> getRawBuild() {
        return Run.fromExternalizableId(externalizableId);
    }

    private @Nonnull Run<?,?> build() throws AbortException {
        Run<?,?> r = getRawBuild();
        if (r == null) {
            throw new AbortException("No build record " + externalizableId + " could be located.");
        }
        return r;
    }

    @Whitelisted
    public void setResult(String result) throws AbortException {
        if (!currentBuild) {
            throw new SecurityException("can only set the result property on the current build");
        }
        build().setResult(Result.fromString(result));
    }

    @Whitelisted
    public void setDescription(String d) throws IOException {
        if (!currentBuild) {
            throw new SecurityException("can only set the description property on the current build");
        }
        // Even if the build is carrying a specific authentication, we want it to be allowed to update itself:
        try (ACLContext ctx = ACL.as(ACL.SYSTEM)) {
            build().setDescription(d);
        }
    }

    @Whitelisted
    public void setDisplayName(String n) throws IOException {
        if (!currentBuild) {
            throw new SecurityException("can only set the displayName property on the current build");
        }
        try (ACLContext ctx = ACL.as(ACL.SYSTEM)) {
            build().setDisplayName(n);
        }
    }
	
    @Whitelisted
    public void setKeepLog(boolean b) throws IOException {
        if (!currentBuild) {
            throw new SecurityException("can only set the keepLog property on the current build");
        }
        try (ACLContext ctx = ACL.as(ACL.SYSTEM)) {
            build().keepLog(b);
        }
    }

    @Whitelisted
    public int getNumber() throws AbortException {
        return build().getNumber();
    }

    @Whitelisted
    public JSONArray getBuildCauses() throws IOException, ClassNotFoundException {
        return getBuildCauses("hudson.model.Cause");
    }

    /**
     * Filters the returned list by the type of <code>Cause</code> class passed as input
     * ex. <code>getBuildCauess('hudson.model.Cause$UserIdCause')</code> would return only
     * <code>Cause</code>s of that type
     *
     * @param className A string containing the fully qualified name for the class type to filter the result list by
     * @return a <code>JSONArray</code> of <code>Cause</code>s of the specified type
     * @throws IOException
     */
    @Whitelisted
    public JSONArray getBuildCauses(String className) throws IOException, ClassNotFoundException {
        Class clazz = Class.forName(className);
        JSONArray result = new JSONArray();

        for(Cause cause : build().getCauses()) {
            if (clazz.isInstance(cause)) {
                StringWriter w = new StringWriter();
                CauseAction causeAction = new CauseAction(cause);
                DataWriter writer = JSON.createDataWriter(causeAction, w);
                Model<CauseAction> model = new ModelBuilder().get(CauseAction.class);
                model.writeTo(causeAction, writer);
                // return a slightlly cleaner object by removing the outer object
                result.add(JSONObject.fromObject(w.toString()).getJSONArray("causes").get(0));
            }
        }
        return result;
    }

    @Whitelisted
    public @CheckForNull String getResult() throws AbortException {
        Result result = build().getResult();
        return result != null ? result.toString() : null;
    }

    @Whitelisted
    public @Nonnull String getCurrentResult() throws AbortException {
        Result result = build().getResult();
        return result != null ? result.toString() : Result.SUCCESS.toString();
    }

    @Whitelisted
    public boolean resultIsBetterOrEqualTo(String other) throws AbortException {
        Result result = build().getResult();
        if (result == null) {
            result = Result.SUCCESS;
        }
        Result otherResult = Result.fromString(other);
        return result.isBetterOrEqualTo(otherResult);
    }

    @Whitelisted
    public boolean resultIsWorseOrEqualTo(String other) throws AbortException {
        Result result = build().getResult();
        if (result == null) {
            result = Result.SUCCESS;
        }
        Result otherResult = Result.fromString(other);
        return result.isWorseOrEqualTo(otherResult);
    }

    @Whitelisted
    public long getTimeInMillis() throws AbortException {
        return build().getTimeInMillis();
    }

    @Whitelisted
    public long getStartTimeInMillis() throws AbortException {
        return build().getStartTimeInMillis();
    }

    @Whitelisted
    public long getDuration() throws AbortException {
	 return build().getDuration() != 0 ? build().getDuration() : System.currentTimeMillis() - build().getStartTimeInMillis();
    }

    @Whitelisted
    public String getDurationString() throws AbortException {
        return build().getDurationString();
    }

    @Whitelisted
    public String getDescription() throws AbortException {
        return build().getDescription();
    }

    @Whitelisted
    public String getDisplayName() throws AbortException {
        return build().getDisplayName();
    }

    @Whitelisted
    public String getFullDisplayName() throws AbortException {
        return build().getFullDisplayName();
    }
    
    @Whitelisted
    public boolean isKeepLog() throws AbortException {
        return build().isKeepLog();
    }
	
    @Whitelisted
    public String getProjectName() throws AbortException {
        return build().getParent().getName();
    }

    @Whitelisted
    public String getFullProjectName() throws AbortException {
        return build().getParent().getFullName();
    }

    @Whitelisted
    public @CheckForNull RunWrapper getPreviousBuild() throws AbortException {
        Run<?,?> previousBuild = build().getPreviousBuild();
        return previousBuild != null ? new RunWrapper(previousBuild, false) : null;
    }

    @Whitelisted
    public @CheckForNull RunWrapper getNextBuild() throws AbortException {
        Run<?,?> nextBuild = build().getNextBuild();
        return nextBuild != null ? new RunWrapper(nextBuild, false) : null;
    }

    @Whitelisted
    public String getId() throws AbortException {
        return build().getId();
    }

    @Whitelisted
    public @Nonnull Map<String,String> getBuildVariables() throws AbortException {
        Run<?,?> build = build();
        if (build instanceof AbstractBuild) {
            Map<String,String> buildVars = new HashMap<>();
            try {
                buildVars.putAll(build.getEnvironment(TaskListener.NULL));
            } catch (IOException | InterruptedException e) {
                // Do nothing
            }
            buildVars.putAll(((AbstractBuild<?,?>) build).getBuildVariables());
            return Collections.unmodifiableMap(buildVars);
        } else {
            EnvironmentAction.IncludingOverrides env = build.getAction(EnvironmentAction.IncludingOverrides.class);
            if (env != null) { // downstream is also WorkflowRun
                return env.getOverriddenEnvironment();
            } else { // who knows
                return Collections.emptyMap();
            }
        }
    }

    @Whitelisted
    @Nonnull
    public List<RunWrapper> getUpstreamBuilds() throws AbortException {
        List<RunWrapper> upstreams = new ArrayList<>();
        Run<?,?> build = build();
        for (Cause c : build.getCauses()) {
            if (c instanceof Cause.UpstreamCause) {
                upstreams.addAll(upstreamCauseToRunWrappers((Cause.UpstreamCause)c));
            }
        }

        return upstreams;
    }

    @Nonnull
    private List<RunWrapper> upstreamCauseToRunWrappers(@Nonnull Cause.UpstreamCause cause) {
        List<RunWrapper> upstreams = new ArrayList<>();
        Run<?,?> r = cause.getUpstreamRun();
        if (r != null) {
            upstreams.add(new RunWrapper(r, false));
            for (Cause c : cause.getUpstreamCauses()) {
                if (c instanceof Cause.UpstreamCause) {
                    upstreams.addAll(upstreamCauseToRunWrappers((Cause.UpstreamCause) c));
                }
            }
        }
        return upstreams;
    }

    @SuppressWarnings("deprecation")
    @Whitelisted
    public @Nonnull String getAbsoluteUrl() throws AbortException {
        return build().getAbsoluteUrl();
    }

    @Whitelisted
    public List<ChangeLogSet<? extends ChangeLogSet.Entry>> getChangeSets() throws Exception {
        Run<?,?> build = build();
        if (build instanceof RunWithSCM) { // typical cases
            return ((RunWithSCM<?, ?>) build).getChangeSets();
        } else {
            try { // to support WorkflowRun prior to workflow-job 2.12
                return (List) build.getClass().getMethod("getChangeSets").invoke(build);
            } catch (NoSuchMethodException x) { // something weird like ExternalRun
                return Collections.emptyList();
            }
        }
    }

    /**
     * Get declared build parameters the job was launched with.
     *
     * Includes only parameters that the job specifies it accepts. To get
     * additional parameters that may have been submitted as untrusted user
     * input use {@link ParametersAction#getAllParameters} on the raw
     * {@link Run} object.
     *
     * Parameters marked as "isSensitive" are returned as a raw
     * {@link ParameterValue} object, so they may only be inspected
     * if the script security context permits it.
     *
     * Return values are Java objects of parameter-specific types, some of
     * which may not be usable by scripts without whitelisting or library
     * helpers. Built-in parameter types are reported as:
     *
     * <ul>
     *  <li><b>{@link StringParameterValue}</b>: <code>String</code></li>
     *  <li><b>{@link BooleanParameterValue}</b>: <code>Boolean</code></li>
     *  <li><b>{@link PasswordParameterValue}</b>: the <code>PasswordParameterValue</code> object (restricted)</li>
     *  <li><b>{@link RunParameterValue}</b>: <code>{@link RunWrapper}</code></li>
     *  <li><b>{@link JobParameterValue}</b>: <code>{@link Job}</code> (restricted)</li>
     *  <li><b>{@link FileParameterValue}</b>: <code><a href="https://commons.apache.org/proper/commons-fileupload/apidocs/org/apache/commons/fileupload/FileItem.html">org.apache.commons.fileupload.FileItem</a></code> (restricted)</li>
     * </ul>
     *
     * Scripts will generally want to use <code>instanceof</code> tests to
     * determine how to handle the parameters.
     *
     * @returns Map of parameter names to parameter values, or null if the job was not parameterised
     * @see ParametersAction#getParameters
     * @see #getAllBuildParameters
     * @see ParametersDefinitionProperty
     */
    @Whitelisted
    public @Nonnull Map<String,Object> getBuildParameters() throws AbortException {
        Run<?,?> build = build();
        ParametersAction params = build.getAction(ParametersAction.class);
        if (params == null) {
            return null;
        } else {
            Map<String,Object> wrappedParams = new HashMap<>();

            for(Map.Entry<String, HashMap> entry : params.getParameters()) {
                final ParameterValue param = entry.getValue();
                Object wrapped;
                if (param == null) {
                    wrapped = null;
                } else if (param.isSensitive()) {
                    // Return PasswordParameterValue and any others marked
                    // sensitive unchanged, so the script can only do anything
                    // with them if the relevant methods are whitelisted.
                    wrapped = param;
                } else if (param.instanceOf(RunParameterValue.class)) {
                    wrapped = new RunWrapper((Run)param.getValue(), false);
                } else {
                    wrapped = param.getValue();
                }
                wrappedParams.put(entry.getKey(), wrapped);
            }

            return wrappedParams;
        }
    }

    /**
     * Get declared build parameters the job was launched with.
     *
     * Like {@link #getBuildParameters} but returns string values
     * for all parameters. The string representation is specific
     * to the parameter type. Sensitive parameters are returned
     * with a placeholder string of asterisks.
     *
     * This output is mainly useful for displaying to the user.
     *
     * @see #getBuildParameters
     */
    @Whitelisted
    public @Nonnull Map<String,String> getBuildParametersAsStrings() throws AbortException {
        Run<?,?> build = build();
        ParametersAction params = build.getAction(ParametersAction.class);
        if (params == null) {
            return null;
        } else {
            Map<String,Object> wrappedParams = new HashMap<>();

            for(Map.Entry<String, HashMap> entry : params.getParameters()) {
                final ParameterValue param = entry.getValue();
                Object wrapped;
                if (param == null) {
                    wrapped = null;
                } else if (param.isSensitive()) {
                    wrapped = "********";
                } else if (param.instanceOf(RunParameterValue.class)) {
                    wrapped = param.getValue().getFullName();
                } else if (param.instanceOf(JobParameterValue.class)) {
                    wrapped = param.getValue().getFullName();
                } else {
                    wrapped = param.getValue().toString();
                }
                wrappedParams.put(entry.getKey(), wrapped);
            }

            return wrappedParams;
        }
    }

}
