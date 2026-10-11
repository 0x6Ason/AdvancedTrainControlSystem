/* Realtime trip planner. Every running train is projected forward over its stops from its live
 * position and arrival estimate, using the travel and stop times ATCS has learned. The earliest
 * arrival is then found over those actual trains, with a margin for each change. A line with no
 * train running (or none left today) is planned with an assumed wait and flagged as an estimate.
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
      train: step.train, estimated: step.estimated,
      key: key, line: entry.line, service: entry.service,
      from: board.station, to: alight.station, platform: board.platform || null,
      arrives: board.arr, departs: board.dep, arrival: alight.arr,
      stops: stops, hops: hops, calls: stops.length, becomes: becomes
    };
  }

  /*
   * The trip from `fromId` to `toId` arriving soonest, leaving no earlier than `now`.
   * Returns null when the stations are not connected.
   */
  function plan(network, fromId, toId, live, now) {
    if (!network || !fromId || !toId || fromId === toId) return null;
    now = Math.max(0, now || 0);
    var byKey = entries(network);
    var defaultDwell = defaultDwellOf(network);
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

    var best = {}, steps = {}, done = {}, open = [fromId];
    best[fromId] = { t: now, legs: 0 };

    function relax(station, t, legs, step) {
      if (done[station]) return;
      var current = best[station];
      if (current && !(t < current.t - 0.01 || (Math.abs(t - current.t) <= 0.01 && legs < current.legs))) return;
      best[station] = { t: t, legs: legs };
      steps[station] = step;
      if (open.indexOf(station) < 0) open.push(station);
    }

    while (open.length) {
      var pick = 0;
      for (var q = 1; q < open.length; q++) {
        var a = best[open[q]], b = best[open[pick]];
        if (a.t < b.t || (a.t === b.t && a.legs < b.legs)) pick = q;
      }
      var station = open.splice(pick, 1)[0];
      done[station] = true;
      if (station === toId) break;
      var at = best[station].t, legs = best[station].legs;
      var ready = at + (station === fromId ? 0 : TRANSFER);

      // The trains that will actually call here.
      (callsAt[station] || []).forEach(function (call) {
        if (call.dep === null || call.dep < ready) return;
        for (var k = call.position + 1; k < call.list.length; k++) {
          relax(call.list[k].station, call.list[k].arr, legs + 1,
            { from: station, calls: call.list, board: call.position, alight: k, train: call.train, estimated: false });
        }
      });

      // A train after the last one anybody can see, on every service calling here.
      Object.keys(byKey).forEach(function (key) {
        var entry = byKey[key], stops = entry.service.stops;
        for (var i = 0; i < stops.length; i++) {
          if (stops[i].station !== station || stops[i].pass) continue;
          var known = lastDeparture[key] && lastDeparture[key][station];
          var depart = (known === undefined ? ready : Math.max(ready, known)) + DEFAULT_WAIT;
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
      legsOut.unshift(buildLeg(step, byKey));
      cursor = step.from;
    }
    return {
      legs: legsOut,
      departs: legsOut[0].departs,
      arrival: best[toId].t,
      transfers: legsOut.length - 1,
      estimated: legsOut.some(function (leg) { return leg.estimated; })
    };
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
    plan: plan, project: project, formatDuration: formatDuration, formatClock: formatClock,
    TRANSFER: TRANSFER, DEFAULT_WAIT: DEFAULT_WAIT
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.ATCSPlanner = api;
})(typeof window !== 'undefined' ? window : globalThis);
