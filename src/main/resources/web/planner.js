/* Trip planner: the quickest way between two stations over the services of the network, using
 * the travel and stop times ATCS has learned, live waiting times where known, and a penalty for
 * every change of train. Works in the browser and in Node. */
(function (root) {
  'use strict';

  var DEFAULT_SEGMENT = 60;   // seconds assumed for a stretch nobody has timed yet
  var DEFAULT_WAIT = 60;      // seconds assumed until the next train when nothing is known
  var TRANSFER_PENALTY = 90;  // seconds added for changing trains, on top of the wait
  var MAX_WAIT = 600;

  function services(network) {
    var result = [];
    network.lines.forEach(function (line) {
      line.services.forEach(function (service) {
        result.push({ key: service.key, line: line, service: service });
      });
    });
    return result;
  }

  function waitFor(live, station, key) {
    if (!live || !live.departures || !live.departures[station]) return null;
    var best = null;
    live.departures[station].forEach(function (d) {
      if (d.key !== key) return;
      var eta = d.boarding ? 0 : (typeof d.eta === 'number' ? d.eta : null);
      if (eta !== null && (best === null || eta < best)) best = eta;
    });
    return best === null ? null : Math.min(best, MAX_WAIT);
  }

  /* Rides between consecutive calling stops, skipping run-through stops. */
  function rides(entry, defaultDwell) {
    var stops = entry.service.stops, result = [];
    var calling = [];
    stops.forEach(function (stop, i) { if (!stop.pass) calling.push(i); });
    if (calling.length < 2) return result;
    var count = entry.service.circular ? calling.length : calling.length - 1;
    for (var c = 0; c < count; c++) {
      var from = calling[c], to = calling[(c + 1) % calling.length];
      var seconds = 0, estimated = false, through = [];
      var i = from;
      do {
        var segment = entry.service.segments[i];
        if (typeof segment === 'number') seconds += segment; else { seconds += DEFAULT_SEGMENT; estimated = true; }
        i = i + 1 < stops.length ? i + 1 : 0;
        if (i !== to) through.push(stops[i].station);
      } while (i !== to && i !== from);
      var dwell = entry.service.dwells[from];
      seconds += typeof dwell === 'number' ? dwell : defaultDwell;
      result.push({ from: from, to: to, seconds: seconds, estimated: estimated, through: through });
    }
    return result;
  }

  function plan(network, fromId, toId, live) {
    if (!fromId || !toId || fromId === toId) return null;
    var defaultDwell = typeof network.defaultDwell === 'number' ? network.defaultDwell : 10;
    var entries = services(network);
    var byKey = {};
    entries.forEach(function (e) { byKey[e.key] = e; });

    var edges = {};
    function add(from, to, cost, info) {
      (edges[from] = edges[from] || []).push({ to: to, cost: cost, info: info });
    }
    entries.forEach(function (entry) {
      var stops = entry.service.stops;
      rides(entry, defaultDwell).forEach(function (ride) {
        var a = stops[ride.from].station, b = stops[ride.to].station;
        add(a + '@' + entry.key, b + '@' + entry.key, ride.seconds,
          { type: 'ride', key: entry.key, from: a, to: b, through: ride.through, estimated: ride.estimated });
      });
      stops.forEach(function (stop) {
        if (stop.pass) return;
        var wait = waitFor(live, stop.station, entry.key);
        var boardCost = (wait === null ? DEFAULT_WAIT : wait) + (stop.station === fromId ? 0 : TRANSFER_PENALTY);
        add(stop.station, stop.station + '@' + entry.key, boardCost, { type: 'board', key: entry.key, wait: wait });
        add(stop.station + '@' + entry.key, stop.station, 0, { type: 'alight' });
      });
      if (entry.service.continues && byKey[entry.service.continues]) {
        var next = byKey[entry.service.continues];
        var last = stops.length ? stops[stops.length - 1].station : null;
        var first = next.service.stops.length ? next.service.stops[0].station : null;
        if (last && last === first) {
          var dwell = entry.service.dwells[stops.length - 1];
          add(last + '@' + entry.key, first + '@' + next.key, typeof dwell === 'number' ? dwell : defaultDwell,
            { type: 'continue', key: next.key });
        }
      }
    });

    // Dijkstra from the origin station node to the destination station node.
    var dist = {}, prev = {}, done = {};
    dist[fromId] = 0;
    var queue = [fromId];
    while (queue.length) {
      var best = 0;
      for (var q = 1; q < queue.length; q++) if (dist[queue[q]] < dist[queue[best]]) best = q;
      var node = queue.splice(best, 1)[0];
      if (done[node]) continue;
      done[node] = true;
      if (node === toId) break;
      (edges[node] || []).forEach(function (edge) {
        var candidate = dist[node] + edge.cost;
        if (dist[edge.to] === undefined || candidate < dist[edge.to]) {
          dist[edge.to] = candidate;
          prev[edge.to] = { node: node, edge: edge };
          queue.push(edge.to);
        }
      });
    }
    if (dist[toId] === undefined) return null;

    var steps = [];
    for (var at = toId; prev[at]; at = prev[at].node) steps.unshift(prev[at]);
    var legs = [], current = null, estimated = false;
    steps.forEach(function (step) {
      var info = step.edge.info;
      if (info.type === 'board') {
        current = { key: info.key, wait: info.wait, stops: [], seconds: 0, estimated: false, sameTrain: false };
      } else if (info.type === 'continue') {
        current = { key: info.key, wait: null, stops: [], seconds: step.edge.cost, estimated: false, sameTrain: true };
      } else if (info.type === 'ride' && current) {
        if (!current.stops.length) current.stops.push(info.from);
        info.through.forEach(function (station) { current.stops.push(station); });
        current.stops.push(info.to);
        current.seconds += step.edge.cost;
        current.estimated = current.estimated || info.estimated;
      } else if (info.type === 'alight' && current) {
        legs.push(current);
        estimated = estimated || current.estimated;
        current = null;
      }
    });
    if (current && current.stops.length) { legs.push(current); estimated = estimated || current.estimated; }
    legs.forEach(function (leg) {
      var entry = byKey[leg.key];
      leg.line = entry.line;
      leg.service = entry.service;
      leg.from = leg.stops[0];
      leg.to = leg.stops[leg.stops.length - 1];
      leg.calls = leg.stops.length - 1;
    });
    return { legs: legs, totalSeconds: dist[toId], transfers: Math.max(0, legs.filter(function (l) { return !l.sameTrain; }).length - 1), estimated: estimated };
  }

  function formatDuration(seconds) {
    if (seconds === null || seconds === undefined || isNaN(seconds)) return '--';
    seconds = Math.round(seconds);
    if (seconds < 60) return seconds + ' s';
    var minutes = Math.floor(seconds / 60), rest = seconds % 60;
    if (minutes < 60) return rest ? minutes + ' min ' + rest + ' s' : minutes + ' min';
    return Math.floor(minutes / 60) + ' h ' + (minutes % 60) + ' min';
  }

  var api = { plan: plan, formatDuration: formatDuration };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.ATCSPlanner = api;
})(typeof window !== 'undefined' ? window : globalThis);
