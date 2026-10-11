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
- Departure boards on ordinary signs; SignLink variables.
- BlueMap integration: stations, lines along recorded track, live trains.
- Set-up from the existing world: stations drafted from TrainCarts station signs and arrival
  announcements; lines and services suggested from observed train trips, including spawn signs,
  express variants and turn-arounds; reviewed through `import-proposal.yml`.
- ATCS TrainCarts signs: `atcs stop`, `atcs service`, `atcs clear`, and `atcs indicator`, which
  drives levers while a train is at, approaching or just past a station, or between two stations,
  for lamps, track diagrams and barriers without any wiring along the track.
- Service recording by riding a train.
- A website (schematic network map, departures, trains in service, realtime trip planner over the
  actual running trains), written as static files into BlueMap's web root or any folder, with a
  plaque on BlueMap's page linking to it. The map is laid out as a subway diagram: an even grid
  that keeps every station's real order, 45-degree lines with rounded bends, no line through a
  station it does not serve.
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
