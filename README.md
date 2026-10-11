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

ATCS adds four TrainCarts sign types. Building them needs `atcs.sign.build`.

```
[+train]        [+train]          [+train]      [+train]
atcs stop       atcs service      atcs clear    atcs indicator
main            a/north                         main:2
2                                               approach a
```

- `atcs stop` marks platform 2 of `main` (platform defaults to 1) without binding a sign in network.yml.
- `atcs service` assigns passing trains to the service.
- `atcs clear` removes the assignment.
- `atcs indicator` turns the levers on the block behind the sign on and off with the trains, for
  platform lamps, track diagrams or barriers, anywhere in the world and without any track wiring.

### Indicator signs

Line 3 names what to watch: a station (`main`) or one platform (`main:2`). Line 4 says when the
levers go on, and optionally for which trains:

| Line 4 | On while |
|---|---|
| empty or `at` | a train stands at (or runs through) the station or platform |
| `approach` | a train has been announced as approaching it (`passenger.approach-distance`) |
| `after` | a train has left it and has not reached its next stop |

Add a line or service to count only its trains: `at 5`, `approach a/north`, `after 5/cw`. For a
track diagram, give every station an `at` sign and every stretch of track an `after` sign for the
station it starts at, naming the service so each direction has its own lamps.

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
| `/atcs station list\|info` | atcs.use | Stations |
| `/atcs station create\|rename\|remove\|bind\|unbind\|platform\|locate` | atcs.manage | Edit stations |
| `/atcs line list\|info` | atcs.use | Lines |
| `/atcs line create\|rename\|color\|badge\|suspend\|resume\|remove` | atcs.manage | Edit lines |
| `/atcs service info` | atcs.use | A service and what has been learned about it |
| `/atcs service create\|stops\|record\|spawn\|unspawn\|fleet\|reverse\|rename\|circular\|continues\|match\|remove\|resetpaths` | atcs.manage | Edit services |
| `/atcs train info [train]` | atcs.use | The train you ride or look at |
| `/atcs train assign\|unassign\|auto`, `/atcs trains` | atcs.manage | Assignments |
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
| `config.yml` | Behaviour: what passengers see, sounds, boards, BlueMap, SignLink, importer |
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

**SignLink** — publishes `%atcs_<station>_<n>%` (a summary of the n-th departure) and
`%atcs_<station>_<n>_line%`, `_service`, `_dest`, `_eta` and `_platform`, where dashes in station ids
become underscores.

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
