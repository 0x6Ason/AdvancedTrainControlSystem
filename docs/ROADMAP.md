# Roadmap

Ideas for ATCS after 0.2.0, in the order they seem worth doing. Nothing here is promised; it is a
plan to think against. Every item keeps the principles in [ARCHITECTURE.md](ARCHITECTURE.md):
the logic is plain Java in the core and unit tested, Bukkit and TrainCarts only translate, files
stay readable, and an operator's work is never lost.

## The goal

ATCS should become *the* passenger-information and operations layer for TrainCarts railways:
a plugin any server drops in and gets, within an evening, what a real metro gives its riders
(announcements, boards, maps, a planner, fares) and its operators (a network model, control at
stations and junctions, a dispatcher's view, statistics), with an API other plugins build on.
Open source, well documented, and boring to run: it never breaks a world, never loses an
operator's work, and tells you why when something is off.

What "well developed" means here, in checkable terms:

- **Formats are stable.** `network.yml`, `fares.yml`, the website's JSON and the Java API are
  versioned and migrated, never silently changed. 1.0 is the release where they are frozen.
- **Everything that decides is tested** without a server, and the server-side glue is exercised
  on a staging server before each release (the compatibility matrix below).
- **Every feature is documented** before it ships: a reference page, an example, and the
  commands' own help text pointing to it. A pull request without docs is not done.
- **Errors teach.** Every refusal names the file, the line and the fix.
- **Nothing hidden.** Behaviour is in config and files people can read; learned data is
  disposable; the plugin is readable by someone who has not met the author.

Where it lands in the code: new packages `fare`, `display` (logos, roadmaps, holograms),
`editor` (the web editor's backend) and `selection` (the wand); the control features extend
`control`, the map work extends `map` and `web`. Everything persists in `network.yml` or `data/`
through `NetworkStore.edit`, so the existing rules (atomic writes, edits refused when the file
changed on disk) carry over.

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

## 6. Maps

Three maps exist today: the BlueMap layer (`BlueMapLayer`, markers per world), the website's
schematic diagram (`layout.js` lays it out, `map.js` draws it) and the recorded track behind both
(`TrackPaths`, first complete run of each segment, simplified). Improvements, by layer:

### Recorded track and the rail graph

- **Re-record on divergence.** A segment is recorded once and kept until the stops change. When
  trains are seen more than a few blocks off the stored path for a whole run, record it again
  instead of waiting for `/atcs service resetpaths`. Track rebuilt in the world then fixes itself.
- **A rail graph.** Merge every recorded path into one graph of the railway (nodes at junctions
  and platforms, edges with length). It gives maps a track layer independent of services,
  junction positions to draw, true distances for fares, and the sections the virtual block
  signals (item 5) protect. This is the foundation most of the rest stands on.
- **Elevation** is recorded already (y); use it: an elevation profile on the website's station
  page and a clearer underground/overground distinction (dimmer, dashed) on both maps.

### BlueMap

- **Smooth trains.** Train markers jump every two seconds. Instead of more frequent markers,
  ship a small script with the plaque that reads the website's `live` data and animates each
  train along the recorded track between updates, heading and line colour included. The POI
  markers stay as a fallback when the script is off.
- **Station icons** from the generated logos (item 2), sized by zoom; far out only interchanges
  and termini show, near in every station with its line badges. BlueMap's marker min and max
  distances do the hiding.
- **Live details.** A station's popup shows its next departures and links to its website page;
  a train's shows its service, next stop and arrival estimate. Markers are rewritten with the
  departures anyway, so this is mostly free.
- **One marker set per line**, plus *Stations*, *Walking transfers* and *Trains*, so BlueMap's own
  menu toggles each line.
- **Shared track drawn once.** Where two lines run the same recorded path, offset them side by
  side as the schematic does, rather than hiding one under the other; a thin dark outline keeps
  colours readable over bright terrain.
- **State on the map.** A suspended line is drawn grey and dashed; a closed station greyed; a
  held train marked.
- **Platforms as shapes.** With station footprints from the wand (item 3), draw each station as a
  shape marker, not only a pin.

### Website schematic

- **Layout that stays put.** The layout is recomputed from scratch in every browser. Save the
  chosen grid positions in `data/map-layout.yml` and start the next layout from them, so adding
  one station moves one station, not the whole diagram.
- **Manual overrides**, in `network.yml` under `map:` and from the editor's drag: pin a station to
  a cell, force a bend, hide a station (a depot), label side, and a per-line colour for the
  diagram when the badge colour is too light.
- **Transit-map details**: interchanges as joined circles (MTR style), termini as bars, line names
  at the ends of each line, a legend, platform numbers and transfers on hover, and the station
  logos as markers when zoomed in.
- **Live trains** on the diagram, interpolated between stations from the learned times.
- **Geographic mode**: a toggle that morphs the diagram onto the recorded track, so riders can
  relate the schematic to the world. Same data, second layout.
- **Status overlay**: suspended lines hatched, closures and planned works marked, driven by the
  disruptions in *More ideas*.
- **Export** the diagram as SVG and PNG, for printing, for wikis and for the roadmap and network
  displays in game. The layout runs in JavaScript; exporting from the editor is the cheap route,
  and a Java port of the layout is the long route that lets the server render displays itself.
- **Search and links**: a station search box, `#station=` links next to the existing `#plan=`.
- **Accessibility**: keyboard navigation, a readable station list for screen readers, and lines
  told apart by badge and pattern as well as colour.
- **Performance for big networks**: lay out once on the server (or cache the result in
  `network.json`) once the Java port exists; until then, cache per browser.

### In-game maps

- A hand-held **network map item** (`/atcs map`) that shows the diagram with the player's position,
  and a **roadmap** in item frames (item 3). Both reuse the display renderer.

## 7. Time estimation

Today an estimate is one smoothed number per service and segment (`TravelTimes`, exponential
smoothing with outliers damped), plus one smoothed dwell per stop, summed along the service by
`EtaCalculator`. The remaining time on the current segment is the learned time minus the time
elapsed, nothing else. That is right for a simple line and wrong in exactly the places a
complicated railway has: junctions where trains wait for each other, shared track where a local
holds up the express behind it, headway and section holds, termini with variable turn-arounds,
branches, long segments through stations without triggers. Each of those gets its own fix, and
the first three are the ones that matter most.

### Measure first

- **Record predictions against outcomes.** Every time an arrival is predicted and then happens,
  log the pair (`data/eta-samples.yml`, bounded). `/atcs timing report` shows the mean and the
  worst errors per service and stop, so every change below can be shown to help, and the stops
  that lie are found instead of guessed.
- **A replay harness in the tests**: feed `trips.yml` and the recorded tracks through the
  estimator and score it. Model changes then compete on the same data.

### Know where the train is, not only how long it has been gone

- **Progress along the recorded track.** Head positions are already sampled for `TrackPaths`.
  Record each segment's path with timestamps, so the stored path knows how long trains usually
  take from every point of it to the platform. The estimate then comes from the train's actual
  position on that path, not the clock. A train that is slow, held or stopped mid-segment stops
  counting down instead of sitting on *Arriving* for a minute.
- **Standing still means held.** When a train in transit has no speed, show *Held* (or *Waiting at
  Main St.* when ATCS itself holds it) rather than a shrinking number, and add the expected hold.
- **The approach as a final correction.** The time from the approach distance to the platform is
  nearly constant; learn it per stop and snap the estimate to it when the approach fires.

### Predict what ATCS controls instead of learning it

- **Split dwell from hold.** At an `atcs station` sign the configured dwell is known; the extra
  time is headway or section waiting. Learn the dwell cleanly and compute the hold from the rule:
  with `gap 60s` the train leaves at the later of arrival plus dwell and the previous departure
  plus sixty seconds; with `block` it leaves when the train in front reaches its next station,
  whose arrival is itself being estimated. Chain them.
- **One train per platform.** A train cannot arrive before the train ahead has left the platform
  and the separation has passed. Estimate all trains of a service together, in order, with that
  constraint, instead of each on its own. This is the single largest fix for shared track and
  bunched lines.
- **Junction merges.** With the rail graph (item 6) and junction priority (item 5) known, a train
  about to yield at a merge gets the other train's passing time added.
- **Turn-around at termini**: learn the time from arriving as one service to departing as the
  continuation separately from dwell, since it is a different thing with a different spread.

### Learn from less data, and the right data

- **Learn per physical segment, not per service.** A local and an express cover the same track
  between two stations; today each learns alone. Key the learned time by the two platforms and
  the recorded path, with a small per-service adjustment, so services share samples and a new
  express is accurate from its first run.
- **Context where it matters.** The segment after a junction depends on where the train came
  from, and peak hours hold more; keep a value per context (previous stop; time-of-day bucket)
  and fall back to the plain segment value when a context has too few samples.
- **Priors for unlearned segments.** Until a segment is learned, estimate it from the distance
  along the recorded track (or straight-line distance) at the railway's typical speed, shown in
  grey as an estimate, instead of *Expected*.
- **Stations without triggers.** A segment that spans an untriggered stop is never learned today
  (the sample is only taken between consecutive triggered stops). Attribute the measured time to
  the stops it spans in proportion to the track distance, and have `/atcs lint` name stops that
  have never reported.
- **Robust statistics.** Keep a median and a spread (P50 and P90) per value instead of one mean:
  show *3 to 5 min* where the spread is wide, say *Delayed* once a train is past its P90, and
  relearn fast after a sustained change (several samples all on one side) rather than at the
  smoothing rate. Discard samples from trains that were unloaded mid-segment (already the case)
  and from trains ATCS held, which are not travel time.

### Keep every display honest and consistent

- One estimator on the server, used by the boss bar, boards, SignLink, BlueMap and the website.
  The planner today re-implements the projection in JavaScript with fixed assumptions (a
  90-second wait, 15 seconds per change); write the projected arrivals into the live data and
  learn the real intervals per service and station, so *trains every ~4 min* replaces the guess.
- Never show *Arriving* for long: *Due*, then *Delayed*, each with the reason when ATCS knows it.

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
- **Installable** as a progressive web app with dark mode and an offline map.

### Integrations

- **PlaceholderAPI** *(quick win)*: `%atcs_next_dest_main%`, `%atcs_line%` for the player's current
  train, `%atcs_station%` for where they stand. Cheap and widely wanted.
- **Other maps**: Dynmap, Pl3xMap and squaremap behind the existing `MapLayer` abstraction.
- **GTFS export**: stations, routes and a timetable derived from the learned times, for fans who
  want the network in real transit apps and tools.

### Project

CI, releases, metrics and the API module are under *Open source* below.

- **Config migration**: `config-version` exists; add the migrator that rewrites old files and
  reports what changed, so updates never leave a half-valid config.
- **Paper plugin loader** (`paper-plugin.yml`): cleaner dependency declaration for TrainCarts,
  BlueMap, SignLink, WorldEdit and PlaceholderAPI as they are added.

## Open source

Deliberately last: the author wants the system finished and running well before it is handed
out. Until then the repository stays private (and once public, carries no licence, which means all
rights are reserved and nobody may legally use or fork the code), which is exactly the state wanted
while finalising. A private repository also means the docs site cannot be published on GitHub Pages
yet; it is built in CI and checked locally until then. Only the licence, the contributor material, publishing and the community items wait;
CI, tests, the docs site and the changelog discipline below are quality work and start now.

When the time comes, in order:

1. **A licence.** MIT or Apache-2.0 (Apache adds a patent grant and is the more usual choice for
   plugins other plugins build against). TrainCarts and BKCommonLib are compile-only
   dependencies, so either works. `LICENSE` at the root, a header note in `build.gradle.kts`,
   the licence named in `plugin.yml` and the README.
2. **Contributor basics**: `CONTRIBUTING.md` (how to build, run the tests, the staging server,
   the English text policy, the commit style), a code of conduct, issue templates (bug with the
   `/atcs status` output and versions, feature request) and a pull request template that asks for
   docs and a changelog line.
3. **Releases**: semantic versions, the changelog kept per release, a tag builds the jar in CI and
   attaches it; publish on Hangar and Modrinth with the same text; a `/atcs` console notice on a
   new release (opt-in).
4. **A place to talk**: GitHub Discussions for questions, Issues for bugs, and a Discord if there
   is appetite. Link all three from the README.
5. **Quality gates in CI**: the build, the tests, the text policy, a link check over the docs,
   Checkstyle or ErrorProne for the obvious mistakes, and once stable a headless Paper server with
   TrainCarts that loads the plugin and runs a smoke test over RCON (the `scripts/` folder already
   does this by hand on Windows; make it cross-platform and automatic).
6. **Compatibility matrix** in the README and tested in CI: Paper versions, TrainCarts versions,
   BlueMap and SignLink versions, Java versions.
7. **Opt-in metrics** (bStats) so the compatibility matrix follows what people actually run.
8. **A public API module**: a separate `atcs-api` artifact with the events (`TrainArrivedEvent`,
   `ServiceAssignedEvent`, `FareQuoteEvent`), the network read model and `FareApi`, published to
   a Maven repository with Javadoc, so other plugins depend on it without the whole plugin.

## Documentation

Good documentation is a feature, planned like one. The README today is the whole manual; it is
good and already too long for one page. The plan:

- **A docs site** from the `docs/` folder, built with MkDocs Material and published to GitHub
  Pages by CI on every push to `main`, versioned per release. The README becomes the front door:
  what it is, a screenshot, install, the two set-up paths, links into the site.
- **Organised by reader**, not by feature:
  - *Riders*: what they see and what each sound and message means. One page, with pictures.
  - *Operators*: set-up from an existing network; set-up by hand; recipes (a loop line, express
    and local on shared track, a terminus turn-around, a depot, two worlds, a fare system);
    references for every sign, every command, every permission, `config.yml`, `messages.yml`,
    `network.yml`, `fares.yml`, the website and each integration; troubleshooting and FAQ.
  - *Developers*: architecture, the API and events, the data files' JSON schemas, building,
    testing, the staging server, contributing.
- **References generated from the code.** A Gradle task dumps the command tree, the permissions,
  the sign options and `config.yml` with its comments to Markdown, and CI fails when the generated
  pages differ from the committed ones. Docs then cannot drift from the plugin.
- **A glossary**: station, platform, trigger, line, service, stop, run-through, continuation,
  fleet, headway, section, display. Every page links terms to it.
- **Pictures and short clips** for anything spatial: where to stand for `station here`, how a
  station sign reads, what a roadmap display looks like, the editor.
- **An example network** shipped in `docs/examples/`: a small `network.yml` and `fares.yml` with
  every feature used once, and the test fixtures already captured from a real server, so readers
  can copy rather than compose.
- **Help in the game.** `/atcs help <topic>` and every error message link to the matching docs
  page; the first run prints the three links that matter.
- **Doc quality in CI**: the English text policy, a link checker, spelling, and "every command in
  the tree has a reference page" as a test.
- **Changelog discipline**: every user-visible change gets a line in `CHANGELOG.md` in the same
  pull request, written for operators, not developers.

## Suggested order

1. CI with the build and tests, the docs site skeleton, generated references. Private quality
   work that makes everything after it checkable.
2. Measure the estimates (`/atcs timing report`, the replay harness), then the three big
   estimation fixes: progress along the track, holds predicted from the rules, one train per
   platform.
3. Fares and `/atcs fare check`: fixes a live problem, pure core work.
4. Quick wins that need little design: network undo, lint, PlaceholderAPI.
5. Logos, then the BlueMap improvements that use them (icons, per-line sets, live details).
6. The wand, then roadmap displays and hologram boards on top of it.
7. The rail graph and re-recording, then the schematic's stable layout, overrides and live
   trains, and the second round of estimation (shared segments, context, junction merges).
8. Editor backend and the network editor views, with map overrides and export.
9. Control, starting with even-spacing regulation, then blocks on the rail graph, timetables,
   then the dispatcher view.
10. Polish: compatibility matrix in CI, performance on big networks, format migrations, the
    estimation error report at a level the author is happy to publish.
11. Open source: licence, contributor material, the public API module, Hangar and Modrinth.

## Milestones

| Version | Theme |
|---|---|
| 0.2 | The redesign now on `main`: network model, journeys, passenger information, boards, maps, website, station and switch signs |
| 0.3 | CI and docs site, measured estimates and the first estimation fixes, fares, undo and lint, PlaceholderAPI |
| 0.4 | Logos, wand, displays, hologram boards, BlueMap improvements |
| 0.5 | Rail graph, schematic layout work, second round of estimation, web editor |
| 0.6 | Control: regulation, blocks, timetables, dispatcher |
| 0.7 | Polish: compatibility matrix, performance, migrations, complete docs |
| 1.0 | Stable formats and API, licence, public API module, published on Hangar and Modrinth |
