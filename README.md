# AdvancedTrainControlSystem (ATCS)

Passenger information, departure boards and live network maps for [TrainCarts](https://github.com/bergerhealer/TrainCarts) railways.

TrainCarts drives the trains. ATCS knows your network — stations, platforms, lines and the services
trains run — follows every train through it, and tells passengers where they are:

- **Boss bar** in the line colour: line, service, destination, next stop and an arrival estimate.
- **Arrival titles** with a chime, **"Next station"** and **interchange** announcements, end-of-line notices.
- **Departure boards** on ordinary signs, with arrival times learned from your real trains.
- **BlueMap**: stations, lines drawn along the track your trains actually use, and live trains.
- **SignLink** variables for existing SignLink displays.
- **Set-up from what you already have**: stations are drafted from your TrainCarts station signs and
  `Arrived: ...` announcements, and lines and services are suggested from the trips your trains make.

ATCS never changes how trains drive, and never edits your world: it observes TrainCarts and adds
information on top. Your existing signs keep working exactly as before.

## Requirements

| | |
|---|---|
| Server | Paper (or a fork) 1.21.4 or newer |
| Java | 21 or newer |
| Required | TrainCarts 2.x and BKCommonLib |
| Optional | BlueMap 5.x (network map), SignLink (sign variables) |

## Installing

1. Put `AdvancedTrainControlSystem-<version>.jar` in `plugins/`.
2. Restart the server (do not hot-load it with PlugMan).
3. Follow one of the set-up guides below.

## Set-up for an existing TrainCarts network

This is the fastest way: ATCS drafts everything from your world, you review it, then apply it.

**1. Stations.** Stand near your railway and run

```
/atcs import scan
```

Only chunks that are already loaded are read (ATCS never loads chunks, so it cannot wake up spawners),
so fly along the network and scan again in other areas; results accumulate. Then

```
/atcs import stations     (look at the draft in chat)
/atcs import write        (write plugins/AdvancedTrainControlSystem/import-proposal.yml)
```

Every TrainCarts `station` sign becomes a platform. Its station name comes from the nearest arrival
announcement (`[+train] announce` with text such as `Arrived: Main St.`) or a station name board (an
ordinary sign ending in `St.`, `Station`, ...). Open the file, fix names and ids, delete anything that
is not a passenger platform, then

```
/atcs import apply
```

**2. Lines and services.** Let your trains run for 10–20 minutes. ATCS records the stations every train
visits and which spawn sign created it. Then

```
/atcs import services     (look at the suggestions in chat)
/atcs import write
```

The proposal now contains suggested lines (`line-1`, `line-2`, ...) with one service per direction,
`Local` and `Express` variants, which service a train becomes after turning around, and the spawn
signs whose trains run each service. Rename the lines and pick colours, then `/atcs import apply`.

From now on every new train is assigned automatically by the spawn sign that created it, and
passengers get information on every journey.

## Set-up by hand

```
/atcs station create main Main St.
/atcs station bind main 1            (look at the TrainCarts station sign of platform 1)
/atcs line create a #E53935 Line A Upperground
/atcs line badge a A
/atcs service create a/north Local
/atcs service stops a/north main:1 nerdy ~golden-beach new-village
/atcs service reverse a/north south
/atcs service continues a/north a/south
/atcs service continues a/south a/north
/atcs service spawn a/north          (look at the spawn sign whose trains run it)
```

Instead of typing stops, ride a train and let ATCS record them:

```
/atcs service record a/north         (while riding)
/atcs service record stop            (at the end of the route)
```

## How it works

**Stations and platforms.** A platform has one or more *triggers*: TrainCarts signs (normally the
`station` sign trains stop at) whose position tells ATCS that a train is at that platform. A train
*arrives* when it enters the trigger's rail and *departs* when it has left it. You can also mark a
platform with an ATCS sign instead of binding one (see below).

**Lines and services.** A line (`a`, "Line A Upperground", red) groups services. A service is one
stopping pattern in one direction (`a/north`: Main St. → Nerdy St. → New Village St.). In stop lists,
`station:platform` plans a platform and `~station` marks a station the service runs through without
stopping. A service can be circular, and can *continue as* another service when its trains turn
around at the end.

**Which service a train runs**, in this order:

1. an explicit assignment, stored on the train as the TrainCarts tag `atcs:service=line/service` —
   written by an ATCS service sign, a spawn sign listed by a service, or `/atcs train assign`;
2. the first service whose `match` rules fit the train: its TrainCarts destination or route, a tag,
   or its name (useful with TrainCarts path finding);
3. **its route**: once a train with no service has been seen at two stations in an order that only
   one service explains, it gets that service (stored on the train, so it survives restarts). A
   train that later strays onto another line is re-inferred. This is how loop lines work, where the
   same trains run all day and no spawn sign ever creates them;
4. otherwise the train has no service; riders still see station names on arrival.

`/atcs train unassign` takes a train out of service *and* out of automatic assignment (for a
maintenance run, say); `/atcs train auto` puts it back.

**Fleets: trains without spawn signs.** A timed spawn sign would keep adding trains to a loop.
Instead give the service a fleet: stand on the rail where trains should appear, facing the way
they go, and run `/atcs service fleet 5/cw 3 00:35 4m`. ATCS then keeps 3 trains in service,
spawning one (four minecarts) at most every 35 seconds while there are fewer, which also spaces
them out. Trains are counted even in unloaded chunks. `/atcs service fleet 5/cw off` stops it.

**Train names.** With `trains.rename` on (the default), every train ATCS assigns is renamed after
its service, `A-to-main-2` or `5-cw-1` instead of `train17`, spawn-sign trains included. Trains
you named yourself are never renamed. The format is in `config.yml`.

**Chunks and memory.** Set `trains.keep-chunks-loaded: true` and ATCS switches TrainCarts'
keep-chunks-loaded on for every train, so trains keep running with nobody around while each
holds only the chunks around itself. With `spawning.keep-spawn-chunks-loaded` (default on) ATCS
also holds the chunk of every spawn sign and fleet spawn point. Together these replace
force-loading the railway: `/forceload remove all` and the trains keep running.

**A loop line.** Build the stations with ordinary TrainCarts `station` signs so trains stop
(ATCS never drives trains; it reads those signs as platform triggers), bind or import them, create
the service with `/atcs service stops ... ` and `/atcs service circular <service> true`, and put
trains on the track however you like. Every train is recognised after its first two stops. Every
station of the loop needs a trigger, because inference follows the stop order exactly.

**Following a train.** ATCS searches forward along the service from the last stop reached, so
stations without triggers and trains that were unloaded for part of the trip are handled. Travel and
stop times are learned per service and stop (smoothed, so a train held at a signal does not ruin the
estimate) and drive the boss-bar and board arrival times.

## Signs

ATCS adds six TrainCarts sign types. Building them needs `atcs.sign.build`.

```
[+train]        [+train]          [+train]      [+train]         [+train]        [+train]
atcs stop       atcs service      atcs clear    atcs indicator   atcs station    atcs switch
main            a/north                         main:2           main:2          left a/express
2                                               approach a       10s continue    right *
```

- `atcs stop` marks platform 2 of `main` (platform defaults to 1) without binding a sign in network.yml.
  It only watches; a TrainCarts `station` sign does the stopping.
- `atcs station` is a platform that runs itself: trains whose service calls there stop, wait and go
  on; trains whose service runs through keep going. See [Station signs](#station-signs).
- `atcs switch` sets the junction it sits on for each train, by service, line or next station. See
  [Switch signs](#switch-signs).
- `atcs service` assigns passing trains to the service.
- `atcs clear` removes the assignment.
- `atcs indicator` turns the levers on the block behind the sign on and off with the trains, for
  platform lamps, track diagrams or barriers, anywhere in the world and without any track wiring.

### Station signs

An `atcs station` sign takes the place of the TrainCarts `station` sign at a platform (keep one or
the other on a rail, not both). Line 3 names the station and platform, `main:2` (platform 1 when
left out). Line 4 says how a stopping train is handled; every part is optional, in any order:

| On line 4 | Meaning |
|---|---|
| `10s` or `10` | dwell time; without one, `control.dwell-seconds` from config.yml |
| `continue`, `reverse`, `left`, `right`, `north` ... | where the train goes afterwards; `continue` when left out, `reverse` at a terminus |
| `route` | TrainCarts path-finding picks the direction from the train's destination |
| `2.5m` | centre the train 2.5 blocks past the sign (`-2.5m`: before it) |
| `30b` | launch the train over 30 blocks |
| `gap 60s` | headway: do not leave until 60 s after the last train of the same line left this platform |
| `block` / `noblock` | wait until the train that left before has reached its next station, or never |

Which trains stop is decided per train, from the service ATCS knows it runs:

- The service calls at the station: the train stops.
- The service runs through (`~firework` in its stop list) or does not list the station at all: the
  train keeps going at speed. That is an express: give its service only the stops it makes, and it
  passes every local station on the shared track while the local trains stop.
- The train has no service: `control.unknown-trains` decides (stop, by default). A train whose
  service does not list the station follows `control.off-route-trains` (pass, by default).

The stop itself is TrainCarts' station behaviour (the train is centred on the sign, the levers on
the sign's block go up while it waits, it is launched the way the line says), so doors and lever
wiring work as with a `station` sign. Use `[+train]` on line 1; with `[train]` the sign acts only
while it is powered by redstone, which makes a lever an on/off switch for the platform.

`gap` and `block` keep trains apart on a shared track: with `block`, a train waits at the platform
until the train that left before it has arrived at its next station (ATCS sees that from the next
platform sign), and with `gap` until the headway has passed. `control.headway-seconds` and
`control.hold-for-clear-section` set these for every station sign; a sign's own `gap 0` or
`noblock` switches them off there. A held train is released after `control.max-hold-seconds` in any
case, with a warning in the console, so a train ATCS lost sight of can never block a platform for
good. `/train status` on a held train shows why it waits.

### Switch signs

An `atcs switch` sign sits at a junction like a TrainCarts `switcher` sign. Lines 3 and 4, and the
lines of any signs placed under it, are rules: a direction followed by the trains it is for.

```
[+train]
atcs switch
left 1/express
right 1 5
```

Trains are named by service (`1/express`), by line (`1`), by the station they reach next on their
service (`golden-beach`), or `*` for every train; a direction on its own means every train too.
The first rule that fits wins; a train no rule fits leaves the junction as it is. Directions are the
ones TrainCarts' own signs understand: `continue` and `reverse` follow the train, `left` and `right`
are as seen by a train driving towards the front of the sign (so face the sign towards the trains
it switches, as you would a TrainCarts switcher), and `north`, `east`, `south`, `west` are absolute.
The levers on the sign's block go up while a train it switched is on it.

Several lines on one track need nothing more: each train stops where its service calls, is switched
where its service goes, and waits for the train in front where you ask for it. For larger
networks, `control.set-destinations: true` keeps every train's TrainCarts destination at its next
calling stop (every station sign registers itself as the destination `station:platform`), so
TrainCarts' own `switcher` signs and `route` stations find the way by path-finding.

### Indicator signs

Line 3 names what to watch: a station (`main`) or one platform (`main:2`). Line 4 says when the
levers go on, and optionally for which trains:

| Line 4 | On while |
|---|---|
| empty or `at` | a train stands at (or runs through) the station or platform |
| `approach` | a train has been announced as approaching it (`passenger.approach-distance`) |
| `after` | a train has left it and has not reached its next stop |
| `to <station>` | a train has left it and the other station is its next stop |
| `between <station>` | a train runs between the two stations, in either direction |

Add a line or service to count only its trains: `at 5`, `approach a/north`, `after 5/cw`. For a
track diagram, give every station an `at` sign and every stretch of track a `to` sign (one per
direction) or a `between` sign (one for both), for example `liberty` / `to ysysign`.

The levers switch the moment ATCS sees the train arrive, leave or come within approach distance, and
are checked every second. Signs work in loaded chunks; when a chunk unloads its levers keep their
last state until it loads again.

## Departure boards

Any ordinary sign can be a board. Each sign shows one departure; add more signs for more departures.

```
/atcs board create main-east main 1 2   (look at a sign; platforms are optional)
/atcs board add main-east               (look at the next sign)
```

Boards show the line badge, service, destination, a countdown (or *Arriving* / *Boarding*) and the
platform. The layout is in `messages.yml`.

## Commands

| Command | Permission | |
|---|---|---|
| `/atcs departures <station> [platform]` | atcs.use | Next departures |
| `/atcs station list\|info\|here` | atcs.use | Stations; `here` names the platform you stand on |
| `/atcs station create\|rename\|remove\|bind\|unbind\|platform\|locate\|code` | atcs.manage | Edit stations |
| `/atcs line list\|info` | atcs.use | Lines |
| `/atcs line create\|rename\|color\|badge\|suspend\|resume\|remove` | atcs.manage | Edit lines |
| `/atcs service info` | atcs.use | A service and what has been learned about it |
| `/atcs service create\|stops\|record\|spawn\|unspawn\|fleet\|reverse\|rename\|circular\|continues\|match\|remove\|resetpaths` | atcs.manage | Edit services |
| `/atcs train info [train]` | atcs.use | The train you ride or look at |
| `/atcs train assign\|unassign\|auto`, `/atcs trains` | atcs.manage | Assignments |
| `/atcs transfer list` | atcs.use | Walking transfers |
| `/atcs transfer add\|remove` | atcs.manage | Edit walking transfers |
| `/atcs board create\|add\|unbind\|remove\|list` | atcs.manage | Departure boards |
| `/atcs import scan\|stations\|services\|write\|apply\|reset` | atcs.manage | Set-up from the world |
| `/atcs status`, `/atcs reload`, `/atcs debug` | atcs.manage | Administration |

`/atcs line suspend a Signal failure at Main St.` shows the notice on the boss bar of Line A trains
and marks the line's departures as *Suspended* on boards until `/atcs line resume a`.

## Permissions

| Permission | Default | |
|---|---|---|
| `atcs.passenger` | everyone | See passenger information while riding |
| `atcs.use` | everyone | Look up stations, lines, trains and departures |
| `atcs.manage` | op | Edit the network, boards and assignments; import, reload, status |
| `atcs.sign.build` | op | Build ATCS signs |
| `atcs.admin` | op | All of the above |

## Files

All in `plugins/AdvancedTrainControlSystem/`:

| File | |
|---|---|
| `config.yml` | Behaviour: what passengers see, sounds, boards, BlueMap, SignLink, website, importer |
| `messages.yml` | Every passenger text and the board layout, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) |
| `network.yml` | Stations, lines, services and boards. Edit in game or by hand, then `/atcs reload` |
| `import-proposal.yml` | Written by `/atcs import write` for review |
| `data/timings.yml`, `data/track-paths.yml`, `data/trips.yml` | Learned travel times, recorded track and observed trips; safe to delete |

`network.yml` is never overwritten behind your back: if it has an error ATCS keeps using the valid
parts but refuses edits until it is fixed, and if you edited it without `/atcs reload`, in-game edits
are refused instead of replacing your changes.

A small example:

```yaml
version: 1
stations:
  main:
    name: Main St.
    platforms:
      '1':
        triggers:
        - world,-322,63,-802
lines:
  a:
    name: Line A Upperground
    short-name: A
    color: '#E53935'
    services:
      north:
        name: Local
        stops: [main:1, nerdy, ~golden-beach, new-village]
        continues-as: south
        spawn-signs:
        - world,-212,63,-781
        match:
          destinations: [NewVil2Main]
boards:
  main-east:
    station: main
    platforms: ['1']
    signs:
    - world,-320,65,-802
```

## Integrations

**BlueMap** — adds a *Railway* marker set with every station (and the lines calling there), each line
drawn in its colour, and live train markers. Lines follow the track recorded from real trains; until
a segment has been recorded it is drawn as a thinner straight line.

**SignLink** — publishes the next departures of every station and every platform as variables
short enough to type on a sign line. Each station has a short code: the initials of a multi-word
id (`golden-beach` is `gb`, `nerdy-estate-blk-1` is `neb1`), a short single word as it is (`main`),
a longer one cut to three letters (`liberty` is `lib`). `/atcs station info` shows it and
`/atcs station code <station> <code>` changes it.

| Variable | Shows |
|---|---|
| `%gb.1d%` | destination of the next train from any platform of Golden Beach |
| `%gb.1t%` | its time: `0:05`, `45s` or `Boarding` |
| `%gb.1l%`, `%gb.1s%`, `%gb.1p%` | its line badge, service and platform |
| `%gb.1%` | all of it in one line (too long for most signs) |
| `%gb.p3.1d%`, `%gb.p3.1t%` ... | the same for platform 3 only |
| `%gb.2d%`, `%gb.p3.2t%` ... | the train after (`integrations.signlink.departures-per-station` sets how many) |

Standing on a platform, `/atcs station here` says which station and platform it is, which trains
use it, and the variables for it (click one to copy it). Building signs with variables needs
SignLink's `signlink.addsign` permission.

## Walking transfers

Two stations a short walk apart can be linked, so passengers change between them on foot:

```
/atcs transfer add 1st-street main            walking time estimated from the distance
/atcs transfer add 1st-street main 0:45       or given (m:ss or seconds)
/atcs transfer add depot-east depot-west inside
```

`outside` (the default) means passengers leave one station through the gates and enter the other;
`inside` is a passage that stays within the stations. In `network.yml`:

```yaml
transfers:
- between: [1st-street, main]
  walk: 45          # seconds; leave it out to estimate it from the distance
  outside: true
```

The website map draws the link as a dotted line (a solid connector for `inside`), as the Hong Kong
MTR map does, and the trip planner walks it when that is quicker. Arrival announcements mention
the lines at the other end: *Change here for Line A, Line 1 (walk to Main St.).*

## Website

ATCS writes a small website: a schematic map of the network, the next departures of every
station, the trains in service, and a realtime trip planner. It is plain static files, so any web
server can serve it.

The map is drawn as a subway diagram from your stations' positions and stop orders: stations sit
on an evenly spaced grid, every line runs through its stops in their real order at 45-degree
angles with rounded bends, no line is drawn through a station it does not serve, and labels are
kept clear of lines (long names may wrap onto two lines). Stations keep their real north-south
and east-west order; distances are not to scale.

The planner follows the actual trains: it projects every running train over its next stops from
its live position and the travel and stop times learned from your trains, allowing time for each
change. Like a journey planner it offers a few routes, each as a card with its lines, when to
board and a countdown to arrival: the fastest, the best trip starting on each line or service at
the origin (an express and a local, say), fewer changes, or walking or not, tagged *Fastest*,
*Leaves first*, *Fewest changes* or *Less walking*. Routes that are worse in every respect, far
slower, or double back through a station are left out. The picked route is drawn on the map and
stays picked while the plan updates with every refresh. A line with no train running is planned
with an assumed wait and marked as an estimate.

On the map, scroll or pinch to zoom, drag to pan, double-click to zoom in; taps find the nearest
station within a finger's reach. On phones the panel is a sheet over the map that can be dragged
between three heights, and the map keeps its place whatever the sheet does. Updates reach browsers straight away even though BlueMap's web server lets them cache files
for a day: the page and the plaque only read a version number and load files stamped with it.

- With BlueMap installed, nothing needs configuring: the files go to BlueMap's web root under
  `atcs/`, the site is at `<your BlueMap address>/atcs/`, and BlueMap's own page gets a plaque
  linking to it.
- Without BlueMap, set `integrations.web.folder` to a folder your web server serves.

The map needs station positions: ATCS takes them from the platform signs trains stop at, or from
`/atcs station locate`. Live data is rewritten every `integrations.web.refresh-seconds`.
Links to a station or a trip can be shared, for example `/atcs/#plan=liberty,main`.

## Troubleshooting

- **Nothing happens when a train arrives.** Check that the platform's trigger is the sign trains
  actually pass (`/atcs station info <station>`), then turn on `/atcs debug` and watch the console.
- **A train shows no line.** `/atcs train info` shows its tags and TrainCarts destination; assign it with
  a spawn sign, an ATCS service sign, match rules or `/atcs train assign`.
- **Arrival estimates show "Expected".** Times are learned from trains running the service; give it a
  few trips. `/atcs service info` shows how many segments have been learned.
- **Old announce signs.** Once ATCS announces your stations you can remove TrainCarts `announce` signs
  that say the same thing.

## Building

```
./gradlew build
```

The plugin is `build/libs/AdvancedTrainControlSystem-<version>.jar`. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the code is organised.
