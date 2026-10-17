/* Realtime trip planner. Every running train is projected forward over its stops from its live
 * position and arrival estimate, using the travel and stop times ATCS has learned. The earliest
 * arrival is then found over those actual trains, with a margin for each change, and over the
 * walking transfers between nearby stations. A line with no train running (or none left today)
 * is planned with an assumed wait and flagged as an estimate. options() offers several trips,
 * as a journey planner does: express or local, fewer changes, walking or not.
 *
 * All times are seconds relative to the moment the live data describes. Works in the browser and
 * in Node. */
(function (root) {
  'use strict';

  var DEFAULT_SEGMENT = 60;   // a stretch no train has timed yet
  var DEFAULT_WAIT = 90;      // assumed wait for a train nobody can see yet
  var TRANSFER = 15;          // least time to change trains at a station
  var HORIZON = 2400;         // how far ahead trains are projected
  var MAX_CALLS = 80;         // calls projected per train

  function entries(network) {
    var byKey = {};
    network.lines.forEach(function (line) {
      line.services.forEach(function (service) { byKey[service.key] = { line: line, service: service }; });
    });
    return byKey;
  }

  function segment(service, i) {
    var value = service.segments[i];
    return typeof value === 'number' && value > 0 ? value : DEFAULT_SEGMENT;
  }

  function dwell(service, i, fallback) {
    var value = service.dwells[i];
    return typeof value === 'number' && value >= 0 ? value : fallback;
  }

  /* The wait for the next train nobody can see yet: half the learned interval between departures, else the assumption. */
  function expectedWait(service, i) {
    var value = service.intervals && service.intervals[i];
    return typeof value === 'number' && value > 0 ? Math.max(20, Math.min(600, value / 2)) : DEFAULT_WAIT;
  }

  function nextIndex(service, i) {
    return i + 1 < service.stops.length ? i + 1 : (service.circular ? 0 : -1);
  }

  function defaultDwellOf(network) {
    return typeof network.defaultDwell === 'number' ? network.defaultDwell : 10;
  }

  /*
   * Appends the stops a train calls at after the last call in `calls`, which it leaves at
   * `depart` from stop `index` of `entry`. Run-through stops are folded into `via`. At the end of
   * a service the train either continues as the next one (same station) or leaves service, in
   * which case the last call gets no departure.
   */
  function extend(calls, byKey, entry, index, depart, defaultDwell, limit) {
    var service = entry.service, i = index, t = depart, guard = 0;
    while (calls.length < MAX_CALLS && t <= limit && guard++ < 1000) {
      var last = calls[calls.length - 1];
      var j = nextIndex(service, i);
      if (j < 0) {
        var next = service.continues ? byKey[service.continues] : null;
        if (next && next.service.stops.length && next.service.stops[0].station === service.stops[i].station) {
          last.boardKey = next.service.key;
          last.dep = last.arr + Math.max(0, dwell(service, i, defaultDwell));
          entry = next;
          service = next.service;
          i = 0;
          t = last.dep;
          continue;
        }
        last.dep = null;
        return;
      }
      t += segment(service, i);
      var via = [];
      var hops = 0;
      while (service.stops[j].pass && hops++ < service.stops.length) {
        var k = nextIndex(service, j);
        if (k < 0) { last.dep = null; return; }
        via.push(service.stops[j].station);
        t += segment(service, j);
        j = k;
      }
      calls.push({
        key: service.key, line: entry.line.id, index: j, station: service.stops[j].station,
        platform: service.stops[j].platform, via: via, arr: t, dep: t + dwell(service, j, defaultDwell)
      });
      i = j;
      t = calls[calls.length - 1].dep;
    }
  }

  /* Where every running train will call next, as far as the horizon. */
  function project(network, live, byKey) {
    byKey = byKey || entries(network);
    var defaultDwell = defaultDwellOf(network);
    var trains = [];
    ((live && live.trains) || []).forEach(function (train) {
      var entry = byKey[train.key];
      if (!entry) return;
      var service = entry.service, stops = service.stops, first = null;
      if (train.phase === 'at_stop' && train.last >= 0 && train.last < stops.length && !stops[train.last].pass) {
        var since = typeof train.since === 'number' ? Math.max(0, train.since) : 0;
        first = {
          index: train.last, arr: -since, standing: true,
          dep: Math.max(0, dwell(service, train.last, defaultDwell) - since),
          platform: train.platform || stops[train.last].platform
        };
      } else if (train.last >= 0 && train.next >= 0 && train.next < stops.length) {
        var eta = typeof train.eta === 'number' ? Math.max(0, train.eta) : null;
        if (eta === null) {
          var total = 0, i = train.last, guard = 0;
          while (i !== train.next && i >= 0 && guard++ < stops.length) {
            total += segment(service, i);
            i = nextIndex(service, i);
          }
          eta = total * (1 - Math.min(0.95, Math.max(0, train.fraction || 0)));
        }
        first = { index: train.next, arr: eta, dep: eta + dwell(service, train.next, defaultDwell), platform: stops[train.next].platform };
      }
      if (!first) return;
      var calls = [{
        key: service.key, line: entry.line.id, index: first.index, station: stops[first.index].station,
        platform: first.platform, via: [], arr: first.arr, dep: first.dep, standing: !!first.standing
      }];
      extend(calls, byKey, entry, first.index, first.dep, defaultDwell, HORIZON);
      trains.push({ name: train.name, calls: calls });
    });
    return trains;
  }

  function buildLeg(step, byKey) {
    var calls = step.calls, board = calls[step.board], alight = calls[step.alight];
    var key = board.boardKey || board.key;
    var entry = byKey[key];
    var stops = [], hops = [], previous = board.station, current = key, becomes = null;
    for (var k = step.board + 1; k <= step.alight; k++) {
      var call = calls[k];
      call.via.forEach(function (station) {
        hops.push({ line: call.line, a: previous, b: station });
        previous = station;
      });
      if (call.station !== previous) hops.push({ line: call.line, a: previous, b: call.station });
      previous = call.station;
      if (call.key !== current && !becomes && byKey[call.key]) {
        becomes = { key: call.key, line: byKey[call.key].line, service: byKey[call.key].service, at: calls[k - 1].station };
      }
      current = call.key;
      stops.push({ station: call.station, arr: call.arr });
    }
    return {
      type: 'ride', train: step.train, estimated: step.estimated,
      key: key, line: entry.line, service: entry.service,
      from: board.station, to: alight.station, platform: board.platform || null,
      arrives: board.arr, departs: board.dep, arrival: alight.arr,
      stops: stops, hops: hops, calls: stops.length, becomes: becomes
    };
  }

  /* Everything a search needs that does not depend on where the trip goes: projected trains,
   * the calls at each station, the last known departure per service and station, and walks. */
  function prepare(network, live) {
    var byKey = entries(network);
    var trains = project(network, live, byKey);
    var callsAt = {}, lastDeparture = {};
    trains.forEach(function (train) {
      train.calls.forEach(function (call, position) {
        call.train = train.name;
        call.position = position;
        call.list = train.calls;
        (callsAt[call.station] = callsAt[call.station] || []).push(call);
        if (call.dep !== null) {
          var key = call.boardKey || call.key;
          var known = lastDeparture[key] = lastDeparture[key] || {};
          known[call.station] = Math.max(known[call.station] === undefined ? -Infinity : known[call.station], call.dep);
        }
      });
    });
    var walks = {};
    (network.transfers || []).forEach(function (transfer) {
      var seconds = typeof transfer.walk === 'number' && transfer.walk > 0 ? transfer.walk : 120;
      var outside = transfer.outside !== false;
      (walks[transfer.a] = walks[transfer.a] || []).push({ to: transfer.b, seconds: seconds, outside: outside });
      (walks[transfer.b] = walks[transfer.b] || []).push({ to: transfer.a, seconds: seconds, outside: outside });
    });
    return { network: network, byKey: byKey, callsAt: callsAt, lastDeparture: lastDeparture, walks: walks, defaultDwell: defaultDwellOf(network) };
  }

  /*
   * The trip from `fromId` to `toId` arriving soonest, leaving no earlier than `now`, or null.
   *
   * opts.penalty   seconds added per train boarded, to prefer fewer changes
   * opts.ban       service keys that may not be boarded
   * opts.firstKey  the service the trip must start on
   * opts.firstWalk the trip must start with a walk
   */
  function search(ctx, fromId, toId, now, opts) {
    opts = opts || {};
    var penalty = opts.penalty || 0, ban = opts.ban || {};
    var byKey = ctx.byKey, defaultDwell = ctx.defaultDwell;
    var best = {}, steps = {}, done = {}, open = [fromId];
    best[fromId] = { t: now, legs: 0 };

    function cost(label) { return label.t + penalty * label.legs; }

    function relax(station, t, legs, step) {
      if (done[station]) return;
      var current = best[station], candidate = { t: t, legs: legs };
      if (current) {
        var a = cost(candidate), b = cost(current);
        if (!(a < b - 0.01 || (Math.abs(a - b) <= 0.01 && legs < current.legs))) return;
      }
      best[station] = candidate;
      steps[station] = step;
      if (open.indexOf(station) < 0) open.push(station);
    }

    function boardable(key, atOrigin) {
      if (ban[key]) return false;
      if (atOrigin && opts.firstWalk) return false;
      if (atOrigin && opts.firstKey && key !== opts.firstKey) return false;
      return true;
    }

    while (open.length) {
      var pick = 0;
      for (var q = 1; q < open.length; q++) {
        var x = best[open[q]], y = best[open[pick]];
        if (cost(x) < cost(y) || (cost(x) === cost(y) && x.legs < y.legs)) pick = q;
      }
      var station = open.splice(pick, 1)[0];
      done[station] = true;
      if (station === toId) break;
      var at = best[station].t, legs = best[station].legs;
      var atOrigin = station === fromId;
      var ready = at + (atOrigin ? 0 : TRANSFER);

      // Walking to a nearby station; it is not a change of train.
      if (!atOrigin || !opts.firstKey) {
        (ctx.walks[station] || []).forEach(function (walk) {
          relax(walk.to, at + walk.seconds, legs, { from: station, walk: walk });
        });
      }

      // The trains that will actually call here.
      (ctx.callsAt[station] || []).forEach(function (call) {
        if (call.dep === null || call.dep < ready || !boardable(call.boardKey || call.key, atOrigin)) return;
        for (var k = call.position + 1; k < call.list.length; k++) {
          relax(call.list[k].station, call.list[k].arr, legs + 1,
            { from: station, calls: call.list, board: call.position, alight: k, train: call.train, estimated: false });
        }
      });

      // A train after the last one anybody can see, on every service calling here.
      Object.keys(byKey).forEach(function (key) {
        if (!boardable(key, atOrigin)) return;
        var entry = byKey[key], stops = entry.service.stops;
        for (var i = 0; i < stops.length; i++) {
          if (stops[i].station !== station || stops[i].pass) continue;
          var known = ctx.lastDeparture[key] && ctx.lastDeparture[key][station];
          var depart = (known === undefined ? ready : Math.max(ready, known)) + expectedWait(entry.service, i);
          var calls = [{ key: key, line: entry.line.id, index: i, station: station, platform: stops[i].platform, via: [], arr: depart, dep: depart }];
          extend(calls, byKey, entry, i, depart, defaultDwell, depart + HORIZON);
          if (calls[0].dep === null) continue;
          for (var k = 1; k < calls.length; k++) {
            relax(calls[k].station, calls[k].arr, legs + 1,
              { from: station, calls: calls, board: 0, alight: k, train: null, estimated: true });
          }
        }
      });
    }

    if (!best[toId] || !steps[toId]) return null;
    var legsOut = [], cursor = toId, guard = 0;
    while (cursor !== fromId && guard++ < 64) {
      var step = steps[cursor];
      if (!step) return null;
      if (step.walk) {
        legsOut.unshift({
          type: 'walk', from: step.from, to: cursor, seconds: step.walk.seconds, outside: step.walk.outside,
          departs: best[step.from].t, arrival: best[cursor].t, calls: 0,
          hops: [{ walk: true, a: step.from, b: cursor }]
        });
      } else {
        legsOut.unshift(buildLeg(step, byKey));
      }
      cursor = step.from;
    }
    var rides = legsOut.filter(function (leg) { return leg.type === 'ride'; });
    var walked = legsOut.filter(function (leg) { return leg.type === 'walk'; });
    return {
      legs: legsOut,
      departs: legsOut[0].departs,
      arrival: best[toId].t,
      transfers: Math.max(0, rides.length - 1),
      walks: walked.length,
      walkSeconds: walked.reduce(function (sum, leg) { return sum + leg.seconds; }, 0),
      estimated: legsOut.some(function (leg) { return leg.estimated; }),
      signature: legsOut.map(function (leg) {
        return (leg.type === 'walk' ? 'walk' : leg.key) + ':' + leg.from + '>' + leg.to;
      }).join('|')
    };
  }

  /* The fastest trip, or null when the stations are not connected. */
  function plan(network, fromId, toId, live, now) {
    if (!network || !fromId || !toId || fromId === toId) return null;
    return search(prepare(network, live), fromId, toId, Math.max(0, now || 0), {});
  }

  /*
   * A few good ways to make the trip, like a journey planner shows them: the fastest, the one
   * with fewest changes, and the best trip starting on each line or service that calls at the
   * origin (an express and a local, say), or avoiding a service of the fastest trip. Trips that
   * are worse in every respect than another one, or far slower than the fastest, are left out.
   * Each trip carries tags: fastest, leaves-first, fewest-changes, least-walking.
   */
  function options(network, fromId, toId, live, now, limit) {
    if (!network || !fromId || !toId || fromId === toId) return [];
    now = Math.max(0, now || 0);
    limit = limit || 5;
    var ctx = prepare(network, live);
    var found = {};
    function add(trip) {
      if (!trip) return;
      var known = found[trip.signature];
      if (!known || trip.arrival < known.arrival - 0.01) found[trip.signature] = trip;
    }
    var fastest = search(ctx, fromId, toId, now, {});
    if (!fastest) return [];
    add(fastest);
    add(search(ctx, fromId, toId, now, { penalty: 1200 }));
    var firstKeys = {};
    Object.keys(ctx.byKey).forEach(function (key) {
      ctx.byKey[key].service.stops.forEach(function (stop) {
        if (stop.station === fromId && !stop.pass) firstKeys[key] = true;
      });
    });
    (ctx.callsAt[fromId] || []).forEach(function (call) { if (call.dep !== null) firstKeys[call.boardKey || call.key] = true; });
    Object.keys(firstKeys).forEach(function (key) { add(search(ctx, fromId, toId, now, { firstKey: key })); });
    if ((ctx.walks[fromId] || []).length) add(search(ctx, fromId, toId, now, { firstWalk: true }));
    fastest.legs.forEach(function (leg) {
      if (leg.type !== 'ride') return;
      var ban = {};
      ban[leg.key] = true;
      add(search(ctx, fromId, toId, now, { ban: ban }));
    });

    var trips = Object.keys(found).map(function (key) { return found[key]; }).filter(function (trip) {
      return !revisits(trip);
    });
    if (!trips.length) return [];
    var best = Math.min.apply(null, trips.map(function (t) { return t.arrival; }));
    var fewest = Math.min.apply(null, trips.map(function (t) { return t.transfers; }));
    var duration = Math.max(1, best - now);
    trips = trips.filter(function (trip) {
      // Each change more than the fastest trip has must be worth two minutes.
      var extra = Math.max(0, trip.transfers - fastest.transfers) * 120;
      var slack = trip.transfers === fewest && trip.transfers < fastest.transfers
        ? Math.max(600, duration) : Math.max(300, duration * 0.6);
      return trip.arrival + extra <= best + slack;
    });
    trips.sort(function (a, b) {
      return a.arrival - b.arrival || a.transfers - b.transfers || a.walkSeconds - b.walkSeconds || b.departs - a.departs;
    });
    trips.forEach(function (trip) {
      var first = firstRide(trip);
      trip.boards = first ? first.departs : trip.departs;
      trip.guessed = !!(first && first.estimated);
      trip.firstKey = first ? first.key : null;
    });
    var kept = [];
    trips.forEach(function (trip) {
      var pointless = kept.some(function (other) {
        if (other.arrival > trip.arrival + 1 || other.transfers > trip.transfers || other.walkSeconds > trip.walkSeconds) return false;
        if (other.estimated && !trip.estimated) return false;
        // Where neither first train can be seen, leaving times are guesses: an express and a
        // local both stay on offer.
        if (trip.guessed && other.guessed && trip.firstKey !== other.firstKey) return false;
        return other.boards <= trip.boards + 1;
      });
      if (!pointless) kept.push(trip);
    });
    kept = kept.slice(0, limit);

    var firstBoarding = Math.min.apply(null, kept.map(function (t) { return t.guessed ? Infinity : t.boards; }));
    var leastChanges = Math.min.apply(null, kept.map(function (t) { return t.transfers; }));
    var leastWalk = Math.min.apply(null, kept.map(function (t) { return t.walkSeconds; }));
    kept.forEach(function (trip, index) {
      trip.tags = [];
      if (index === 0) trip.tags.push('fastest');
      if (index > 0 && !trip.guessed && trip.boards <= firstBoarding + 1 && trip.boards < kept[0].boards - 30) trip.tags.push('leaves-first');
      if (index > 0 && trip.transfers === leastChanges && trip.transfers < kept[0].transfers) trip.tags.push('fewest-changes');
      if (index > 0 && trip.walkSeconds === leastWalk && trip.walkSeconds < kept[0].walkSeconds) trip.tags.push('least-walking');
      trip.following = following(ctx, trip);
    });
    return kept;
  }

  function firstRide(trip) {
    for (var i = 0; i < trip.legs.length; i++) if (trip.legs[i].type === 'ride') return trip.legs[i];
    return null;
  }

  /* The index of the first call at the station on the service, or -1. */
  function stopIndex(service, stationId) {
    for (var i = 0; i < service.stops.length; i++) {
      if (service.stops[i].station === stationId && !service.stops[i].pass) return i;
    }
    return -1;
  }

  /*
   * Times a trip along a path that is already chosen, against the live data: each ride is taken
   * on the first train that can be seen calling at its boarding station on that service, after
   * the traveller is ready, and reaching the leg's end; where no such train can be seen, an
   * assumed wait is used and the leg is flagged as an estimate. The path never changes, so the
   * options a visitor chooses between (from options() without live data) keep their place while
   * their times move. Returns null when the path cannot be followed on the network any more.
   */
  function timeTrip(ctx, trip, now) {
    var byKey = ctx.byKey, defaultDwell = ctx.defaultDwell;
    var legs = [], at = Math.max(0, now || 0), estimated = false;
    for (var i = 0; i < trip.legs.length; i++) {
      var leg = trip.legs[i];
      if (leg.type === 'walk') {
        legs.push(assign({}, leg, { departs: at, arrival: at + leg.seconds }));
        at += leg.seconds;
        continue;
      }
      var entry = byKey[leg.key];
      if (!entry) return null;
      var ready = i === 0 ? at : at + TRANSFER;
      var best = null;
      (ctx.callsAt[leg.from] || []).forEach(function (call) {
        if (call.dep === null || call.dep < ready || (call.boardKey || call.key) !== leg.key) return;
        for (var k = call.position + 1; k < call.list.length; k++) {
          if (call.list[k].station !== leg.to) continue;
          if (!best || call.list[k].arr < best.list[best.k].arr - 0.01) best = { board: call, list: call.list, k: k };
          break;
        }
      });
      var timed;
      if (best) {
        timed = {
          train: best.board.train, estimated: false, platform: best.board.platform || leg.platform || null,
          arrives: best.board.arr, departs: best.board.dep, arrival: best.list[best.k].arr, stops: []
        };
        for (var p = best.board.position + 1; p <= best.k; p++) timed.stops.push({ station: best.list[p].station, arr: best.list[p].arr });
      } else {
        var index = stopIndex(entry.service, leg.from);
        if (index < 0) return null;
        var known = ctx.lastDeparture[leg.key] && ctx.lastDeparture[leg.key][leg.from];
        var depart = (known === undefined ? ready : Math.max(ready, known)) + expectedWait(entry.service, index);
        var calls = [{ key: leg.key, line: entry.line.id, index: index, station: leg.from, platform: entry.service.stops[index].platform, via: [], arr: depart, dep: depart }];
        extend(calls, byKey, entry, index, depart, defaultDwell, depart + HORIZON);
        var end = -1;
        for (var q = 1; q < calls.length; q++) if (calls[q].station === leg.to) { end = q; break; }
        if (end < 0) return null;
        timed = { train: null, estimated: true, platform: leg.platform || null, arrives: depart, departs: depart, arrival: calls[end].arr, stops: [] };
        for (var r = 1; r <= end; r++) timed.stops.push({ station: calls[r].station, arr: calls[r].arr });
        estimated = true;
      }
      legs.push(assign({}, leg, timed));
      at = timed.arrival;
    }
    if (!legs.length) return null;
    var result = { legs: legs, departs: legs[0].departs, arrival: at, estimated: estimated, signature: trip.signature, transfers: trip.transfers, walks: trip.walks, walkSeconds: trip.walkSeconds, tags: trip.tags };
    result.following = following(ctx, result);
    return result;
  }

  function assign(target) {
    for (var i = 1; i < arguments.length; i++) {
      var source = arguments[i];
      for (var key in source) if (Object.prototype.hasOwnProperty.call(source, key)) target[key] = source[key];
    }
    return target;
  }

  /* Whether a trip passes the same station twice, such as riding a loop the wrong way to change
   * back through the station it started from. */
  function revisits(trip) {
    var seen = {};
    seen[trip.legs[0].from] = true;
    for (var i = 0; i < trip.legs.length; i++) {
      var hops = trip.legs[i].hops || [];
      for (var h = 0; h < hops.length; h++) {
        if (seen[hops[h].b]) return true;
        seen[hops[h].b] = true;
      }
    }
    return false;
  }

  /* When the train after the one this trip starts on leaves the origin, if one can be seen. */
  function following(ctx, trip) {
    var first = null;
    for (var i = 0; i < trip.legs.length; i++) {
      if (trip.legs[i].type === 'ride') { first = trip.legs[i]; break; }
      if (trip.legs[i].type !== 'walk') return null;
    }
    if (!first || first.estimated) return null;
    var next = null;
    (ctx.callsAt[first.from] || []).forEach(function (call) {
      if (call.dep === null || (call.boardKey || call.key) !== first.key || call.train === first.train) return;
      if (call.dep > first.departs + 1 && (next === null || call.dep < next)) next = call.dep;
    });
    return next;
  }

  function formatDuration(seconds) {
    if (seconds === null || seconds === undefined || isNaN(seconds)) return '--';
    seconds = Math.max(0, Math.round(seconds));
    if (seconds < 60) return seconds + ' s';
    var minutes = Math.floor(seconds / 60), rest = seconds % 60;
    if (minutes < 60) return rest ? minutes + ' min ' + rest + ' s' : minutes + ' min';
    return Math.floor(minutes / 60) + ' h ' + (minutes % 60) + ' min';
  }

  /* A countdown: 0:07, 3:41, 1:02:30. */
  function formatClock(seconds) {
    seconds = Math.max(0, Math.round(seconds));
    var h = Math.floor(seconds / 3600), m = Math.floor((seconds % 3600) / 60), s = seconds % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') + m : String(m)) + ':' + (s < 10 ? '0' : '') + s;
  }

  var api = {
    plan: plan, options: options, project: project, prepare: prepare, timeTrip: timeTrip,
    formatDuration: formatDuration, formatClock: formatClock, TRANSFER: TRANSFER, DEFAULT_WAIT: DEFAULT_WAIT
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.ATCSPlanner = api;
})(typeof window !== 'undefined' ? window : globalThis);
