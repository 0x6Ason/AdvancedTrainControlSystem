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

ATCS observes TrainCarts and adds information on top; your existing signs keep working exactly as
before. Where you want it to, it also runs the stations: `atcs station` signs stop the trains that
call and let the others run through (an express on a local track), hold trains apart, and
`atcs switch` signs set junctions by service.

<!-- --8<-- [start:install] -->
## Requirements

| | |
|---|---|
| Server | Paper (or a fork) 1.21.4 or newer; built and tested against 1.21.4, 1.21.11 and 26.3 |
| Java | 21 or newer (Paper 26 needs 25) |
| Required | TrainCarts 2.0.0 or newer and BKCommonLib 2.0.1 or newer; tested with 2.0.0/2.0.1 and 2.0.2/2.0.4 |
| Optional | BlueMap 5.x (network map), SignLink (sign variables), WorldEdit 7.3.x (the wand's selection), PlaceholderAPI 2.11.x |

Every build in CI compiles and tests against the oldest and the newest of those, so a new
server or TrainCarts release that breaks something is caught before it reaches you.

## Installing

1. Put `AdvancedTrainControlSystem-<version>.jar` in `plugins/`.
2. Restart the server (do not hot-load it with PlugMan).
3. Follow one of the set-up guides below.

<!-- --8<-- [end:install] -->
<!-- --8<-- [start:setup] -->
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
`/atcs service hours 5/cw 06:00-23:00` gives the service hours by the server's clock, and
`/atcs service offpeak 5/cw 2 22:00-06:00` fewer trains for part of the day: a train that ends
its trip (or completes a lap) while more are running than the hour asks for is stabled, that is
taken out of the world, with nobody aboard, and the fleet comes back out when the hours do.

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

<!-- --8<-- [end:setup] -->
<!-- --8<-- [start:signs] -->
## Signs

ATCS adds nine TrainCarts sign types. Building them needs `atcs.sign.build`.

```
[+train]        [+train]          [+train]      [+train]         [+train]        [+train]
atcs stop       atcs service      atcs clear    atcs indicator   atcs station    atcs switch
main            a/north                         main:2           main:2          left a/express
2                                               approach a       10s continue    right *

[+train]        [+train]          [+train]
atcs block      atcs speed        atcs depot
tunnel-east     0.6
enter           a/express
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
  The same without a sign: look at a redstone lamp or a lever and run `/atcs output add main:2
  approach` (the modes of the indicator sign: `at`, `approach`, `after`, `to <station>`,
  `between <station>`, `block <id>`, and a line or service to filter by). ATCS lights the lamp or
  throws the lever itself whenever the chunk is loaded, so a wall of lamps showing where every
  train is needs no signs and no wiring to the track; `/atcs output list` shows them and
  `/atcs output remove` unbinds the one you look at. They are kept in `network.yml` under
  `outputs:`.
- `atcs block` signs come in pairs, `enter` and `exit`, and let one train at a time through the
  track between them. See [Block signs](#block-signs).
- `atcs speed` sets the speed limit of the trains that pass it, all of them or one line's or
  service's, so an express runs faster than a local on shared track. A service can also carry a
  `speed` in `network.yml`, set on its trains when they take it up.
- `atcs depot` is where a train sent to the depot ends: a train a dispatcher sent there
  (`/atcs train depot`) is taken out of the world when it arrives with nobody aboard. Any other
  train passes it.

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
| `even` / `noeven` | hold a train that is catching up the one in front, so the trains of a service spread out evenly |
| `at :00 :30`, `at /15` | leave at these minutes past the hour (or every 15 minutes), by the server's clock |

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

To change a railway over, `/atcs station convert` lists every TrainCarts station sign that is a
platform trigger in network.yml with the `atcs station` text it would get (the delay, direction,
`route`, offset, launch distance and launch speed are carried over) and anything it cannot keep;
`/atcs station convert confirm` rewrites them. Station signs that are not platforms in network.yml,
such as a stop where trains refill on a long run or a station still being built, are never touched.

`gap` and `block` keep trains apart on a shared track: with `block`, a train waits at the platform
until the train that left before it has arrived at its next station (ATCS sees that from the next
platform sign), and with `gap` until the headway has passed. `control.headway-seconds` and
`control.hold-for-clear-section` set these for every station sign; a sign's own `gap 0` or
`noblock` switches them off there. A held train is released after `control.max-hold-seconds` in any
case, with a warning in the console, so a train ATCS lost sight of can never block a platform for
good. `/train status` on a held train shows why it waits.

`even` is for loop lines and any service with several trains: a train that arrives soon after
the one in front left, with the next one far behind, waits at the platform for up to half the
difference (never more than `control.even-max-hold-seconds`), so the gaps come out equal instead
of the trains bunching. `control.even-spacing: true` turns it on for every station sign. `at`
makes a timetable: the train leaves at the next listed minute after its dwell, and boards count
down to it. A service's `timetable: [':00', ':30']` in `network.yml` does the same at its first
stop. `/atcs train hold` and `release` keep a train at its next station sign by hand, `/atcs train
skip` makes it run through a station once (riders are told), `/atcs train shortturn` turns a
train back at a station as the service of its line running the other way (riders are told
there; the train leaves backwards and boards show its new destination), `/atcs train depot`
takes a train out of service and sends it to the depot (no service, through every station
except one whose sign turns trains back, routed by the TrainCarts destination
`control.depot-destination`), and `/atcs switch set` sends
every train at a switch sign one way for a while. With `control.junction-priority: true` a train about
to leave a platform whose next stretch of track runs into a junction waits for a train that is
approaching it, unless it goes first: the late train first, then the line listed earlier in
`control.priority`, then the nearer one; this needs recorded track, so that ATCS knows where the
junctions are. The web editor's *Dispatch* section does all of this from a browser, with every
train live and the recent events.

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
`free main:2` names the trains for `main` while platform 2 is free (nothing standing there and no
train sent there in the last few minutes), so a terminus with several platforms fills them in
turn: `left free main:1`, `right free main:2`, then `left main` for when both are taken. `depot`
names the trains a dispatcher sent to the depot. The first rule that fits wins; a train no rule
fits leaves the junction as it is. Directions are the
ones TrainCarts' own signs understand: `continue` and `reverse` follow the train, `left` and `right`
are as seen by a train driving towards the front of the sign (so face the sign towards the trains
it switches, as you would a TrainCarts switcher), and `north`, `east`, `south`, `west` are absolute.
The levers on the sign's block go up while a train it switched is on it.

Several lines on one track need nothing more: each train stops where its service calls, is switched
where its service goes, and waits for the train in front where you ask for it. For larger
networks, `control.set-destinations: true` keeps every train's TrainCarts destination at its next
calling stop (every station sign registers itself as the destination `station:platform`), so
TrainCarts' own `switcher` signs and `route` stations find the way by path-finding.

### Block signs

Two signs protect any stretch of track, not only platform to platform: trains pass the `enter`
sign one at a time and free the block at the `exit` sign.

```
[+train]        [+train]
atcs block      atcs block
tunnel-east     tunnel-east
enter           exit
```

A train that reaches the entry while another is inside stops on the sign and waits; the moment the
train inside passes the exit it sets off again. A block a train never left (it was destroyed in
the block, say) is believed for fifteen minutes and a waiting train is let in after
`control.max-hold-seconds` with a warning, so nothing can close a line for good. Put the exit sign
where the next train may safely enter: at the far end of a single-track section, a tunnel or a
level crossing. An indicator with `block tunnel-east` on line 3 lights while the block is taken.

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
| line 3 `block <id>` | a train is inside the virtual block (see [Block signs](#block-signs)) |

Add a line or service to count only its trains: `at 5`, `approach a/north`, `after 5/cw`. For a
track diagram, give every station an `at` sign and every stretch of track a `to` sign (one per
direction) or a `between` sign (one for both), for example `liberty` / `to ysysign`.

The levers switch the moment ATCS sees the train arrive, leave or come within approach distance, and
are checked every second. Signs work in loaded chunks; when a chunk unloads its levers keep their
last state until it loads again.

<!-- --8<-- [end:signs] -->
<!-- --8<-- [start:boards] -->
## Departure boards

Any ordinary sign can be a board. Each sign shows one departure; add more signs for more departures.

```
/atcs board create main-east main 1 2   (look at a sign; platforms are optional)
/atcs board add main-east               (look at the next sign: the departure after)
/atcs board mirror main-east            (look at a sign elsewhere: the same first departure again)
```

Boards show the line badge, service, destination, a countdown (or *Arriving* / *Boarding*) and the
platform. The layout is in `messages.yml`. A service name too wide for a sign line, such as
*Counter-clockwise*, is replaced by its short name: set one with `/atcs service short 5/ccw CCW`,
or let ATCS abbreviate (*Clockwise* becomes CW, *Counter-clockwise* CCW, other long names their
initials).

<!-- --8<-- [end:boards] -->
<!-- --8<-- [start:displays] -->
## Displays and the wand

Pictures on map items in item frames, and floating hologram boards, placed where you select.

```
/atcs wand                               (a wand: left click one corner, right click the other)
/atcs display roadmap 1/north main 1     (the selected frames show line 1's strip diagram, "you are here" at Main St.)
/atcs display logo main                  (the station's logo)
/atcs display board main 1               (a departure board picture that keeps updating)
/atcs display map main                   (the network map, marking Main St.)
/atcs display status                     (every line and whether it runs)
/atcs display hologram main 1            (a floating departure board at the selection's centre, or where you look)
/atcs display list | remove <id> | redraw
/atcs map                                (a hand-held map of the railway that shows where you are)
```

Hang the item frames first, as many as you like in a rectangle on one wall: a roadmap reads best
on a row of four, a logo on one, a board on two by two. ATCS fills them as one picture, fixes the
frames so players cannot turn or empty them (`displays.fix-frames`), records the display under
`displays:` in `network.yml` and draws it again after a restart. Roadmaps, logos and maps are
drawn again when the network changes, boards and holograms whenever a departure changes. When
WorldEdit is installed its selection is used instead of the wand's whenever you have one.

The wand also gives a station a footprint and binds signs in bulk:

```
/atcs station footprint main    (players inside are at Main St. for /atcs station here; BlueMap draws the box)
/atcs station bindall main      (every TrainCarts station sign in the selection becomes a platform trigger)
```

<!-- --8<-- [end:displays] -->
<!-- --8<-- [start:commands] -->
## Commands

| Command | Permission | |
|---|---|---|
| `/atcs departures <station> [platform]` | atcs.use | Next departures |
| `/atcs station list\|info\|here` | atcs.use | Stations; `here` names the platform you stand on |
| `/atcs station create\|rename\|remove\|bind\|unbind\|bindall\|footprint\|platform\|locate\|code\|map\|convert` | atcs.manage | Edit stations; `bindall` and `footprint` use the wand selection; `map` pins, hides or labels a station on the website's diagram; `convert` rewrites TrainCarts station signs as `atcs station` signs |
| `/atcs line list\|info` | atcs.use | Lines |
| `/atcs line create\|rename\|color\|mapcolor\|badge\|addstop\|suspend\|resume\|remove` | atcs.manage | Edit lines; `addstop` adds a stop to every service running between two stations; `mapcolor` recolours a line on the diagram only |
| `/atcs service info` | atcs.use | A service and what has been learned about it |
| `/atcs service create\|stops\|addstop\|removestop\|record\|spawn\|unspawn\|fleet\|hours\|offpeak\|reverse\|rename\|circular\|continues\|match\|remove\|resetpaths` | atcs.manage | Edit services; `hours` and `offpeak` stable fleet trains by the time of day |
| `/atcs train info [train]` | atcs.use | The train you ride or look at |
| `/atcs train assign\|unassign\|auto\|hold\|release\|skip`, `/atcs trains` | atcs.manage | Assignments; `hold` keeps a train at its next station sign until `release`; `skip` runs through a station once |
| `/atcs switch list\|set\|clear` | atcs.manage | Switch signs in loaded chunks, and a direction set by hand for a while |
| `/atcs transfer list` | atcs.use | Walking transfers |
| `/atcs transfer add\|remove` | atcs.manage | Edit walking transfers |
| `/atcs board create\|add\|unbind\|remove\|list` | atcs.manage | Departure boards |
| `/atcs wand [pos1\|pos2\|clear\|info]` | atcs.manage | The selection wand |
| `/atcs display roadmap\|logo\|board\|map\|status\|hologram\|list\|remove\|redraw` | atcs.manage | Pictures in the selected item frames and hologram boards |
| `/atcs map` | atcs.use | A hand-held map of the railway |
| `/atcs import scan\|stations\|services\|write\|apply\|reset` | atcs.manage | Set-up from the world |
| `/atcs fare check\|info` | atcs.use | The fare between two stations and how it was worked out |
| `/atcs fare generate\|apply\|set\|zone\|zonefare\|formula\|currency` | atcs.manage | Edit fares |
| `/atcs timing report\|reset` | atcs.manage | How good the arrival estimates are, measured against real arrivals |
| `/atcs editor` | atcs.manage | A one-time link to the web editor (`integrations.editor`) |
| `/atcs lint` | atcs.manage | Check the network for what will not work or not show, with what to do about it |
| `/atcs undo`, `/atcs history` | atcs.manage | Restore network.yml as it was before the last command edit; the last 25 versions are kept |
| `/atcs status`, `/atcs reload`, `/atcs debug` | atcs.manage | Administration |

`/atcs line suspend a Signal failure at Main St.` shows the notice on the boss bar of Line A trains
and marks the line's departures as *Suspended* on boards until `/atcs line resume a`.

### Adding a station to a line

```
/atcs station create riverside Riverside St.
/atcs line addstop 1 riverside between frostpeak golden-islands
```

The second command puts Riverside into every service of line 1 on which Frostpeak and Golden
Islands follow each other, in either direction, and names the services it left alone (an express
that skips that stretch, say). `/atcs service addstop 1/to-main riverside after frostpeak` and
`/atcs service removestop 1/to-main riverside` edit one service. What ATCS has learned about the
other stops (travel times, stop times, platforms, recorded track) is kept; only the segments next
to the new stop are learned afresh. Then give the platforms their signs: an `atcs station` sign
(or a TrainCarts station sign bound with `/atcs station bind`) per platform.

<!-- --8<-- [end:commands] -->
## Permissions

| Permission | Default | |
|---|---|---|
| `atcs.passenger` | everyone | See passenger information while riding |
| `atcs.use` | everyone | Look up stations, lines, trains and departures |
| `atcs.manage` | op | Edit the network, boards and assignments; import, reload, status |
| `atcs.sign.build` | op | Build ATCS signs |
| `atcs.admin` | op | All of the above |

<!-- --8<-- [start:files] -->
## Files

All in `plugins/AdvancedTrainControlSystem/`:

| File | |
|---|---|
| `config.yml` | Behaviour: what passengers see, sounds, boards, displays, BlueMap, SignLink, website, importer |
| `messages.yml` | Every passenger text and the board and hologram layouts, in [MiniMessage](https://docs.advntr.dev/minimessage/format.html) |
| `network.yml` | Stations, lines, services, boards, displays and diagram adjustments. Edit in game or by hand, then `/atcs reload` |
| `fares.yml` | Fares: pairs, zones and the formula. Edit in game or by hand, then `/atcs reload` |
| `fare-matrix.yml` | The draft `/atcs fare generate` writes and `/atcs fare apply` loads |
| `import-proposal.yml` | Written by `/atcs import write` for review |
| `data/timings.yml`, `data/track-paths.yml`, `data/trips.yml`, `data/eta-samples.yml` | Learned travel times, recorded track, observed trips and scored estimates; safe to delete |
| `data/map-layout.yml` | The website diagram's saved cells, so the layout stays put; safe to delete |
| `data/events.log` | Every arrival, departure, hold and service change, one JSON line each, rotated at `logging.max-megabytes` |

`network.yml` is never overwritten behind your back: if it has an error ATCS keeps using the valid
parts but refuses edits until it is fixed, and if you edited it without `/atcs reload`, in-game edits
are refused instead of replacing your changes.

A small example (a complete one, with a loop line, a fleet, a timetable, a board with a mirror
and a walking transfer, is in `docs/examples/network.yml`; ATCS
reads it as it is, so it is a safe starting point):

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

<!-- --8<-- [end:files] -->
<!-- --8<-- [start:integrations] -->
## Integrations

**BlueMap** — adds a marker set per line, drawn in its colour along the track recorded from real
trains (a thinner straight line until a segment has been recorded; lines sharing a stretch run side
by side; a suspended line turns grey), a *Stations* set, *Walking transfers* and *Trains*, so
BlueMap's own menu toggles each. Stations are their logos: a ring in the colours of the lines
calling there with the station's short code, generated from the network, and stations served by
one line fade out when the map is far out so interchanges and ends stand out. A station's popup
lists its next departures and links to the website; a train's shows where it is going and when it
is due, and trains are drawn as their line's badge. A station with a footprint (`/atcs station
footprint`) is also drawn as a translucent box. A *Track* set, off until switched on in BlueMap's
menu, shows the railway itself: every stretch of recorded track once, in grey, with a marker at
each junction. Your own pictures replace any logo: put `<station>.png` or `line-<id>.png` into the
plugin's `logos/` folder.

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

### PlaceholderAPI

With PlaceholderAPI installed, ATCS registers placeholders for scoreboards, holograms and chat
plugins. About the player's train: `%atcs_line%`, `%atcs_line_short%`, `%atcs_service%`,
`%atcs_destination%`, `%atcs_next%`, `%atcs_eta%`, `%atcs_train%`, `%atcs_status%` and
`%atcs_station%` (the station the train is at; off a train, the nearest station). About a station's
next departure: `%atcs_next_dest_main%`, `%atcs_next_time_main%`, `%atcs_next_line_main%`,
`%atcs_next_service_main%`, `%atcs_next_platform_main%`; add `:2` for one platform
(`%atcs_next_time_main:2%`) and `_2` or `_3` for the second or third departure. `%atcs_fare_main:golden-beach%`
gives the configured fare and `%atcs_station_name_main%` a station's name.

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

<!-- --8<-- [end:integrations] -->

<!-- --8<-- [start:fares] -->
## Fares

ATCS knows the network, so it can say what a journey should cost: the route between two stations,
its stops and changes, and the track distance from the recorded paths. Fares live in `fares.yml`
in three layers; the highest that applies wins:

1. **Pairs**: an explicit fare between two stations, in either direction (`main|golden-beach: 12`).
2. **Zones**: each station in a zone (`zones: {main: 1, golden-beach: 2}`) and a fare per pair of
   zones (`zone-fares: {1-2: 8, 1-1: 4}`).
3. **Formula**: `flat` + `per-stop` for each stop travelled past + `per-change` for each change of
   train + a `distance-bands` part over the track distance, rounded to `round-to` and never under
   `minimum`.

```
/atcs fare formula flat 2
/atcs fare formula per-stop 0.5
/atcs fare zone main 1
/atcs fare zonefare 1 2 8
/atcs fare set main golden-beach 12
/atcs fare check main golden-beach
```

`check` prints the fare and the reasoning: which layer answered, the route it assumed, the stops,
changes and distance. That is how a wrong price is understood instead of guessed at.

For a whole network, `/atcs fare generate` writes every pair of stations with its fare to
`fare-matrix.yml`; correct the ones that are wrong by hand and run `/atcs fare apply`. Every pair
then becomes explicit, and the ones you changed are marked `keep: true` so a later `generate`
never touches them, while the rest follow the formula and zones again.

The website's trip planner shows the fare on each route. Other plugins (a card or gate plugin, a
shop) get fares from Bukkit's services manager:

```java
FareApi fares = Bukkit.getServicesManager().load(FareApi.class);
fares.quote("main", "golden-beach").ifPresent(quote -> charge(player, quote.fare()));
```

Every quote through the API passes a `FareQuoteEvent`, in which another plugin may change the fare
(a discount, a daily cap) and say why.
<!-- --8<-- [end:fares] -->

<!-- --8<-- [start:website] -->
## Website

ATCS writes a small website: a schematic map of the network, the next departures of every
station, the trains in service, and a realtime trip planner. It is plain static files, so any web
server can serve it.

The map is drawn the way the Hong Kong MTR and New York subway maps are: the shape of the
network follows the world (what is north of a station stays north of it, a line heading
south-west is drawn heading south-west), but every line runs straight at 0, 45 or 90 degrees with
rounded bends, the stations along a line are evenly spaced, a crowded centre is spread out and
long stretches without stations are shortened, so distances are not to scale. No line is drawn
through a station it does not serve, lines cross where they cross in the world and nowhere else,
and every name has room beside its station (long names may wrap onto two lines). An express that
leaves out stops runs on the same line as the local trains, as in New York; on such a line the
stations every train calls at are drawn as open rings, *Express trains stop* in the key.

The planner follows the actual trains: it projects every running train over its next stops from
its live position and the travel and stop times learned from your trains, allowing time for each
change. Like a journey planner it offers a few routes, each as a card with its lines, when to
board and a countdown to arrival: the fastest, the best trip starting on each line or service at
the origin (an express and a local, say), fewer changes, or walking or not, tagged *Fastest*,
*Leaves first*, *Fewest changes* or *Less walking*. Routes that are worse in every respect, far
slower, or double back through a station are left out. The picked route is drawn on the map and
stays picked while the plan updates with every refresh. A line with no train running is planned
with an assumed wait and marked as an estimate.

The diagram stays put: each browser keeps the layout it drew and lays the next network out from
it, so adding a station moves that station and its neighbours, not the whole map (open the site
with `?fresh` to lay it out from scratch). A layout kept from an older version of the map is
laid out afresh once. The editor saves a layout to `data/map-layout.yml` for
every browser to start from. Hand adjustments live under `map:` in `network.yml`:
`/atcs station map <station> pin <column> <row>` fixes a station to a cell, `hide` leaves a depot
off the diagram, `label left|right|above|below` chooses the side its name goes, and
`/atcs line mapcolor <line> <colour>` draws a line in a darker colour than its badge. Trains move
along the diagram between refreshes, from their live position and the learned times; the train
button on the map hides them.

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

<!-- --8<-- [end:website] -->
<!-- --8<-- [start:editor] -->
## Web editor

Stations, lines and services, boards, walking transfers, fares, the diagram and the configuration
files, edited in a browser instead of in chat. It is off until you turn it on:

```yaml
integrations:
  editor:
    enabled: true      # then /atcs reload
    bind: 127.0.0.1    # localhost only; put a reverse proxy in front to reach it from elsewhere
    port: 8111
```

Run `/atcs editor` in game and click the link; it works once, for ten minutes, and signs that
browser in until the session has gone unused for `session-minutes`.

The diagram from the website sits on the left and redraws as you edit. Tap a station to open it,
or drag it to pin it to another cell. On the right, a rail of sections lists stations, lines,
boards, transfers, fares, diagram adjustments, displays, settings and the network check. Stop
lists are drawn as the line itself: drag the dots to reorder, mark a stop as run through, pick a
platform. "Make the return service" reverses a service and links the two at their termini.

Nothing is written until you save. Every change is checked by the same code that reads
`network.yml`, so a mistake shows at once, with the same message a command would give, next to
the thing it is about; *Review* shows the file exactly as it will be written. Saving goes
through the same store as the commands: if someone edited the network in game or on disk
meanwhile, the editor shows both versions and asks which to keep, and learned travel times and
recorded track are kept for the stops that stay. Ctrl+S saves, Ctrl+Z and Ctrl+Shift+Z undo and
redo everything since the page was opened, and *Restore previous version* in the header is
`/atcs undo`. Saving `config.yml` or `messages.yml` under *Settings* reloads ATCS.

Displays are put up and taken down in game, because they need the world: the editor lists them
with the command that removes each.

<!-- --8<-- [end:editor] -->
<!-- --8<-- [start:troubleshooting] -->
## Troubleshooting

- **Nothing happens when a train arrives.** Check that the platform's trigger is the sign trains
  actually pass (`/atcs station info <station>`), then turn on `/atcs debug` and watch the console.
- **A train shows no line.** `/atcs train info` shows its tags and TrainCarts destination; assign it with
  a spawn sign, an ATCS service sign, match rules or `/atcs train assign`.
- **Arrival estimates show "Expected" or "~1:05".** Times are learned from trains running the
  service; give it a few trips (`/atcs service info` shows how many segments have been learned).
  Until then a stretch with recorded track is estimated from its length at the railway's usual
  speed and shown with a `~`; a stretch another service runs uses that service's times.
- **A board says "Delayed".** The train has been on its way longer than trains usually take on
  that stretch, by more than the usual spread. It is on its way; the countdown would mislead.
- **Old announce signs.** Once ATCS announces your stations you can remove TrainCarts `announce` signs
  that say the same thing.

<!-- --8<-- [end:troubleshooting] -->
<!-- --8<-- [start:building] -->
## Building

```
./gradlew build
```

The plugin is `build/libs/AdvancedTrainControlSystem-<version>.jar`.
<!-- --8<-- [end:building] -->
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the code is organised,
[docs/ROADMAP.md](docs/ROADMAP.md) for what comes next, and [CONTRIBUTING.md](CONTRIBUTING.md)
before sending a change.
