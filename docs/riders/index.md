# What riders see

Nothing to install and nothing to learn: ATCS shows itself while you ride.

## On the train

- **Boss bar** in the colour of the line: the line badge, the service (Local, Express), where the
  train is going, the next stop and when the train should get there. The estimate comes from how
  long trains on this service really take.
- **Arrival title** with a chime as the train pulls into a station, the station's name large on
  the screen.
- **Chat announcements** when the train leaves: the next station, the lines you can change to
  there, and a note when it is the end of the line. A short "Now approaching" appears before a
  stop, and "Passing" when the train runs through a station without stopping.
- **Walking transfers**: when a station is a short walk from another, the announcement says so
  ("walk to Main St. for Line 1").
- A train that has no service yet shows the station names only, with no line or destination.

## On the platform

- **Departure boards** on signs: line, service, destination, a countdown or *Arriving* and
  *Boarding*, and the platform. Where the server uses SignLink displays, the same departures
  appear there.
- **Indicator lamps** where the operator wired them: a lamp or barrier that reacts when a train
  is at, approaching or just past the platform.

## On the web

The railway's website (ask your server for the address; with BlueMap it is the map's address
followed by `/atcs/`) has a schematic network map, every train in service, departures per
station, and a trip planner that uses the trains actually running right now and offers several
routes: fastest, leaving first, fewest changes, least walking.

## What the sounds mean

| You hear | It means |
|---|---|
| A chime as the train slows | Arriving at a station |
| A chime as it leaves (if the server enabled it) | Departing |
| A soft note before a stop (if enabled) | Approaching the next station |

Operators can change every text and sound in `messages.yml` and `config.yml`, so the words on your
server may differ slightly from the ones quoted here.
