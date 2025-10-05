# Pipeline: Supporting APIs Plugin

## Introduction

This plugin provides APIs that are used by core Pipeline plugins for features such as persistence and step visualization.

## Version History

See [the changelog](CHANGELOG.md).

## Caveat emptor

### Overheads of WithThreadName feature

Some consumers of this plugin improve readability of JVM thread dumps by using
the `org.jenkinsci.plugins.workflow.support.concurrent.WithThreadName` feature
to temporarily clarify the purpose of that thread.

This ends up calling `Thread.setName()` => `JVM_SetNativeThreadName()` => some
native OS implementation (via `pthreads` or otherwise), both to enter and exit
a context with this feature.

In some use-cases, e.g. with a generated `parallel` pipeline with hundreds of
stages then waiting in a Jenkins queue for considerable time, this can amount
to tens of thousands of native (not very efficient on some systems) calls per
second just for this troubleshooting aid, and can cause considerable slow-down
of the Jenkins controller.

If you suspect your controller's performance is impacted by this, please
configure a JVM property or context setting
`org.jenkinsci.plugins.workflow.support.concurrent.WithThreadName.enabled=false`
to disable the feature's native calls.

You can still trace attempts to rename the threads by adding a Jenkins log
listener for the class name at `FINE` or louder level, and watching the
log entry stream via Web UI or automatically-rotating files on the controller.
