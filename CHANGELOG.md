## Changelog

- For newer versions, see [GitHub Releases](https://github.com/jenkinsci/workflow-support-plugin/releases)

### 3.8

Release date: 2021-03-04

- Make plugin compatible with Guava 21.0 and newer.([PR 114](https://github.com/jenkinsci/workflow-support-plugin/pull/114))
- Internal: Update LTS baseline. ([PR 115](https://github.com/jenkinsci/workflow-support-plugin/pull/115))

### 3.7

Release date: 2020-11-30

- Fix: Do not add empty password parameters to the sensitive variable list.([JENKINS-64282](https://issues.jenkins-ci.org/browse/JENKINS-64282))

### 3.6

Release date: 2020-11-05

- Fix: Mask password parameters using the new EnvironmentExpander API from [workflow-step-api](https://github.com/jenkinsci/workflow-step-api-plugin/pull/57). ([PR 110](https://github.com/jenkinsci/workflow-support-plugin/pull/110))([JENKINS-47101](https://issues.jenkins-ci.org/browse/JENKINS-47101))

### 3.5

Release date: 2020-06-15

- Fix: Traversing a Pipeline execution using the `FlowGraphTable` API (used primarily for the Pipeline Steps view) could cause infinite loops for corrupted Pipelines in rare cases. ([JENKINS-62545](https://issues.jenkins-ci.org/browse/JENKINS-62545))
- Internal: Update minimum required Jenkins version to 2.176.4, update parent POM, and update dependencies. ([PR 106](https://github.com/jenkinsci/workflow-support-plugin/pull/106))

### 3.4

Release date: 2020-01-15

- Improvement: Add methods to `currentBuild` for getting various types of previous builds, such as `currentBuild.previousSuccessfulBuild` ([PR 102](https://github.com/jenkinsci/workflow-support-plugin/pull/102))
- Improvement: Add localization support. ([PR 93](https://github.com/jenkinsci/workflow-support-plugin/pull/93))
- Improvement: Fix typo in the documentation for the `currentBuild` global variable. ([PR 100](https://github.com/jenkinsci/workflow-support-plugin/pull/100))
- Internal: Update parent POM and improve tests. ([PR 99](https://github.com/jenkinsci/workflow-support-plugin/pull/99))

### 3.3

Release date: 2019-04-25

-   Developer: Add support
    for `FlowNode` to `DefaultStepContext.get(Class)` ([PR
    94](https://github.com/jenkinsci/workflow-support-plugin/pull/94))
-   Internal: Update parent POM and Javadoc so that the plugin can build
    with all tests passing on Java 11 ([PR
    92](https://github.com/jenkinsci/workflow-support-plugin/pull/92))

### 3.2

Release date: 2019-02-01

-   [JENKINS-51170](https://issues.jenkins-ci.org/browse/JENKINS-51170): Enable
    the `StepEnvironmentContributor` extension point added in version
    2.19 of Pipeline Step API Plugin
-   Fix: Add custom JEP-200 class filter entry so
    that `org.jboss.marshalling.TraceInformation$IndexInfo`, which may
    occur in some error messages related to saving and loading Pipeline
    builds, can be serialized and deserialized without any warnings
    being printed to build logs ([PR
    90](https://github.com/jenkinsci/workflow-support-plugin/pull/90))
-   Internal: Use APIs from newer versions of Jenkins core to clean up
    some code paths ([PR
    91](https://github.com/jenkinsci/workflow-support-plugin/pull/91))

### 3.1

Release date: 2019-01-16

-   Internal: Remove test scope dependency
    on obsolete `workflow-scm-step` tests artifact ([PR
    88](https://github.com/jenkinsci/workflow-support-plugin/pull/88))
-   Internal: Simplify configuration for the compatibility warning shown
    when upgrading to the 3.x line of the plugin ([PR
    89](https://github.com/jenkinsci/workflow-support-plugin/pull/89))

### 3.0

Release date: 2019-01-03

> This update involves incompatible changes to the serialized format of
Pipeline builds. Any Pipelines that are running when Jenkins is
restarted to apply this upgrade will fail after the restart. Pipelines
will be able to resume normally after subsequent restarts.
>
> To avoid this one-time issue, please stop all running Pipelines or allow
them to complete before restarting Jenkins when applying this update.

-   [JENKINS-52187](https://issues.jenkins-ci.org/browse/JENKINS-52187):
    Update JBoss Marshalling dependency, which is used to serialize and
    deserialize Pipeline data across Jenkins restarts, in order to
    support newer versions of Java.

### 2.24

Release date: 2018-12-12

-   [JENKINS-26138](https://issues.jenkins-ci.org/browse/JENKINS-26138):
    Add a sidebar link to Pipeline builds in the classic UI to show all
    workspaces used by the build.
-   [PR #85](https://github.com/jenkinsci/workflow-support-plugin/pull/85):
    Fix the implementation of
    `c``urrentBuild.getBuildCauses(String fullyQualifiedClassName)` so
    that it works correctly when the class name refers to a class
    defined in a different plugin.

### 2.23

Release date: 2018-11-30

-   [JENKINS-54531](https://issues.jenkins-ci.org/browse/JENKINS-54531):
    Update documentation for `currentBuild.getBuildVariables`
-   Avoid use of deprecated APIs ([PR
    76](https://github.com/jenkinsci/workflow-support-plugin/pull/76))

### 2.22

Release date: 2018-11-02

-   [JENKINS-41272](https://issues.jenkins-ci.org/browse/JENKINS-41272)/[JENKINS-54227](https://issues.jenkins-ci.org/browse/JENKINS-54227):
    Safely expose build causes in Pipeline as JSON. Build causes can be
    accessed as follows:
    -   `currentBuild.getBuildCauses()`
    -   `currentBuild.getBuildCauses(String fullyQualifiedClassName)`

### 2.21

Release date: 2018-10-12

-   [JEP-210](https://jenkins.io/jep/210): redesigned log storage system
    for Pipeline builds. Should have no effect unless [Pipeline Job
    Plugin](https://plugins.jenkins.io/workflow-job) is
    also updated.
-   [JENKINS-45693](https://issues.jenkins-ci.org/browse/JENKINS-45693):
    support for `TaskListenerDecorator` API.

-   `currentBuild` documentation updates.

### 2.21-beta-1

Release date: 2018-10-04

-   [JEP-210](https://jenkins.io/jep/210): redesigned log storage system
    for Pipeline builds. Should have no effect unless [Pipeline Job
    Plugin](https://plugins.jenkins.io/workflow-job) is
    also updated.
-   [JENKINS-45693](https://issues.jenkins-ci.org/browse/JENKINS-45693):
    support for `TaskListenerDecorator` API.

-   `currentBuild` documentation updates.

### 2.20

Release date: 2018-08-07

> Users are encouraged to combine this with an updates to the [Pipeline Job Plugin](https://plugins.jenkins.io/workflow-job) and [Pipeline Nodes and Processes Plugin](https://plugins.jenkins.io/workflow-durable-task-step)

-   [JEP-206](https://github.com/jenkinsci/jep/blob/master/jep/206/README.adoc)
    Use UTF-8 for all Pipeline build logs

### 2.19

Release date: 2018-06-25

-   [JENKINS-49014](https://issues.jenkins-ci.org/browse/JENKINS-49014) Exposes
    currentBuild.keepLog
-   [JENKINS-51390](https://issues.jenkins-ci.org/browse/JENKINS-51390) Use
    ProxyException when necessary for program.dat

### 2.18

Release date: 2018-02-05

-   [Fix security
    issue](https://jenkins.io/security/advisory/2018-02-05/)

### 2.17

Release date: 2018-01-22

-   **Major Feature**: Add APIs for FlowNodeStorage to provide more
    granular control of when/how they write to disk 
    ([JENKINS-47172](https://issues.jenkins-ci.org/browse/JENKINS-47172))  
    -   Allows for deferred writes, where a FlowNode has all its actions
        attached before being written (cuts writes \~1/2 or more)
    -   Provides facilities a bit like DB transactions
-   **Major Feature**: New and MUCH more efficient pipeline FlowNode
    storage 
    ([JENKINS-47173](https://issues.jenkins-ci.org/browse/JENKINS-47173))
    -   Stores all FlowNodes in a single file, allowing for much faster
        bulk streaming read/writes, and faster access.
    -   Available with the performance-optimized durability setting -
        see Jenkins documentation for Pipeline Scalability for what you
        need to enable this.
-   **Enhancement**:  More compact representation of FlowNodes by using
    XStream Aliases
    ([JENKINS-49084](https://issues.jenkins-ci.org/browse/JENKINS-49084))
    -   Applies to all of the FlowNode storage engines, and reduces
        size-on-disk (and data written) by about 30%
    -   **Compatibility note: after this change, builds with this plugin
        version CANNOT be read by older versions of this plugin**
-   Feature: utility API to switch between atomic and non-atomic XStream
    serialization
-   Robustness enhancement: Timeout utility tries to repeatedly
    interrupt threads and notes that this is happening
    ([PR\#48](https://github.com/jenkinsci/workflow-support-plugin/pull/48))
-   Feature: Sandboxed access to upstream build information
    ([JENKINS-31576](https://issues.jenkins-ci.org/browse/JENKINS-31576))
-   ClassFilter entries to ensure the XStream/Remoting whitelist doesn't
    break Pipeline
-   Bugfix: Fix a Groovy memory leak introduced previously with the
    Timeout utility: ensure that the timeout threadpool cannot be
    lazy-initialized with a GroovyClassloader as its contextClassloader

### 2.16

Release date: 2017-10-13

-   [JENKINS-26148](https://issues.jenkins-ci.org/browse/JENKINS-26148) Create
    a default implementation of StepExecution.stop
-   Add a WithThreadName utility to give threads more meaningful names
    for debugging

### 2.15

Release date: 2017-09-26

-   [JENKINS-26137](https://issues.jenkins-ci.org/browse/JENKINS-26137)
    Integrate patched version of JBoss Marshalling with better
    diagnostics 
-   [JENKINS-38223](https://issues.jenkins-ci.org/browse/JENKINS-38223)
    /
    [JENKINS-45553](https://issues.jenkins-ci.org/browse/JENKINS-45553)
    Massively improve performance of pipeline with numerous parallel
    branches by using the new isActive API from workflow-api 2.22.
-   [JENKINS-37324](https://issues.jenkins-ci.org/browse/JENKINS-37324)
    followup: Add an arguments column to the FlowGraphTable display
-   [JENKINS-36528](https://issues.jenkins-ci.org/browse/JENKINS-36528)
    Fix Environment Variables Handling: Include AbstractBuild Env vars
    in build variables
-   Optimization: eliminate need for reflection when calling
    getChangeSets
    - <https://github.com/jenkinsci/workflow-support-plugin/pull/41>

### 2.14

Release date: 2017-03-31

-   [JENKINS-42952](https://issues.jenkins-ci.org/browse/JENKINS-42952)
    Make `currentBuild.duration` work.
-   [JENKINS-42521](https://issues.jenkins-ci.org/browse/JENKINS-42521)
    Added a `currentResult` property and `resultIsBetterOrEqualTo` /
    `resultIsWorseOrEqualTo` methods to `currentBuild` and the return
    value of `build`.
-   [JENKINS-40934](https://issues.jenkins-ci.org/browse/JENKINS-40934)
    Speedup of log-related code run when adding a new step when using a
    massive number of `parallel` branches.
-   Robustness fix noted in
    [JENKINS-26137](https://issues.jenkins-ci.org/browse/JENKINS-26137).
-   Robustness fix associated with
    [JENKINS-42556](https://issues.jenkins-ci.org/browse/JENKINS-42556):
    tolerate errors encountered when printing progress of build
    resumption tasks.

### 2.13

Release date: 2017-02-13

-   Internal: `Timeout` utility to implement
    [JENKINS-32986](https://issues.jenkins-ci.org/browse/JENKINS-32986).

### 2.12

Release date: 2017-01-10

-   Internal: Test utility used to verify
    [JENKINS-40909](https://issues.jenkins-ci.org/browse/JENKINS-40909).

### 2.11

Release date: 2016-11-11

-   Optimization: don't throw away the Actions attached to a FlowNode
    when loaded from disk (avoids double-loading)
-   Small things:
    -   Remove SemaphoreListener (dead code from testing)

### 2.10

Release date: 2016-10-20

-   Regression in log handling with certain steps inside `parallel` in
    2.9.

### 2.9

Release date: 2016-10-19

> Do not use, there is a known regression which will be fixed shortly in
2.10.

-   Allow block-scoped steps to provide log output in addition to their
    what their bodies contribute
    ([JENKINS-34637](https://issues.jenkins-ci.org/browse/JENKINS-34637)
    related)
-   Make PauseAction implement PersistentAction so it consumes the API
    optimizations from
    [JENKINS-38867](https://issues.jenkins-ci.org/browse/JENKINS-38867)
-   Small things:
    -   Generics fix for JDK 9 support
    -   Add a getStatus method to Semaphore step (used in testing)

### 2.8

Release date: 2016-09-26

-   Restore use of the DepthFirstScanner API that was reverted
    in [JENKINS-38457](https://issues.jenkins-ci.org/browse/JENKINS-38457) now
    that its handling of parallels matches FlowGraphWalker

<!-- 2.7 was never released, check tags here: https://github.com/jenkinsci/workflow-support-plugin -->

### 2.6

Release date: 2016-09-23

-   [JENKINS-38457](https://issues.jenkins-ci.org/browse/JENKINS-38457)
    Show parallel branches in correct order (broken since 2.3).
-   Infrastructure for
    [JENKINS-38114](https://issues.jenkins-ci.org/browse/JENKINS-38114).

### 2.5

Release date: 2016-09-16

-   Clean up display of timing information in pipeline steps for a few
    edge cases (no start time on node, times under 1 ms)
-   Remove some obsolete approveSignature calls

### 2.4

Release date: 2016-09-09

-   Added timing information to pipeline steps step display (show how
    long a step or block ran for)

### 2.3

Release date: 2016-09-09

-   [JENKINS-37366](https://issues.jenkins-ci.org/browse/JENKINS-37366)
    Added properties `fullDisplayName`, `projectName`, and
    `fullProjectName` to `currentBuild` or return value of `build` step.

### 2.2

Release date: 2016-07-11

-   [JENKINS-30412](https://issues.jenkins-ci.org/browse/JENKINS-30412)
    Sandbox-friendly `changeSets` property for `currentBuild` or return
    value of `build` step.
-   [JENKINS-36306](https://issues.jenkins-ci.org/browse/JENKINS-36306)
    `duration` property for return value of `build` step.

### 2.1

Release date: 2016-06-16

-   Infrastructure for
    [JENKINS-26130](https://issues.jenkins-ci.org/browse/JENKINS-26130).
-   Fixed title of log pages from *Pipeline Steps*.

### 2.0

Release date: 2016-04-05

-   First release under per-plugin versioning scheme. See [1.x
    changelog](https://github.com/jenkinsci/workflow-plugin/blob/82e7defa37c05c5f004f1ba01c93df61ea7868a5/CHANGES.md)
    for earlier releases.
-   Various code moved out of this plugin into [Pipeline Nodes and
    Processes
    Plugin](https://plugins.jenkins.io/workflow-durable-task-step),
    [Pipeline Basic Steps
    Plugin](https://plugins.jenkins.io/workflow-basic-steps),
    [Pipeline Input Step
    Plugin](https://plugins.jenkins.io/pipeline-input-step),
    [Pipeline Build Step
    Plugin](https://plugins.jenkins.io/pipeline-build-step),
    and [Pipeline Stage Step
    Plugin](https://plugins.jenkins.io/pipeline-stage-step).
    You **must update** those plugins to 2.x if updating this plugin to
    2.x.
