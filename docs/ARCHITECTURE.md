# Architecture

ATCS is a single Paper plugin with no runtime dependencies of its own. TrainCarts (and BKCommonLib)
are required at runtime; BlueMap and SignLink are optional and only touched when installed.

## Principles

- **Observe, never drive.** TrainCarts does physics, stopping and switching. ATCS listens to its
  events and adds information. It never moves trains, edits signs it did not create a board on, or
  loads chunks.
- **Pure core, thin adapters.** Everything that decides something (network model, journeys, timing,
  departures, importing, route suggestions) is plain Java without Bukkit runtime calls, and is unit
  tested. Bukkit and TrainCarts code only translates events and draws output.
- **Files people can read.** The network lives in `network.yml`, in a format meant to be edited by
  hand. Learned data (timings, recorded track) lives in `data/` and is disposable.
- **Never lose an operator's work.** A file with errors is used partially but never written back; an
  edit is refused if the file changed on disk since it was loaded; imports only add.

## Packages

| Package | Role |
|---|---|
| `network` | Immutable model (`Network`, `Station`, `Platform`, `Line`, `Service`, `Stop`, `Board`), YAML codec, and `NetworkStore` which owns `network.yml` |
| `operation` | The railway logic: `JourneyTracker` (where each train is on its service), `ServiceResolver` (which service a train runs), `TravelTimes` and `EtaCalculator` (learned times and estimates), `Departures`, `TrackPaths` (recorded track for maps), `TripLog` and `RouteSuggester` (service suggestions), `AtcsSign` (sign text) |
| `importer` | Drafting stations from signs (`StationImporter`), the loaded-chunk `SignScanner`, the proposal file and merging it into the network |
| `runtime` | `RailwayRuntime` ties everything together on the server thread: platform signals in, events out to displays, boards and maps; periodic tasks; persistence of learned data. `TrainHandle`/`TrainSource` hide TrainCarts from it |
| `traincarts` | The only package that imports TrainCarts: event listener, the `atcs` sign action, and the `TrainHandle` implementation |
| `passenger` | Boss bars, titles, chat, action bar and sounds for riders |
| `board` | Sign departure boards and SignLink variables |
| `map` | `MapLayer` and `MapGeometry` (what to draw), `BlueMapLayer` (how) |
| `command` | The `/atcs` command tree |
| `config` | `config.yml` and `messages.yml` |

## Flow of a train through a station

1. TrainCarts fires `SignActionEvent` (`GROUP_ENTER` / `GROUP_LEAVE`) for every sign a train passes.
   `TrainCartsListener` looks the sign position up in `Network.platformAt`; ATCS `atcs stop` signs
   report through `AtcsSignAction` instead.
2. `RailwayRuntime.platformSignal` resolves the train's service (`ServiceResolver`) and hands a
   `StopObservation` to `JourneyTracker`, which returns `JourneyEvent`s: `Arrived`, `Departed`,
   `Passed`, `ServiceChanged`, `TripCompleted`, and their unassigned variants.
3. The runtime feeds the events to `TravelTimes`, `TrackPaths` and `TripLog`, then to
   `PassengerDisplay` for the riders.
4. Periodic tasks refresh boss bars (every second), check approaches (twice a second), and redraw
   boards, SignLink variables and the map.

### Journey rules worth knowing

- A station visit begins with the first trigger entered at the station and ends when the train has
  left all of them, so long trains and repeated TrainCarts events are harmless.
- Stops are found by searching forward from the last stop reached; a non-circular service is
  searched again from the start, which is a new trip.
- The resolver's answer is applied only when it changes. That is what lets a train *continue as*
  another service at a terminus even though its tag or match rule still names the first one (the
  runtime also rewrites an explicit tag to keep restarts consistent).
- Entering the station just left within 15 seconds resumes the visit instead of arriving again.

## Persistence

| File | Written by | When |
|---|---|---|
| `network.yml` | `NetworkStore.edit` | Synchronously, atomically, on each command edit |
| `data/timings.yml` | `TravelTimes.save` | Every few minutes if changed, and on shutdown |
| `data/track-paths.yml` | `TrackPaths.save` | Same |
| `data/trips.yml` | `TripLog.save` | Same; feeds `/atcs import services` across restarts |
| `import-proposal.yml` | `ImportService` | On `/atcs import write` |

Learned data is keyed by service and discarded automatically when a service's stops change (each
service has a fingerprint of its stop list).

## Testing

`./gradlew test` runs the unit tests: the journey tracker scenarios, timing and departures, the YAML
format, the station importer against signs captured from a real server (`src/test/resources/fixtures`),
and the route suggester. Anything touching Bukkit at runtime is kept out of these classes so they need
no server. Behaviour on a real server is checked by running a staging copy with TrainCarts and
watching `/atcs debug` output.

## Compatibility

The plugin compiles against the Paper 1.21.4 API and TrainCarts 2.0.0, and uses only long-stable parts
of the TrainCarts API (sign action events, group lifecycle and seat events, train properties and
tags). It does not check exact versions at runtime; a `LinkageError` during start-up disables the
plugin with a clear message instead.
