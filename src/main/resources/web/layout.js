/* Schematic layout: turns station coordinates and line stop orders into a transit-map style
 * drawing. Coordinates are compressed onto an even grid (long empty stretches shrink, close
 * stations spread out), every stretch of track becomes one straight or one diagonal-plus-straight
 * path, and lines sharing a stretch run side by side. Works in the browser and in Node. */
(function (root) {
  'use strict';

  var CELL = 100;          // grid spacing in map units
  var STROKE = 9;          // line width in map units
  var GAP = 4;             // space between parallel lines

  function clusterAxis(values) {
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    if (!sorted.length) return {};
    var span = sorted[sorted.length - 1] - sorted[0];
    var tolerance = Math.max(20, span * 0.035);
    var slots = {};
    var slot = 0;
    var previous = null;
    sorted.forEach(function (value) {
      if (previous !== null && value - previous > tolerance) slot++;
      slots[value] = slot;
      previous = value;
    });
    return slots;
  }

  function sign(value) { return value > 0 ? 1 : value < 0 ? -1 : 0; }

  /* A→B as one straight stroke or a diagonal followed by a straight. The diagonal always leaves
   * the station with the lexically smaller id, so parallel lines bend the same way. */
  function octilinear(a, b, diagonalFirst) {
    var du = b.u - a.u, dv = b.v - a.v;
    var adu = Math.abs(du), adv = Math.abs(dv);
    if (du === 0 || dv === 0 || adu === adv) return [{ u: a.u, v: a.v }, { u: b.u, v: b.v }];
    var d = Math.min(adu, adv);
    var corner = diagonalFirst
      ? { u: a.u + sign(du) * d, v: a.v + sign(dv) * d }
      : { u: b.u - sign(du) * d, v: b.v - sign(dv) * d };
    return [{ u: a.u, v: a.v }, corner, { u: b.u, v: b.v }];
  }

  function offsetPath(points, offset) {
    if (!offset) return points;
    var normals = [];
    for (var i = 0; i < points.length - 1; i++) {
      var dx = points[i + 1].u - points[i].u, dy = points[i + 1].v - points[i].v;
      var length = Math.hypot(dx, dy) || 1;
      normals.push({ u: -dy / length, v: dx / length });
    }
    return points.map(function (point, i) {
      var n = i === 0 ? normals[0] : i === points.length - 1 ? normals[normals.length - 1]
        : { u: (normals[i - 1].u + normals[i].u) / 2, v: (normals[i - 1].v + normals[i].v) / 2 };
      return { u: point.u + n.u * offset, v: point.v + n.v * offset };
    });
  }

  function pairKey(a, b) { return a < b ? a + '|' + b : b + '|' + a; }

  function build(network) {
    var placed = network.stations.filter(function (s) { return typeof s.x === 'number' && typeof s.z === 'number'; });
    var cols = clusterAxis(placed.map(function (s) { return s.x; }));
    var rows = clusterAxis(placed.map(function (s) { return s.z; }));
    var stations = {};
    var byCell = {};
    placed.forEach(function (s) {
      var node = { id: s.id, name: s.name, u: cols[s.x] * CELL, v: rows[s.z] * CELL, lines: s.lines || [], interchange: (s.lines || []).length > 1 };
      stations[s.id] = node;
      var cell = node.u + ':' + node.v;
      (byCell[cell] = byCell[cell] || []).push(node);
    });
    Object.keys(byCell).forEach(function (cell) {
      var group = byCell[cell];
      if (group.length < 2) return;
      group.sort(function (a, b) { return a.id < b.id ? -1 : 1; });
      group.forEach(function (node, i) { node.v += (i - (group.length - 1) / 2) * CELL * 0.45; });
    });

    // Every stretch of track per line, from the stop orders of its services.
    var edgeLines = {};   // pair key -> [line ids]
    var lines = network.lines.map(function (line) {
      var pairs = {};
      line.services.forEach(function (service) {
        var stops = service.stops;
        for (var i = 0; i < stops.length; i++) {
          var j = i + 1 < stops.length ? i + 1 : (service.circular ? 0 : -1);
          if (j < 0 || j === i) continue;
          var a = stops[i].station, b = stops[j].station;
          if (a === b || !stations[a] || !stations[b]) continue;
          pairs[pairKey(a, b)] = true;
        }
      });
      Object.keys(pairs).forEach(function (key) {
        var list = edgeLines[key] = edgeLines[key] || [];
        if (list.indexOf(line.id) < 0) list.push(line.id);
      });
      return { id: line.id, name: line.name, short: line.short, color: line.color, suspended: !!line.suspended, pairs: Object.keys(pairs), edges: [] };
    });

    var bounds = null;
    function extend(point) {
      if (!bounds) bounds = { minU: point.u, maxU: point.u, minV: point.v, maxV: point.v };
      bounds.minU = Math.min(bounds.minU, point.u); bounds.maxU = Math.max(bounds.maxU, point.u);
      bounds.minV = Math.min(bounds.minV, point.v); bounds.maxV = Math.max(bounds.maxV, point.v);
    }
    Object.keys(stations).forEach(function (id) { extend(stations[id]); });

    lines.forEach(function (line) {
      line.pairs.forEach(function (key) {
        var ids = key.split('|');
        var a = stations[ids[0]], b = stations[ids[1]];
        var sharing = edgeLines[key];
        var index = sharing.indexOf(line.id);
        var offset = (index - (sharing.length - 1) / 2) * (STROKE + GAP);
        var points = offsetPath(octilinear(a, b, true), offset);
        points.forEach(extend);
        line.edges.push({ a: ids[0], b: ids[1], points: points });
      });
    });

    return {
      cell: CELL,
      stroke: STROKE,
      stations: stations,
      lines: lines,
      bounds: bounds,
      /* The drawn path of a line between two adjacent stations, oriented from a to b. */
      path: function (lineId, a, b) {
        for (var i = 0; i < lines.length; i++) {
          if (lines[i].id !== lineId) continue;
          for (var e = 0; e < lines[i].edges.length; e++) {
            var edge = lines[i].edges[e];
            if (edge.a === a && edge.b === b) return edge.points;
            if (edge.a === b && edge.b === a) return edge.points.slice().reverse();
          }
        }
        return null;
      }
    };
  }

  /* A point a fraction (0..1) along a polyline. */
  function along(points, fraction) {
    if (!points || !points.length) return null;
    var total = 0, lengths = [];
    for (var i = 0; i < points.length - 1; i++) {
      var l = Math.hypot(points[i + 1].u - points[i].u, points[i + 1].v - points[i].v);
      lengths.push(l); total += l;
    }
    if (!total) return points[0];
    var target = Math.max(0, Math.min(1, fraction)) * total;
    for (var k = 0; k < lengths.length; k++) {
      if (target <= lengths[k] || k === lengths.length - 1) {
        var t = lengths[k] ? target / lengths[k] : 0;
        return { u: points[k].u + (points[k + 1].u - points[k].u) * t, v: points[k].v + (points[k + 1].v - points[k].v) * t };
      }
      target -= lengths[k];
    }
    return points[points.length - 1];
  }

  var api = { build: build, along: along, CELL: CELL, STROKE: STROKE };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.ATCSLayout = api;
})(typeof window !== 'undefined' ? window : globalThis);
