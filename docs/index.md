# ATCS

Passenger information, departure boards, maps and station control for
[TrainCarts](https://github.com/bergerhealer/TrainCarts) railways on Paper servers.

TrainCarts drives the trains. ATCS knows your network (stations, platforms, lines and the services
trains run), follows every train through it, and tells passengers where they are. Where you ask it
to, it also runs the stations: trains that call stop, trains that run through keep going, and
junctions are set by service.

- **Riders** see a boss bar in the line colour, arrival titles and chimes, next-station and
  interchange announcements, departure boards and a website with a live trip planner.
  [What riders see](riders/index.md).
- **Operators** describe the network once, in `network.yml` or with commands, and get the rest
  from the trains that already run. [Set-up](operators/setup.md), [signs](operators/signs.md),
  [the website and maps](operators/website.md).
- **Developers** find the design in [Architecture](ARCHITECTURE.md) and ideas for what comes
  next in the [Roadmap](ROADMAP.md). ATCS is open source under the MIT licence.

## Install

--8<-- "README.md:install"

## Where to go next

| You want to | Read |
|---|---|
| Set ATCS up on a railway that already runs | [Set-up from an existing network](operators/setup.md#set-up-for-an-existing-traincarts-network) |
| Make an express skip stations, or keep trains apart | [Station signs](operators/signs.md#station-signs) |
| Put departures on signs or SignLink displays | [Departure boards](operators/boards.md), [Integrations](operators/integrations.md) |
| Look a command up | [Commands](reference/commands.md) |
| Know what a setting does | [config.yml](reference/config.md) |
