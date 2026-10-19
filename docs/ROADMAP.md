# Roadmap

Ideas for what comes after 0.2, roughly in the order they seem worth doing. Nothing here is
promised; an issue saying which of them would help your railway, and how, is the best way to move
one up. Every item keeps the principles in [Architecture](ARCHITECTURE.md): the logic is plain
Java in the core and unit tested, Bukkit and TrainCarts only translate, files stay readable, and
an operator's work is never lost.

## Towards 1.0

1.0 is the release where the formats are frozen: `network.yml`, `fares.yml`, the website's JSON
and the Java API are versioned and migrated from then on, never silently changed.

- **Releases on Hangar and Modrinth**, with the same text as the GitHub release.
- **A public API module**: a separate `atcs-api` artifact with the events (train arrived, service
  assigned, fare quoted), a read-only view of the network and `FareApi`, published with Javadoc
  so other plugins can depend on it without the whole plugin.
- **Opt-in metrics** (bStats), so the compatibility matrix follows what servers actually run.
- **A Paper plugin descriptor** (`paper-plugin.yml`) for cleaner optional dependencies.
- **A headless smoke test in CI**: a Paper server with TrainCarts that loads the plugin and runs a
  few trains, on every pull request.

## Riders

- **Doors-closing countdown** on the action bar during the dwell at an `atcs station` sign.
- **Platform announcements for people waiting**: players inside a station's footprint hear the
  next train announced, not only riders.
- **Per-player preferences** (`/atcs prefs`): mute chimes, hide the boss bar, compact chat.
- **Translations**: `messages_<locale>.yml`, picked per player from the client's language.
- **Per-line sounds**: a jingle per line or an arrival chime per station, from a resource pack.
- **The remaining stops on the scoreboard** while riding, with estimated times.
- **Disruptions beyond suspending a line**: station closures, diversions and planned works with a
  start and an end, on boss bars, boards, the website and in the planner.

## Operators

- **Performance statistics**: punctuality, headway adherence, trips per hour and journey times per
  service, on the website and in `/atcs service info`.
- **Rolling stock per service**: a fleet spawns a TrainCarts saved train, so each line has its own
  look.
- **Webhooks** (Discord and others) for trains held too long, trains that cannot be spawned and
  stuck trains.
- **Occupancy**: seats taken per train, on boards and the website.

## Website and maps

- **Station pages**: the station's sign, platform diagrams, departures, transfers and fares, with a
  link to share.
- **A service status page**: every line with good service, delays or suspended.
- **Other map plugins**: Dynmap, Pl3xMap and squaremap behind the same map layer as BlueMap.
- **GTFS export**: stations, routes and a timetable from the learned times, for real transit apps.

## Documentation

- **Recipes**: a terminus turn-around, express and local on shared track, a depot, two worlds.
- **A glossary** of the terms the docs use (platform, trigger, service, run-through, fleet,
  headway, block), linked from every page.
- **Help in the game** that links to the matching page: `/atcs help <topic>` and error messages.
