# Changelog

## 0.2.0 (unreleased)

A complete redesign. ATCS is now a passenger-information and network plugin that works on any
TrainCarts server, configured with files and in-game commands.

### Added

- Fleets: ATCS keeps a configured number of trains running on a service, spawning them itself
  at a spawn point, for lines without spawn signs (`/atcs service fleet`).
- Train naming after the service (`A-to-main-2`), keep-chunks-loaded for every train, and plugin
  chunk tickets for spawn signs and fleet spawn points, so the railway runs without force-loading.
- Service inference from the stations a train visits, for trains no spawn sign or sign ever
  assigned (loop lines); `/atcs train auto` and a manual mode for `/atcs train unassign`.
- Network model in `network.yml`: stations, platforms with trigger signs, lines with colours and
  badges, directional services with planned platforms, run-through stops, circular services,
  continuations at termini, automatic assignment rules and spawn-sign bindings, departure boards.
- Journey tracking from TrainCarts sign events, tolerant of long trains, repeated events, unloaded
  trains and stations without triggers.
- Passenger information: line-coloured boss bar with next stop and arrival estimate, arrival titles,
  chimes, next-station, interchange and end-of-line announcements, approaching and passing notes,
  station titles for trains without a service, line suspension notices.
- Learned travel and stop times per service and stop, used for arrival estimates.
- Departure boards on ordinary signs; SignLink variables per station and per platform with names
  short enough to type on a sign (`%gb.1t%`, `%gb.p3.1d%`), from short station codes.
- `/atcs station here` names the station and platform you stand on, its trains and its sign
  variables.
- BlueMap integration: stations, lines along recorded track, live trains.
- Set-up from the existing world: stations drafted from TrainCarts station signs and arrival
  announcements; lines and services suggested from observed train trips, including spawn signs,
  express variants and turn-arounds; reviewed through `import-proposal.yml`.
- ATCS TrainCarts signs: `atcs stop`, `atcs service`, `atcs clear`, and `atcs indicator`, which
  drives levers while a train is at, approaching or just past a station, or between two stations,
  for lamps, track diagrams and barriers without any wiring along the track.
- `atcs station` signs: a platform that runs itself. Trains whose service calls at the station stop
  (TrainCarts' own station mechanics: centring, levers, dwell, launch direction, `route`), trains
  whose service runs through keep going, so express and local services share one track. Optional
  headway (`gap 60s`) and section holding (`block`) keep following trains apart; `control:` in
  config.yml sets the defaults and what trains without a service do.
- `atcs switch` signs: a junction set per train by service, line or next station, with the rules
  written on the sign. With `control.set-destinations` every train's TrainCarts destination follows
  its next calling stop, so TrainCarts switcher signs route by path-finding as well.
- `/atcs station convert`: turns the TrainCarts station signs of every platform in network.yml into
  `atcs station` signs, keeping their delay, direction, `route`, offset, launch distance and speed,
  and leaves every other sign (refill stops, test stations) alone. Without `confirm` it only lists
  what it would change.
- The website diagram stays put: a browser lays the next network out from the layout it drew,
  the server hands out `data/map-layout.yml` when the editor saved one, and `?fresh` starts over.
  Hand adjustments under `map:` in network.yml: `/atcs station map <station> pin|unpin|hide|show|label`
  and `/atcs line mapcolor`. Live trains glide along the diagram between refreshes, with a button
  to hide them.
- Recorded track fixes itself: when trains run clearly off a stored path twice in a row (track
  rebuilt in the world), the path is recorded again instead of waiting for `/atcs service resetpaths`.
- The rail graph: every recorded path merged into one graph of the railway, with a node per
  platform, junction and line end and the length of each stretch; shown by `/atcs status`, drawn
  on BlueMap as a *Track* set with junction markers, and the base for track distances and sections.
- The wand: `/atcs wand` selects two corners (WorldEdit's selection is used when there is one);
  `/atcs station footprint` records the box a station occupies, used by `/atcs station here` and
  drawn on BlueMap, and `/atcs station bindall` binds every TrainCarts station sign in it at once.
- Displays: `/atcs display roadmap|logo|board|map|status` fill the item frames you selected with
  one picture drawn on map items (a strip diagram with "you are here", the station logo, a
  departure board that keeps updating, the network map along the recorded track, a service status
  panel); `/atcs display hologram` places a floating departure board on a text display entity;
  `/atcs map` hands out a map of the railway that shows where you are. All recorded under
  `displays:` in network.yml, redrawn after restarts, removed with `/atcs display remove`.
- Station logos and line badges, generated from the network (a ring in the line colours with the
  station's short code), with `logos/<station>.png` and `logos/line-<id>.png` as overrides.
- BlueMap: one marker set per line plus Stations, Walking transfers and Trains; stations drawn as
  their logos and trains as line badges; small stations fade out far away; station popups list the
  next departures and link to the website; train popups say where the train goes and when; lines
  on a shared stretch of track run side by side; suspended lines turn grey; held trains are marked.
- `/atcs undo` and `/atcs history`: every command edit keeps the previous network.yml (the last
  25), and undo restores it.
- `/atcs lint`: stations without a platform or sign, platforms without triggers, stations no
  service calls at, lines without services, stops listed twice, boards whose signs are gone,
  transfers that cannot be estimated, fares that name nothing and pairs with no route, each with
  what to do about it.
- PlaceholderAPI placeholders: `%atcs_line%`, `%atcs_next%`, `%atcs_eta%` and more for the
  player's train, `%atcs_next_time_<station>%` and friends for a station's departures,
  `%atcs_fare_<from>:<to>%`.
- Fares: `fares.yml` with explicit pairs, zones and a formula over the route (stops, changes,
  track distance); `/atcs fare check` explains a fare; `/atcs fare generate` and `apply` draft and
  load the whole matrix with hand corrections kept; the planner shows fares on route cards; a
  `FareApi` service and `FareQuoteEvent` for other plugins.
- Arrival estimates take the train's place on the recorded track, standing trains and the time
  a station sign holds a train into account, and every train of a service is projected in running
  order so a follower is never shown arriving before the train in front has left. A replay harness
  scores the estimator on logged data.
- Estimates for stretches nobody has timed yet, from the track length at the railway's usual
  speed (`timing.assumed-speed` until enough is learned), shown as `~1:05` instead of *Expected*;
  travel times shared between services on the same stretch of track, so a new express has an
  estimate from its first run; a train on its way longer than trains usually take, by more than
  the usual spread, is shown as *Delayed* on boards, the boss bar, SignLink, placeholders, BlueMap
  and the website instead of a countdown that reached zero; a stop without triggers no longer
  loses the run through it (the measured time is shared out by track distance); and the planner
  waits half the learned interval between departures instead of a fixed guess.
- Arrival estimates are scored against real arrivals: `/atcs timing report` shows the mean and
  worst error per service and stop at two minutes, one minute and thirty seconds out, so changes
  to the estimator can be checked and the stops that lie can be found.
- An event log, `data/events.log`: every arrival, departure, hold and service change as one JSON
  line, bounded by rotation (`logging:` in config.yml), with train positions optional.
- `/atcs line addstop <line> <stop> between <a> <b>`, `/atcs service addstop` and
  `/atcs service removestop`: add or drop one stop without retyping the list. Learned travel times,
  stop times, platforms and recorded track are carried over for the stops that stay.
- Service recording by riding a train.
- A website (schematic network map, departures, trains in service, realtime trip planner over the
  actual running trains), written as static files into BlueMap's web root or any folder, with a
  plaque on BlueMap's page linking to it. The map is laid out as a subway diagram: an even grid
  that keeps every station's real order, 45-degree lines with rounded bends, no line through a
  station it does not serve. The trip planner offers several routes (express or local, fewer
  changes, walking or not) with live countdowns; on phones the panel is a draggable sheet over the
  map.
- Walking transfers between nearby stations (`/atcs transfer`, `transfers:` in network.yml): a
  dotted connector on the map, walked by the trip planner, and mentioned in arrival
  announcements.

### Changed

- Runs on Paper 1.21.4 and newer with any TrainCarts 2.x build (0.1 required Paper 1.21.11 and exactly
  TrainCarts 2.0.0).
- The plugin JAR no longer bundles SQLite or Gson.

### Removed

- The 0.1 prototype's evidence/candidate review pipeline, embedded web administration site, SQLite
  database, Anvil region scanner and WebGL map. On first start their files are moved into
  `legacy-0.1/` in the plugin folder.
