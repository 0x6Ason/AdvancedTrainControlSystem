# Roadmap

Ideas for ATCS after 0.2.0, in the order they seem worth doing. Nothing here is promised; it is a
plan to think against. Every item keeps the principles in [ARCHITECTURE.md](ARCHITECTURE.md):
the logic is plain Java in the core and unit tested, Bukkit and TrainCarts only translate, files
stay readable, and an operator's work is never lost.

Where it lands in the code: new packages `fare`, `display` (logos, roadmaps, holograms),
`editor` (the web editor's backend) and `selection` (the wand); the control features extend
`control`. Everything persists in `network.yml` or `data/` through `NetworkStore.edit`, so the
existing rules (atomic writes, edits refused when the file changed on disk) carry over.

## 1. Fares and the Essencard override

A card plugin only knows the entry and exit gates. ATCS knows the network: the shortest route
between two stations, the real track length from `TrackPaths`, stop counts and line changes. That is
where a correct fare comes from.

- `fares.yml` with three layers, the highest that applies wins:
  1. an explicit origin-destination pair (`main -> golden-beach: 12`), either direction;
  2. a zone pair (`zones: {main: 1, golden-beach: 2}`, `zone-fares: {1-2: 8}`);
  3. a formula: flat, per stop, distance bands, plus a penalty per line change.
- `/atcs fare generate` writes the whole origin-destination matrix as a draft from the formula and
  zones; the operator corrects the wrong ones by hand; `/atcs fare apply` loads it. Same
  draft-review-apply flow as the importer. Pairs edited by hand are marked so a later `generate`
  never overwrites them.
- `/atcs fare check main golden-beach` prints the fare and the reasoning: which layer answered,
  the route it assumed, the distance and the stop count. This is the actual fix for "the price is
  never right": it becomes possible to see why.
- Integration: a `FareApi` registered with Bukkit's `ServicesManager` (`quote(from, to)`) and a
  `FareQuoteEvent` other plugins can read or override. Essencard asks ATCS instead of guessing.
  Entry and exit stations can also come from ATCS itself once station footprints exist (see the
  wand), so a gate plugin does not have to know where stations are.
- The website's trip planner shows the fare on each route card.

## 2. Station logos

Generated, deterministic, no files needed: a roundel whose ring is split into the colours of the
lines calling at the station, with the station's short code (`gb`, `main`) in the middle and the
line badges around it.

- Drawn with Java2D to PNG for BlueMap markers and in-game maps, and as SVG for the website; one
  description, two renderers. No new dependency.
- `logos/<station>.png` in the plugin folder overrides any one logo. Line badges get the same
  treatment (`logos/line-<id>.png`).
- Used on BlueMap markers, website station pages and planner cards, the roadmap displays below,
  and `/atcs station info`.

## 3. The wand and roadmap displays

A platform roadmap is the strip diagram of a line with "you are here", drawn on Minecraft map items
held by item frames on the platform wall.

- `/atcs wand`: left and right click two corners of a selection, WorldEdit-style. When WorldEdit
  is installed its current selection is used instead (soft dependency through its API).
- The operator places the item frames, selects them, and runs
  `/atcs display roadmap 1/north main`. ATCS fills the frames as one mosaic (a 4x1 strip for a
  long line) through `MapRenderer`, and records the display under `displays:` in `network.yml`
  so it survives restarts and `/atcs display remove` can take it down.
- Other display kinds on the same mechanism: a station logo, the network map, a departure board
  as a picture, a service status panel, a QR code linking to the website's station page.
- Honest caveat: this is the first time ATCS puts something into the world. It stays opt-in and
  limited to frames the operator selected, as boards only touch signs the operator bound.
- The wand pays off elsewhere: bind every TrainCarts station sign in a selection at once, and
  define a station's footprint (used for fare zones, platform announcements and `/atcs station
  here` without a sign nearby).

## 4. Web editor

A browser editor built on the existing website code, not an in-game chest GUI: stop lists,
services and continuations are miserable in a 9x6 inventory. In-game stays for things that need a
position, which the wand covers.

- A small HTTP API on the JDK's built-in `HttpServer` (no dependency), own port, off by default,
  listening on localhost unless configured otherwise so it can sit behind the operator's reverse
  proxy.
- `/atcs editor` gives a one-time link with a short-lived token; sessions expire. Every save goes
  through `NetworkStore.edit`, so a hand edit on disk is still never clobbered, and the editor
  shows the refusal with a diff instead of failing silently.
- Views: the map with draggable stations (sets map order, not world position), lines and services
  with drag-to-reorder stops and run-through markers, platforms and their triggers, boards,
  transfers, the fare matrix, displays, and config.
- Validation while typing, with the same messages the commands give.
- Later, the dispatcher panel (item 5) lives here too.

## 5. Advanced control

Builds on `StationControl`, `HoldAction` and the switch signs, roughly by value:

- **Even-spacing regulation** for loop lines: a train that is catching up the one in front is held
  a little at the next platform so headways equalise, instead of a fixed `gap`. Ends bunching.
- **Timetables**: departures at fixed clock times (`atcs station` option `at :00 :15 :30 :45`, or
  a timetable per service in `network.yml`), service hours, and peak and off-peak fleet sizes so
  trains stable at night and come out in the morning.
- **Virtual block signals**: an `atcs block` sign pair protecting any stretch of track, not only
  station to station; indicator signs show the block state.
- **Junction priority**: at a merge, the late train or the higher-priority line goes first; the
  other waits at its last station sign.
- **Service recovery**: short-turn or skip-stop a late train, with passengers told; a free-platform
  picker at termini with several platforms.
- **Speed by service**: an `atcs speed` sign or a per-service speed limit, so an express runs faster
  than a local on shared track.
- **Dispatcher console** in the web editor: every train live, hold, release, suspend a line, set a
  switch by hand, send a train to the depot.

## More ideas

Grouped, with the cheap ones marked *(quick win)*.

### Passenger experience

- **Hologram departure boards** *(quick win)*: boards and roadmaps on `TextDisplay` entities
  instead of sign lines. Readable from far away, any length, line colours, no SignLink needed.
  Placed with the wand like displays, recorded in `network.yml`.
- **Doors-closing countdown** on the action bar during the dwell at an `atcs station` sign; ATCS
  knows the dwell exactly.
- **Platform announcements for people waiting**: players inside a station footprint hear "Train to
  Golden Beach arriving at platform 2", not only riders. Needs footprints from the wand.
- **Per-player preferences** `/atcs prefs`: mute chimes, hide the boss bar, compact chat, language.
- **Translations**: `messages_<locale>.yml`, picked per player from the client locale. The English
  text policy in the build stays for the code; translations live in resource files of their own.
- **Per-line and per-station sounds**: a jingle per line or arrival chime per station, from a
  resource pack, in `network.yml` next to the colour.
- **Stops list on the scoreboard sidebar** while riding: the remaining stops with estimated times,
  interchanges marked.
- **Occupancy**: seats taken per train on boards and the website (seat events are already read).
- **Disruptions beyond suspend**: station closures, diversions and planned works with a start and
  end time, shown on the boss bar, boards, website status page and the planner (which routes
  around them).

### Operations and reliability

- **Network versions and undo** *(quick win)*: keep the last N copies of `network.yml` on every
  edit, `/atcs undo`. Fits "never lose an operator's work".
- **`/atcs lint`** *(quick win)*: platforms without triggers, services whose stops cannot be reached
  in order on the recorded track, lines without services, stations without a position, boards on
  signs that are gone, fares missing for a pair.
- **Stuck and lost train detection**: a train in service with no observation for N minutes is
  reported, and a fleet train can be replaced automatically; a console warning and an optional
  webhook (Discord) for held-too-long, cannot-spawn and lost trains.
- **Event log** `data/events.log`: every arrival, departure, hold and assignment with a timestamp,
  for debugging after the fact instead of watching `/atcs debug` live.
- **Performance statistics**: punctuality, headway adherence, trips per hour and average journey
  time per service, from `TripLog` and the timings, on the website and in `/atcs service info`.
- **Rolling stock per service**: fleets spawn a TrainCarts saved train (`/atcs service fleet ...
  train:<saved-name>`) so each line has its own look.
- **Scenario simulator in tests**: a fake railway that plays trips through `JourneyTracker`,
  `StationControl` and the timetable, so control features can be tested without a server.

### Website

- **Station pages**: logo, platform roadmaps, departures, transfers, fares from here, a shareable
  link, and the QR code the in-game display shows.
- **Service status page**: every line with good service, delays or suspended, like a real
  operator's status board, driven by the disruptions above.
- **Live trains on the schematic map**: positions interpolated between stations from the learned
  times, not only in BlueMap.
- **Installable** as a progressive web app with dark mode and an offline map.
- **Documented data files**: the JSON the site reads is already a public API in effect; give it a
  schema and a page so people can build their own apps on it.

### Integrations

- **PlaceholderAPI** *(quick win)*: `%atcs_next_dest_main%`, `%atcs_line%` for the player's current
  train, `%atcs_station%` for where they stand. Cheap and widely wanted.
- **Other maps**: Dynmap, Pl3xMap and squaremap behind the existing `MapLayer` abstraction.
- **GTFS export**: stations, routes and a timetable derived from the learned times, for fans who
  want the network in real transit apps and tools.
- **Public API jar**: `FareApi`, network read access and events (`TrainArrivedEvent`,
  `ServiceAssignedEvent`, `FareQuoteEvent`), published so other plugins compile against it.

### Project

- **CI** *(quick win)*: a GitHub Actions workflow running `./gradlew build` on every push and
  pull request, and a release workflow that attaches the jar to a tag.
- **Config migration**: `config-version` exists; add the migrator that rewrites old files and
  reports what changed, so updates never leave a half-valid config.
- **Opt-in metrics and update notice**: bStats and a one-line console notice on a new release.
- **Paper plugin loader** (`paper-plugin.yml`): cleaner dependency declaration for TrainCarts,
  BlueMap, SignLink, WorldEdit and PlaceholderAPI as they are added.

## Suggested order

1. Fares and `/atcs fare check`: fixes a live problem, pure core work.
2. Quick wins that need little design: network undo, lint, PlaceholderAPI, CI.
3. Logos: cheap, makes the map and website look finished.
4. The wand, then roadmap displays and hologram boards on top of it.
5. Editor backend and the network editor views.
6. Control, starting with even-spacing regulation, then timetables, then the dispatcher view.
7. The rest as they come up.
