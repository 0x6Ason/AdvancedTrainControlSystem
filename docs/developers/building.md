# Building and testing

--8<-- "README.md:building"

[Architecture](../ARCHITECTURE.md) explains how the code is organised; the unit tests run without a
server, and the behaviour on a real one is checked on a staging copy before each release.

## Continuous integration

Every push and pull request runs the *Build* workflow (`.github/workflows/build.yml`): it compiles
the plugin on JDK 21, runs the unit tests, checks that the generated reference pages under
`docs/reference/` match the code, and enforces the English-only text policy. The plugin jar is
attached to the run as an artifact. The *Docs* workflow builds this site with `mkdocs build
--strict`, so a broken link or a page missing from the navigation fails the run; it publishes to
GitHub Pages only when the repository variable `PUBLISH_DOCS` is `true`.

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
