# Building and testing

--8<-- "README.md:building"

[Architecture](../ARCHITECTURE.md) explains how the code is organised; the unit tests run without a
server, and the behaviour on a real one is checked on a staging copy before each release.

## Continuous integration

Every push and pull request runs the *Build* workflow (`.github/workflows/build.yml`): it compiles
the plugin on JDK 21, runs the unit tests, checks that the generated reference pages under
`docs/reference/` match the code, and enforces the English-only text policy. The plugin jar is
attached to the run as an artifact. A second job, the compatibility matrix, repeats the build and
the tests against the newest Paper of the 1.21 line and the current Paper 26 (on JDK 25) with the
newest TrainCarts and BKCommonLib, so the README's requirements table is tested, not promised. The
versions are Gradle properties (`-PpaperApi`, `-PtrainCarts`, `-PbkCommonLib`, `-PsignLink`,
`-PjavaVersion`), so the same can be run locally. The *Docs* workflow builds this site with `mkdocs build
--strict`, so a broken link or a page missing from the navigation fails the run; it publishes to
GitHub Pages only when the repository variable `PUBLISH_DOCS` is `true`.

## Releases

Pushing a tag such as `v0.2.0` runs the *Release* workflow (`.github/workflows/release.yml`): it
builds and tests the plugin from the tag and publishes a GitHub release with the jar attached and
the version's section of `CHANGELOG.md` as the notes. The tag must match `version` in
`build.gradle.kts`, and the changelog needs a `## 0.2.0 (date)` section, or the workflow stops
before publishing anything.

## A big railway in the tests

`BigNetworkTest` builds a railway of 300 stations, 24 services and 150 running trains and times
what the server does every second (the consistent estimates, departures at every station, the
website's live file, a boss bar view per train) and the one-off jobs (reading and writing
`network.yml`, lint, the website's network file, recording every stretch of track and building
the rail graph). Each has a budget the test enforces; the per-second work comes to a few tens of
milliseconds, so a railway that size runs on a tick without being noticed.

## Generated reference pages

`docs/reference/commands.md`, `signs.md`, `permissions.md` and `config.md` are written by
`./gradlew generateDocs` from the command tree, the sign parsers, `plugin.yml` and the bundled
`config.yml`. A test compares them with the committed files, so the reference cannot drift from
the plugin: after changing a command, a sign option, a permission or the config, run the task and
commit the result.

## The docs site locally

```
pip install mkdocs-material
mkdocs serve
```

Pages under `docs/operators/` reuse sections of `README.md` through snippet markers
(`<!-- --8<-- [start:signs] -->`), so the README stays the single text to keep right.
