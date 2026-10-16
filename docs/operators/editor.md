# Web editor

--8<-- "README.md:editor"

## What each section does

**Stations**: the name, short code (with the sign variables it makes), map position and
footprint, platforms with their signs, the services calling there and the platform each uses, how
the station sits on the diagram, and its walking transfers. Deleting a station takes its stops out
of every service and removes its boards and walks; the dialog lists them first.

**Lines**: name, badge and colour (the route bullet follows as you type), an own colour for the
diagram, and a suspension with the notice riders see. Each line lists its services as small strip
diagrams.

**A service**: its name, what it continues as at its last stop, end to end or a loop, its stops,
the rules that tell ATCS which trains run it, its spawn signs, and a fleet of trains ATCS keeps
running itself.

**Boards**: the station, which platforms, and the signs in departure order. Signs are bound in game.

**Transfers**: walks between stations, with a time or estimated from the distance, outside or
inside the station.

**Fares**: the three layers in the order they apply: pairs set by hand, zones, the formula.

**Diagram**: saves the layout every visitor's browser starts from, lays it out from scratch,
and lists the stations that are pinned, hidden or labelled on one side, and line colours.

**Settings**: `config.yml` and `messages.yml` as text. Saving checks the YAML, writes the file and
reloads ATCS; what the reload reports is shown below.

**Check**: everything `/atcs lint` and `/atcs reload` would say, for the version on screen. Names
in the messages link to the station or service.

**Dispatch**: every train in service, where it is and what is next, refreshed every few seconds;
hold a train (with a reason riders and the log see) and release it, make it run through its next
stop or a later one, suspend a line with a notice and resume it, see which blocks are taken and
which switch signs are loaded, set a switch one way for ten minutes, and read the recent events.

## Behind a reverse proxy

The editor serves its page and API from one port. To reach it from elsewhere, keep `bind:
127.0.0.1` and proxy a path or host name to it, then set `host` to the name people use so
`/atcs editor` prints a link that works:

```yaml
integrations:
  editor:
    enabled: true
    bind: 127.0.0.1
    port: 8111
    host: editor.example.net
```

Links are single use and expire after ten minutes; sessions end after `session-minutes` unused.
Anyone with a link can edit the network, so give links only to people with `atcs.manage`.
