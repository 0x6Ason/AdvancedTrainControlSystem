# Contributing

Thank you for looking at ATCS. The repository is private while the system is being finished and
no licence has been chosen yet, so for now contributions are by invitation; everything below
applies all the same, and will when the project opens up.

## Before you start

- **Bugs**: open an issue with the output of `/atcs status`, the server's and TrainCarts'
  versions, what you did, what you expected and what happened. The *Bug report* template asks for
  exactly that. A `data/events.log` excerpt around the time helps for anything about trains.
- **Features**: open an issue first and say what you are trying to do on your railway, not only
  what you want added. [The roadmap](docs/ROADMAP.md) says what is planned and in what order;
  something already on it is a good sign.
- **Questions**: Discussions, once the repository is public; issues until then.

## Building and testing

```
./gradlew build          # compiles, runs the tests, checks the generated docs and the text policy
./gradlew generateDocs   # rewrites docs/reference/* after a change to commands, signs or config.yml
./gradlew generatePreviews
python -m mkdocs build --strict
```

JDK 21 and a network connection for the first build. The tests run without a server: the core
(`network`, `operation`, `control`, `fare`, `editor`) is plain Java and is where new logic goes;
`traincarts`, `map`, `display` and `runtime` are the adapters that touch Bukkit and TrainCarts,
kept thin. [Architecture](docs/ARCHITECTURE.md) has the map; [Building and
testing](docs/developers/building.md) the details.

Before a pull request, run something real: a Paper server with TrainCarts, a copy of a railway,
and the feature exercised with a train. "Verified on a staging copy" in the description, with
what was tried, is worth more than a long explanation.

## What a change needs

1. **A test** for every rule the change adds or fixes, in the core packages. Estimation and
   control changes have fixtures captured from a real server under `src/test/resources/fixtures/`
   and a replay harness; use them.
2. **Documentation** in the same pull request. The README is the manual's single text and the
   docs site reuses its sections; a new command, sign option or config key also needs
   `./gradlew generateDocs` run and the result committed, or CI fails.
3. **A changelog line** in `CHANGELOG.md` under *Unreleased*, written for operators: what they
   can do now, not which class changed.
4. **English only** in code, bundled files and docs; the build rejects other scripts. Passenger
   texts live in `messages.yml`, where operators translate them.

## Style

- Match the code around you. Comments say why, not what; most methods have a one-line summary
  that reads like a sentence. No framework, no reflection, no dependency added for one call.
- Core logic never imports Bukkit. Anything that needs the server goes through a small
  interface (`TrainHandle`, `TrainSource`, `StationLocator`) with a fake in the tests.
- Nothing server-specific: no station names, coordinates or line colours in code. Everything an
  operator might want different is in `network.yml`, `config.yml` or `messages.yml`.
- Commit messages are one line that says what changed for the user, with an area prefix when it
  helps: `Control: even spacing, timetables, virtual blocks`. The body, when there is one, says
  why and what was verified.

## Pull requests

One change per pull request, rebased on `main`, with the template filled in. CI must be green:
the build and tests on the baseline versions and on the compatibility matrix (the newest 1.21
Paper and the current Paper 26 with the newest TrainCarts), the generated references, the text
policy and the docs site. Reviews look at behaviour on a railway first and code second.
