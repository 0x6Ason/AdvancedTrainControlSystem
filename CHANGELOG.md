# Changelog

## Unreleased

### Added

- Pricing rules for fares (`/atcs fare pricing`): `cumulative` adds the formula, the zone prices and
  the pair prices of every hop of the route actually ridden; `highest` and `lowest` take one of
  them; `custom` takes a calculation of your own (`/atcs fare calculation`, with `+ - * /`, `sum`,
  `min` and `max`). `legacy`, the old pair, else zones, else formula, stays the default.
- Outside `legacy`, a journey that returns past where it began stays one journey priced over the
  whole route, and the fare shown on getting off lists what it is made of. `/atcs fare receipt`
  shows a receipt's full calculation and route.
- The web editor's fares page chooses the pricing rule, builds a custom calculation from rows and
  groups, and previews a journey of one or more rides against the draft.

### Changed

- `fares.yml` is format 2 and `fare-ledger.json` version 2. Both are read from older versions;
  a ledger written by this version cannot be read by 0.2.2 or before.

## 0.2.2 (2026-10-27)

### Added

- Passengers can end a journey and pay at once instead of waiting for the transfer window to
  close: an **[End trip]** button follows the fare shown when they get off (message
  `fare.end-trip`), or `/atcs fare endtrip`. Staff can end a player's journey by name.

## 0.2.1 (2026-10-27)

### Added

- Automatic fares: passengers are charged through Vault for the journey they actually rode, when
  it ends, without gates or cards (`/atcs fare billing on`). Free lines, a transfer window with a
  discount for changing to another paid line, and a journey minimum, also for leaving at the
  station you boarded. Passengers who cannot pay are kept off paid trains; receipts, and payments
  the economy could not confirm, are settled with `/atcs fare receipts` and `/atcs fare resolve`.
  Journeys and receipts are kept in `fare-ledger.json`. Vault is a soft dependency.

### Fixed

- Circular-line arrival estimates no longer accumulate phantom laps of waiting time. Each train's
  next visit to a stop is projected once, and platform queues follow the order of approach to that
  stop, including across the last-to-first boundary. Boards and onboard displays share the fix.
- Trains on the same segment are ordered by recorded track progress (or elapsed travel time when
  no position is available), rather than by the order in which the trains were tracked.

## 0.2.0 (2026-10-19)

A complete redesign. ATCS is now a passenger-information and network plugin that works on any
TrainCarts server, configured with files and in-game commands. It is open source from this
release, under the MIT licence.

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
- Control: `even` on a station sign (or `control.even-spacing`) holds a train that is catching
  up the one in front so a service's trains spread out instead of bunching; `at :00 :30` or
  `at /15` on a sign, or a service's `timetable:`, makes trains leave at fixed minutes past the
  hour; `atcs block` sign pairs let one train at a time through any stretch of track, with
  `block <id>` indicators; `atcs speed` signs and a service's `speed:` set speed limits so an
  express runs faster than a local on shared track; a dispatcher can hold and release trains;
  fleets get service hours and an off-peak size (`/atcs service hours`, `offpeak`), and trains
  beyond the hour's number are stabled at the end of their trip; junction priority
  (`control.junction-priority`, `priority`) makes a train wait at its platform for a train about
  to cross the junction ahead, the late train or the listed line first; `/atcs train skip`,
  `/atcs switch list|set|clear`; a Dispatch section in the web editor with every train live,
  holds, skips, suspensions, blocks, switches and recent events.
- Polish: `config.yml` and `messages.yml` are upgraded in place on start and on `/atcs reload`
  (new options added with their comments, own values kept, the old file saved next to it, and
  unknown options named); a compatibility matrix in CI builds against the newest 1.21 Paper and
  the current Paper 26 with TrainCarts 2.0.2; a test keeps the per-second work of a 300-station
  railway with 150 trains in a few milliseconds, and the rail graph of such a railway is built
  three times faster; *Arrival estimates* page in the docs with the report read and a railway's
  real figures; contributor material (CONTRIBUTING, a code of conduct, issue and pull request
  templates).
- The web editor (`integrations.editor`, off by default; `/atcs editor` prints a one-time link):
  stations and platforms, lines, services with their stops drawn as the line, boards, walking
  transfers, fares, diagram adjustments and the configuration files, beside the website's diagram
  where stations can be dragged to pin them. Every change is checked by the code that reads the
  files and shown with the same messages; saving goes through the same store as the commands, so
  an edit made in game meanwhile is shown side by side instead of overwritten; learned times are
  kept for stops that stay; local undo and redo, a review of the file as it will be written, and
  `/atcs undo` from the header. The server is the JDK's own, bound to localhost unless configured.
- The website's diagram is static again by default: live trains are a button away, not on.
- The website map is drawn like the Hong Kong MTR and New York subway maps: the shape follows the
  world (north stays north, a line heading south-west is drawn heading south-west) instead of an
  even grid, lines run straight at 0, 45 or 90 degrees with evenly spaced stations, a crowded
  centre is spread out, long empty stretches are shortened, lines cross only where they do in the
  world, and every name has room. An express that leaves out stops is drawn on the local line,
  with the stations every train calls at as open rings, instead of as a second line. Layouts kept
  from the old diagram are laid out afresh once; pins are cells of the new, finer grid.
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
  changes, walking or not), worked out from the network once per pair of stations and listed in
  a fixed order, as a journey planner does; the live data fills in each route's times in place,
  so the choice never shifts or flickers as trains move. On phones the panel is a draggable sheet
  over the map.
- Walking transfers between nearby stations (`/atcs transfer`, `transfers:` in network.yml): a
  dotted connector on the map, walked by the trip planner, and mentioned in arrival
  announcements.
- Displays redrawn: a split-flap departure board like an airport's (one flap per letter, the
  line on a flap in its colour, columns by width, flaps turning to new departures); roadmaps that
  run left or right to match the platform and fit the frames as a strip, rows or a column, with
  passed stations grey and "You are here"; station signs in New York style (or Hong Kong style)
  with route bullets; the lettering bundled (Inter, Barlow Condensed) so every server draws the
  same; letters centred by their ink. Hologram boards face one way (`/atcs display face`).
  Displays are much lighter: pictures kept as map colours, tiles sent only when they change,
  boards assembled from shared, pre-drawn flaps (a 4 × 2 board in 0.03 ms instead of 2 ms) and
  kept current only while a player is near. `/atcs display export` saves a display as a picture.
- A first start no longer warns about files it has not written yet, and without BlueMap the
  website says what it needs (BlueMap, or `integrations.web.folder`) instead of blaming the
  BlueMap version. Checked on a fresh Paper 1.21.5 server with only TrainCarts installed.
- Redstone outputs bound by command: `/atcs output add <station[:platform]>|block <id> [mode]
  [line|service]` makes the redstone lamp or lever you look at follow the trains (the modes of
  the `atcs indicator` sign), with no sign and no wiring to the track; ATCS sets the block
  itself whenever its chunk is loaded, and again every second, for a lamp lit without power
  goes out at the next block update beside it. A lever is thrown as a player throws it, so
  redstone wired through the block it hangs on follows too. `/atcs output list` and `remove`;
  `outputs:` in network.yml.
- A complete example network in `docs/examples/network.yml`, read by a test so it stays valid.
- Service recovery: `/atcs train shortturn <train> <station>` turns a train back at a station as
  the service of its line running the other way (riders are told there, the train leaves
  backwards, boards and the website follow its new destination); `/atcs train depot <train>`
  takes a train out of service and sends it to the depot, routed by the TrainCarts destination
  `control.depot-destination`, through every station (a sign that turns trains back turns it
  back too), where an `atcs depot` sign takes it out of
  the world once nobody is aboard; `atcs switch` rules `free <station:platform>` (a free-platform
  picker for termini with several platforms, with the platform kept for the train sent there)
  and `depot`. All three from the editor's Dispatch section too. A short turn is only ordered
  where a service runs back the way the train came, and ends when the train runs through the
  station or is given another service first. A depot run survives `atcs service` and
  `atcs clear` signs on the way, and with `control.set-destinations` off a train put back in
  service gets its own TrainCarts destination back. `depot` and `free` in a switch rule still
  name a station or line called that.
- `/atcs service fleet` changes the number of trains, the interval or the train pattern of a
  fleet from anywhere (the console too): a service that has a fleet keeps its spawn point
  and direction unless the sender stands on a rail. Its hours and off-peak setting are kept.
- BlueMap marker images (station logos, train icons) are written by their own thread; the
  markers are drawn again once an image is there. Writing them on the main thread stalled the
  server on some file systems.
- Board mirrors: `/atcs board mirror <id> [line]` makes another sign repeat one of a board's
  lines, so the same next departure can be read at both ends of a platform (`mirrors:` under
  the board in network.yml, a list of `{ sign: world,x,y,z, line: 1 }`). Services have a short
  name for sign lines their name does not fit (`/atcs service short`, `short:` in network.yml,
  `<service_short>` in layouts); boards use it
  where the full name would be cut off, and without one abbreviate *Clockwise* to CW and
  *Counter-clockwise* to CCW.
- Trains standing at a station or block survive a plugin reload or a restart: when ATCS stops,
  a train it holds stays where it is (one let go then could run into an occupied block) until
  the next ATCS takes it over, or the hold's time limit passes; which train is in each block and
  the dispatcher's holds are kept over the reload (`data/dispatch-state.yml`, read once). A hold
  whose check fails is reported once and ends at its time limit. When ATCS starts, and every
  few seconds after, a train on a service that stands at an `atcs station` or block sign with
  nothing to move it is stopped afresh and sent on, or launched onwards when its sign now has
  nothing for it; a hold left by the old plugin on a train that is no longer seen on its sign is
  dropped; a train loaded again on top of its sign is stopped afresh too. A train on a service
  that stands still for five minutes with no dispatcher holding it is logged with what holds it, and
  `/atcs train info` shows how long a standing train has stood and what TrainCarts has queued for
  it. Fleet trains keep their chunks loaded whatever `trains.keep-chunks-loaded` says, so a fleet
  never counts a train frozen out of sight. With `trains.keep-chunks-loaded` on,
  the chunks of stored trains that are not loaded are held, so a train that stood still when
  the server stopped comes back after a restart with TrainCarts'
  `keepChunksLoadedOnlyWhenMoving` instead of waiting for a player. `/atcs service info` counts
  the trains assigned to the service and names those not loaded, which a fleet counts as running.
- Stuck trains: a train on a service that has stood for a minute (`trains.stuck-seconds`) with
  nothing to move it, wherever it is (TrainCarts has no wait or launch queued for it and it waits
  for no train ahead, switch or speed limit), is launched onwards; one that still stands there a
  minute later is taken out of the world when nobody is aboard, so a fleet sends a new one
  (`trains.stuck-trains`: `remove`, `restart` or `report`).

### Changed

- Runs on Paper 1.21.4 and newer with any TrainCarts 2.x build (0.1 required Paper 1.21.11 and exactly
  TrainCarts 2.0.0).
- The plugin JAR no longer bundles SQLite or Gson.

### Removed

- The 0.1 prototype's evidence/candidate review pipeline, embedded web administration site, SQLite
  database, Anvil region scanner and WebGL map. On first start their files are moved into
  `legacy-0.1/` in the plugin folder.
