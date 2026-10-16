## What changes for an operator or a rider

<!-- One or two sentences, the way the changelog will say it. -->

## Why

<!-- The issue, or the situation on a railway that needed it. -->

## Verified

<!-- What was tried on a real server: the set-up, the train, what was seen. "Unit tests only" is an answer too. -->

## Checklist

- [ ] Tests for the new rule or the fixed one
- [ ] README or docs updated; `./gradlew generateDocs` run if commands, signs or config.yml changed
- [ ] A line in CHANGELOG.md under *Unreleased*
- [ ] `./gradlew build` and `python -m mkdocs build --strict` pass
