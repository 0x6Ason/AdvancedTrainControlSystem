/* Schematic layout for a transit-map style drawing. Works in the browser and in Node.
 *
 * 1. Positions keep order and are evenly spaced. Every station keeps its real east-west and
 *    north-south order relative to every other station (two may come level, never swap), so a
 *    line is always drawn through its stops in the right sequence. Rows and columns are merged
 *    wherever no stations clash, and the rest are spaced evenly, as on a subway diagram.
 * 2. Every stretch of track becomes an octilinear path (straight, or straight and 45 degrees). Of
 *    the possible shapes, the one that passes no other station, runs along no other line and bends
 *    least is used.
 * 3. Lines sharing a stretch run side by side; walking transfers are straight connectors.
 * 4. Labels go where they cover no station, label, line or connector, preferring the right side.
 * 5. A saved layout (the seed) keeps every station where it was: new stations slot in between
 *    their neighbours and nothing else moves. Hand adjustments from network.yml (map:) pin a
 *    station to a cell, hide it, choose its label side or recolour a line.
 */
(function (root) {
  'use strict';

  var CELL = 100;            // spacing of columns and rows at a typical distance, in map units
  var STROKE = 9;            // line width
  var GAP = 4;               // space between lines running side by side
  var CLEAR = 22;            // how close a line may pass a station it does not serve
  var BESIDE = STROKE + 3;   // closer than this, two lines read as one
  var KINK = 30;             // size of the bend that takes a straight line around a station
  var PASSES = 4;            // rerouting rounds once every stretch knows about all the others
  var LABEL_HEIGHT = 18;

  // ---- Geometry --------------------------------------------------------------------------------

  function sign(value) { return value > 0 ? 1 : value < 0 ? -1 : 0; }

  function distanceToSegment(p, a, b) {
    var du = b.u - a.u, dv = b.v - a.v, length2 = du * du + dv * dv;
    var t = length2 ? ((p.u - a.u) * du + (p.v - a.v) * dv) / length2 : 0;
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(p.u - (a.u + t * du), p.v - (a.v + t * dv));
  }

  function distanceToPath(p, points) {
    var best = Infinity;
    for (var i = 0; i < points.length - 1; i++) best = Math.min(best, distanceToSegment(p, points[i], points[i + 1]));
    return best;
  }

  function cross(a, b, c) { return (b.u - a.u) * (c.v - a.v) - (b.v - a.v) * (c.u - a.u); }

  function crosses(a, b, c, d) {
    var e = 1e-6;
    var d1 = cross(c, d, a), d2 = cross(c, d, b), d3 = cross(a, b, c), d4 = cross(a, b, d);
    return ((d1 > e && d2 < -e) || (d1 < -e && d2 > e)) && ((d3 > e && d4 < -e) || (d3 < -e && d4 > e));
  }

  /* How long two segments run on top of, or right beside, each other. */
  function overlap(a, b, c, d, within) {
    var length = Math.hypot(b.u - a.u, b.v - a.v), other = Math.hypot(d.u - c.u, d.v - c.v);
    if (!length || !other) return 0;
    var eu = (b.u - a.u) / length, ev = (b.v - a.v) / length;
    if (Math.abs(((d.u - c.u) * eu + (d.v - c.v) * ev) / other) < 0.999) return 0;
    if (Math.abs((c.u - a.u) * -ev + (c.v - a.v) * eu) > (within === undefined ? 3 : within)) return 0;
    var t1 = (c.u - a.u) * eu + (c.v - a.v) * ev, t2 = (d.u - a.u) * eu + (d.v - a.v) * ev;
    return Math.max(0, Math.min(length, Math.max(t1, t2)) - Math.max(0, Math.min(t1, t2)));
  }

  /* Whether a segment passes through a rectangle {x, y, w, h}. */
  function hitsBox(a, b, box) {
    var t0 = 0, t1 = 1, du = b.u - a.u, dv = b.v - a.v;
    var checks = [[-du, a.u - box.x], [du, box.x + box.w - a.u], [-dv, a.v - box.y], [dv, box.y + box.h - a.v]];
    for (var i = 0; i < 4; i++) {
      var p = checks[i][0], q = checks[i][1];
      if (p === 0) { if (q < 0) return false; continue; }
      var r = q / p;
      if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r; } else { if (r < t0) return false; if (r < t1) t1 = r; }
    }
    return true;
  }

  function boxesOverlap(a, b) {
    return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
  }

  function pointInBox(p, box, margin) {
    return p.u > box.x - margin && p.u < box.x + box.w + margin && p.v > box.y - margin && p.v < box.y + box.h + margin;
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
      if (i === 0) return { u: point.u + normals[0].u * offset, v: point.v + normals[0].v * offset };
      if (i === points.length - 1) {
        var last = normals[normals.length - 1];
        return { u: point.u + last.u * offset, v: point.v + last.v * offset };
      }
      var n1 = normals[i - 1], n2 = normals[i];
      var mu = n1.u + n2.u, mv = n1.v + n2.v, scale = (mu * n1.u + mv * n1.v) || 1;
      return { u: point.u + mu / scale * offset, v: point.v + mv / scale * offset };
    });
  }

  // ---- 1. Positions ----------------------------------------------------------------------------

  function median(values) {
    if (!values.length) return 0;
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var middle = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  }

  /* Groups the coordinates of one axis into ordered slots; values a few blocks apart share one. */
  function slotsOf(values, merge) {
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var slots = {}, slot = -1, first = null, last = null;
    sorted.forEach(function (value) {
      if (last === null || value - last > merge || value - first > merge * 2) {
        slot++;
        first = value;
      }
      slots[value] = slot;
      last = value;
    });
    return slots;
  }

  function renumber(nodes, axis) {
    var used = [];
    nodes.forEach(function (n) { if (used.indexOf(n[axis]) < 0) used.push(n[axis]); });
    used.sort(function (a, b) { return a - b; });
    nodes.forEach(function (n) { n[axis] = used.indexOf(n[axis]); });
  }

  /*
   * Merges neighbouring rows (or columns) wherever that puts no two stations on the same spot, no
   * station on a straight stretch between two others, and still leaves every stretch of track a
   * route that passes no other station. Closest real coordinates are merged first. This is what
   * gives the diagram its even, compact spacing. Order is never reversed: stations only come level.
   */
  function compact(nodes, axis, other, raw, edges, columnStep, rowStep) {
    for (var guard = 0; guard < 1000; guard++) {
      renumber(nodes, axis);
      var count = 1 + Math.max.apply(null, nodes.map(function (n) { return n[axis]; }));
      var mean = [];
      for (var s = 0; s < count; s++) {
        var members = nodes.filter(function (n) { return n[axis] === s; });
        mean.push(members.reduce(function (sum, n) { return sum + n[raw]; }, 0) / members.length);
      }
      var candidates = [];
      for (var i = 0; i + 1 < count; i++) candidates.push({ slot: i, gap: mean[i + 1] - mean[i] });
      candidates.sort(function (x, y) { return x.gap - y.gap; });
      var merged = false;
      for (var c = 0; c < candidates.length && !merged; c++) {
        var slot = candidates[c].slot;
        if (!mergeable(nodes, axis, other, edges, slot, slot + 1)) continue;
        var moved = nodes.filter(function (n) { return n[axis] === slot + 1; });
        moved.forEach(function (n) { n[axis] = slot; });
        if (routable(nodes, edges, columnStep, rowStep)) {
          merged = true;
        } else {
          moved.forEach(function (n) { n[axis] = slot + 1; });
        }
      }
      if (!merged) return;
    }
  }

  /* Whether every stretch of track has at least one shape that passes no other station. */
  function routable(nodes, edges, columnStep, rowStep) {
    var cols = [], rows = [];
    nodes.forEach(function (n) {
      if (cols.indexOf(n.col) < 0) cols.push(n.col);
      if (rows.indexOf(n.row) < 0) rows.push(n.row);
    });
    cols.sort(function (a, b) { return a - b; });
    rows.sort(function (a, b) { return a - b; });
    var at = {}, list = [];
    nodes.forEach(function (n) {
      var p = { id: n.id, u: cols.indexOf(n.col) * columnStep, v: rows.indexOf(n.row) * rowStep };
      at[n.id] = p;
      list.push(p);
    });
    for (var e = 0; e < edges.length; e++) {
      var a = at[edges[e][0]], b = at[edges[e][1]];
      if (!a || !b) continue;
      var free = shapes(a, b).some(function (points) {
        return list.every(function (s) { return s.id === a.id || s.id === b.id || distanceToPath(s, points) >= CLEAR; });
      });
      if (!free) return false;
    }
    return true;
  }

  function mergeable(nodes, axis, other, edges, a, b) {
    var group = nodes.filter(function (n) { return n[axis] === a || n[axis] === b; });
    for (var i = 0; i < group.length; i++) {
      for (var j = i + 1; j < group.length; j++) {
        if (group[i][axis] !== group[j][axis] && group[i][other] === group[j][other]) return false;
      }
    }
    var inGroup = {};
    group.forEach(function (n) { inGroup[n.id] = n; });
    for (var e = 0; e < edges.length; e++) {
      var p = inGroup[edges[e][0]], q = inGroup[edges[e][1]];
      if (!p || !q) continue;
      var lo = Math.min(p[other], q[other]), hi = Math.max(p[other], q[other]);
      for (var k = 0; k < group.length; k++) {
        var r = group[k];
        if (r !== p && r !== q && r[other] > lo && r[other] < hi) return false;
      }
    }
    return true;
  }

  function trackPairs(network) {
    var seen = {}, edges = [];
    network.lines.forEach(function (line) {
      line.services.forEach(function (service) {
        for (var i = 0; i < service.stops.length; i++) {
          var j = i + 1 < service.stops.length ? i + 1 : (service.circular ? 0 : -1);
          if (j < 0) continue;
          var a = service.stops[i].station, b = service.stops[j].station;
          if (a === b) continue;
          var key = a < b ? a + '|' + b : b + '|' + a;
          if (!seen[key]) { seen[key] = true; edges.push([a, b]); }
        }
      });
    });
    return edges;
  }

  /*
   * Stations on an even grid: real coordinates give each station a row and a column, rows and
   * columns are merged wherever nothing clashes, and what remains is spaced evenly.
   */
  /* Slots for stations without a seed: between the seeded neighbours along the real coordinate. */
  function seededSlots(nodes, seed, axis, slot) {
    var known = nodes.filter(function (n) { return seed[n.id] && typeof seed[n.id][slot] === 'number'; });
    known.sort(function (a, b) { return a[axis] - b[axis]; });
    nodes.forEach(function (n) {
      if (seed[n.id] && typeof seed[n.id][slot] === 'number') { n[slot] = seed[n.id][slot]; return; }
      var lower = null, upper = null;
      known.forEach(function (k) {
        if (k[axis] <= n[axis]) lower = k;
        if (k[axis] >= n[axis] && !upper) upper = k;
      });
      if (lower && upper && upper !== lower) {
        var span = upper[axis] - lower[axis];
        n[slot] = lower[slot] + (upper[slot] - lower[slot]) * (span ? (n[axis] - lower[axis]) / span : 0.5);
        if (upper[slot] === lower[slot]) n[slot] = lower[slot];
      } else if (lower) {
        n[slot] = lower[slot] + (lower[axis] === n[axis] ? 0 : 1);
      } else if (upper) {
        n[slot] = upper[slot] - (upper[axis] === n[axis] ? 0 : 1);
      } else {
        n[slot] = 0;
      }
    });
  }

  function placeStations(network, columnStep, rowStep, packRows, packColumns, seed, overrides) {
    var placed = network.stations.filter(function (s) { return typeof s.x === 'number' && typeof s.z === 'number'; });
    var adjust = (overrides && overrides.stations) || {};
    var nearest = placed.map(function (s) {
      var best = Infinity;
      placed.forEach(function (o) { if (o !== s) best = Math.min(best, Math.hypot(o.x - s.x, o.z - s.z)); });
      return best;
    }).filter(isFinite);
    var merge = Math.max(2, Math.min(10, median(nearest) * 0.25));
    var columns = slotsOf(placed.map(function (s) { return s.x; }), merge);
    var rows = slotsOf(placed.map(function (s) { return s.z; }), merge);
    var nodes = placed.map(function (s) {
      var node = { id: s.id, name: s.name, x: s.x, z: s.z, col: columns[s.x], row: rows[s.z], lines: s.lines || [] };
      node.interchange = node.lines.length > 1;
      node.labelSide = adjust[s.id] && adjust[s.id].label ? adjust[s.id].label : null;
      return node;
    });
    var seeded = seed && seed.stations && nodes.some(function (n) { return seed.stations[n.id]; });
    if (seeded) {
      seededSlots(nodes, seed.stations, 'x', 'col');
      seededSlots(nodes, seed.stations, 'z', 'row');
    }
    // Stations a few blocks apart in both directions would share a spot: give each its own
    // row or column along the direction they actually lie in.
    var spots = {};
    nodes.forEach(function (n) { (spots[n.col + ':' + n.row] = spots[n.col + ':' + n.row] || []).push(n); });
    Object.keys(spots).forEach(function (key) {
      var group = spots[key];
      if (group.length < 2) return;
      var spreadX = Math.max.apply(null, group.map(function (n) { return n.x; })) - Math.min.apply(null, group.map(function (n) { return n.x; }));
      var spreadZ = Math.max.apply(null, group.map(function (n) { return n.z; })) - Math.min.apply(null, group.map(function (n) { return n.z; }));
      var vertical = spreadZ >= spreadX;
      group.sort(function (a, b) { return vertical ? a.z - b.z || a.x - b.x : a.x - b.x || a.z - b.z; });
      group.forEach(function (n, i) {
        if (vertical) n.row += i / group.length; else n.col += i / group.length;
      });
    });
    renumber(nodes, 'row');
    renumber(nodes, 'col');
    var edges = trackPairs(network);
    // A seeded layout is already compact; compacting again would move what the seed keeps put.
    if (!seeded && packRows) compact(nodes, 'row', 'col', 'z', edges, columnStep, rowStep);
    if (!seeded && packColumns) compact(nodes, 'col', 'row', 'x', edges, columnStep, rowStep);
    // Pins are absolute cells, counted from the top left, applied last so nothing else moves them.
    nodes.forEach(function (n) {
      var pin = adjust[n.id];
      if (pin && typeof pin.column === 'number' && typeof pin.row === 'number') { n.col = pin.column; n.row = pin.row; }
    });
    var stations = {};
    nodes.forEach(function (n) {
      n.u = n.col * columnStep;
      n.v = n.row * rowStep;
      stations[n.id] = n;
    });
    return stations;
  }

  // ---- 2. Track --------------------------------------------------------------------------------

  function shapes(a, b) {
    var du = b.u - a.u, dv = b.v - a.v, adu = Math.abs(du), adv = Math.abs(dv);
    var start = { u: a.u, v: a.v }, end = { u: b.u, v: b.v };
    var list = [];
    if (adu < 0.5 || adv < 0.5 || Math.abs(adu - adv) < 0.5) {
      list.push([start, end]);
      var length = Math.hypot(du, dv);
      if (length > 2 * KINK + 10) {
        var eu = du / length, ev = dv / length, nu = -ev, nv = eu;
        var step = KINK / Math.max(Math.abs(eu), Math.abs(ev));
        [1, -1].forEach(function (side) {
          var out = { u: nu * side * step, v: nv * side * step };
          list.push([start,
            { u: a.u + eu * step + out.u, v: a.v + ev * step + out.v },
            { u: b.u - eu * step + out.u, v: b.v - ev * step + out.v },
            end]);
        });
      }
      return list;
    }
    // One 45-degree stretch and one straight stretch, the straight part split before and after
    // the diagonal in five ways; then the two right-angle bends.
    var d = Math.min(adu, adv), su = sign(du), sv = sign(dv), run = Math.max(adu, adv) - d;
    [0, 1, 0.5, 0.25, 0.75].forEach(function (share) {
      var before = run * share, points = [start];
      if (adu > adv) {
        if (before > 0.5) points.push({ u: a.u + su * before, v: a.v });
        if (run - before > 0.5) points.push({ u: a.u + su * (before + d), v: b.v });
      } else {
        if (before > 0.5) points.push({ u: a.u, v: a.v + sv * before });
        if (run - before > 0.5) points.push({ u: b.u, v: a.v + sv * (before + d) });
      }
      points.push(end);
      list.push(points);
    });
    list.push([start, { u: b.u, v: a.v }, end]);
    list.push([start, { u: a.u, v: b.v }, end]);
    return list;
  }

  function shapeCost(points, a, b, stationList, routed) {
    var cost = (points.length - 2) * 3;
    // Transit maps turn by 45 degrees; a right-angle turn costs extra.
    for (var k = 1; k < points.length - 1; k++) {
      var u1 = points[k].u - points[k - 1].u, v1 = points[k].v - points[k - 1].v;
      var u2 = points[k + 1].u - points[k].u, v2 = points[k + 1].v - points[k].v;
      var cosine = (u1 * u2 + v1 * v2) / ((Math.hypot(u1, v1) * Math.hypot(u2, v2)) || 1);
      if (cosine < 0.3) cost += 8;
    }
    stationList.forEach(function (station) {
      if (station.id === a.id || station.id === b.id) return;
      if (distanceToPath(station, points) < CLEAR) cost += 1000;
    });
    // A bend lying on another line reads as the two lines joining.
    for (var j = 1; j < points.length - 1; j++) {
      for (var s = 0; s < routed.length; s++) {
        if (distanceToSegment(points[j], routed[s][0], routed[s][1]) < BESIDE) { cost += 25; break; }
      }
    }
    for (var i = 0; i < points.length - 1; i++) {
      var p = points[i], q = points[i + 1];
      cost += Math.hypot(q.u - p.u, q.v - p.v) / CELL * 3;
      routed.forEach(function (segment) {
        cost += overlap(p, q, segment[0], segment[1], BESIDE) / CELL * 150;
        if (crosses(p, q, segment[0], segment[1])) cost += 6;
      });
    }
    return cost;
  }

  function pairKey(a, b) { return a < b ? a + '|' + b : b + '|' + a; }

  // Arrangements tried, tightest first: column and row spacing, and whether rows and columns are
  // packed. The one whose track is cleanest overall is used; a stretch that cannot avoid a station
  // costs so much that a looser arrangement wins.
  var ARRANGEMENTS = [
    [140, 100, true, true], [150, 110, true, true], [160, 110, true, true], [130, 90, true, true],
    [140, 100, true, false], [140, 100, false, true],
    [110, 80, false, false], [130, 90, false, false]
  ];
  // Each row or column costs this much: the diagram prefers to be compact and evenly spaced.
  var COMPACT = 25;

  function routeTrack(network, stations) {
    var pairs = {};
    network.lines.forEach(function (line, lineIndex) {
      line.services.forEach(function (service) {
        var stops = service.stops;
        for (var i = 0; i < stops.length; i++) {
          var j = i + 1 < stops.length ? i + 1 : (service.circular ? 0 : -1);
          if (j < 0 || j === i) continue;
          var a = stops[i].station, b = stops[j].station;
          if (a === b || !stations[a] || !stations[b]) continue;
          var key = pairKey(a, b);
          var pair = pairs[key] = pairs[key] || { key: key, a: a < b ? a : b, b: a < b ? b : a, lines: [] };
          if (pair.lines.indexOf(lineIndex) < 0) pair.lines.push(lineIndex);
        }
      });
    });
    var stationList = Object.keys(stations).map(function (id) { return stations[id]; });
    var order = Object.keys(pairs).map(function (key) { return pairs[key]; });
    order.forEach(function (pair) {
      var a = stations[pair.a], b = stations[pair.b];
      pair.length = Math.hypot(b.u - a.u, b.v - a.v);
      pair.lines.sort(function (x, y) { return x - y; });
    });
    order.sort(function (x, y) { return y.lines.length - x.lines.length || y.length - x.length || (x.key < y.key ? -1 : 1); });
    order.forEach(function (pair) { pair.shapes = shapes(stations[pair.a], stations[pair.b]); });

    function segmentsOf(points) {
      var list = [];
      for (var i = 0; i < points.length - 1; i++) list.push([points[i], points[i + 1]]);
      return list;
    }

    // First round: each stretch sees the ones routed before it. Later rounds: each stretch is
    // routed again knowing every other one, until nothing changes.
    for (var pass = 0; pass < PASSES; pass++) {
      var changed = false;
      order.forEach(function (pair) {
        var a = stations[pair.a], b = stations[pair.b];
        var others = [];
        order.forEach(function (other) { if (other !== pair && other.points) others = others.concat(segmentsOf(other.points)); });
        var best = null, bestCost = Infinity;
        pair.shapes.forEach(function (points) {
          var cost = shapeCost(points, a, b, stationList, others);
          if (cost < bestCost - 1e-9) { best = points; bestCost = cost; }
        });
        if (best !== pair.points) changed = true;
        pair.points = best;
        pair.cost = bestCost;
        pair.blocked = bestCost >= 1000;
      });
      if (!changed) break;
    }
    pairs.total = order.reduce(function (sum, pair) { return sum + pair.cost; }, 0);
    return pairs;
  }

  // ---- 4. Labels -------------------------------------------------------------------------------

  function textWidth(text, large) { return String(text).length * (large ? 8.2 : 7.6) + 6; }

  /* A long name broken into two lines at the space nearest its middle, or null. */
  function twoLines(name) {
    var words = String(name).split(' ');
    if (words.length < 2 || name.length < 12) return null;
    var best = null;
    for (var i = 1; i < words.length; i++) {
      var first = words.slice(0, i).join(' '), second = words.slice(i).join(' ');
      var longest = Math.max(first.length, second.length);
      if (!best || longest < best.longest) best = { lines: [first, second], longest: longest };
    }
    return best.lines;
  }

  function labelOptions(station, lines, r, extra) {
    var large = station.interchange;
    var w = Math.max.apply(null, lines.map(function (line) { return textWidth(line, large); }));
    var h = LABEL_HEIGHT * lines.length, d = r * 0.75, u = station.u, v = station.v;
    return [
      { x: u + r, y: v - h / 2, anchor: 'start', tx: u + r, ty: v, pref: 0 },
      { x: u - r - w, y: v - h / 2, anchor: 'end', tx: u - r, ty: v, pref: 1 },
      { x: u + d, y: v + d, anchor: 'start', tx: u + d, ty: v + d + h / 2, pref: 2 },
      { x: u + d, y: v - d - h, anchor: 'start', tx: u + d, ty: v - d - h / 2, pref: 2 },
      { x: u - d - w, y: v + d, anchor: 'end', tx: u - d, ty: v + d + h / 2, pref: 3 },
      { x: u - d - w, y: v - d - h, anchor: 'end', tx: u - d, ty: v - d - h / 2, pref: 3 },
      { x: u - w / 2, y: v + r, anchor: 'middle', tx: u, ty: v + r + h / 2, pref: 4 },
      { x: u - w / 2, y: v - r - h, anchor: 'middle', tx: u, ty: v - r - h / 2, pref: 4 },
      { x: u + r, y: v - h, anchor: 'start', tx: u + r, ty: v - h / 2, pref: 5 },
      { x: u + r, y: v, anchor: 'start', tx: u + r, ty: v + h / 2, pref: 5 },
      { x: u - r - w, y: v - h, anchor: 'end', tx: u - r, ty: v - h / 2, pref: 6 },
      { x: u - r - w, y: v, anchor: 'end', tx: u - r, ty: v + h / 2, pref: 6 }
    ].map(function (option) {
      option.w = w;
      option.h = h;
      option.lines = lines;
      option.pref += extra;
      option.side = option.anchor === 'start' ? 'right' : option.anchor === 'end' ? 'left' : option.ty < v ? 'above' : 'below';
      return option;
    });
  }

  /* Places every label; returns how much clashing was left (0 when every label is clear). */
  function placeLabels(stationList, segments) {
    var placed = [], clashes = 0;
    var order = stationList.slice().sort(function (a, b) {
      return (b.interchange ? 1 : 0) - (a.interchange ? 1 : 0) || b.degree - a.degree || (a.id < b.id ? -1 : 1);
    });
    order.forEach(function (station) {
      var r = station.r + 9;
      var options = labelOptions(station, [station.name], r, 0);
      var split = twoLines(station.name);
      if (split) options = options.concat(labelOptions(station, split, r, 3));
      if (station.labelSide) {
        var sided = options.filter(function (option) { return option.side === station.labelSide; });
        if (sided.length) options = sided;
      }
      var best = null, bestCost = Infinity, bestClash = 0;
      options.forEach(function (option) {
        var box = { x: option.x, y: option.y, w: option.w, h: option.h };
        var clash = 0;
        placed.forEach(function (other) { if (boxesOverlap(box, other)) clash += 100; });
        stationList.forEach(function (other) { if (other !== station && pointInBox(other, box, other.r + 2)) clash += 80; });
        segments.forEach(function (segment) { if (hitsBox(segment[0], segment[1], box)) clash += 12; });
        if (option.pref + clash < bestCost) { best = option; bestCost = option.pref + clash; bestClash = clash; }
      });
      station.label = {
        x: best.tx, y: best.ty, anchor: best.anchor, lines: best.lines, lineHeight: LABEL_HEIGHT,
        box: { x: best.x, y: best.y, w: best.w, h: best.h }
      };
      placed.push(station.label.box);
      clashes += bestClash + (best.lines.length > 1 ? 2 : 0);
    });
    return clashes;
  }

  // ---- Build -----------------------------------------------------------------------------------

  /* Lays out one arrangement completely, with a score: track, labels and size. */
  function arrange(network, arrangement, seed, overrides) {
    var stations = placeStations(network, arrangement[0], arrangement[1], arrangement[2], arrangement[3], seed, overrides);
    var pairs = routeTrack(network, stations);
    var recolour = (overrides && overrides.lines) || {};
    var lines = network.lines.map(function (line) {
      return { id: line.id, name: line.name, short: line.short, color: recolour[line.id] || line.color, suspended: !!line.suspended, edges: [] };
    });
    var widest = {}, degree = {};
    Object.keys(pairs).forEach(function (key) {
      if (key === 'total') return;
      var pair = pairs[key], count = pair.lines.length;
      [pair.a, pair.b].forEach(function (id) {
        widest[id] = Math.max(widest[id] || 1, count);
        degree[id] = (degree[id] || 0) + 1;
      });
      pair.lines.forEach(function (lineIndex, i) {
        var offset = (i - (count - 1) / 2) * (STROKE + GAP);
        lines[lineIndex].edges.push({ a: pair.a, b: pair.b, points: offsetPath(pair.points, offset), blocked: pair.blocked });
      });
    });

    var stationList = Object.keys(stations).map(function (id) { return stations[id]; });
    stationList.forEach(function (station) {
      var bundle = widest[station.id] || 1;
      var base = station.interchange ? 8.5 : 5.5;
      station.r = bundle > 1 ? Math.max(base, (bundle - 1) * (STROKE + GAP) / 2 + STROKE / 2 + 3) : base;
      station.degree = degree[station.id] || 0;
    });

    var transfers = (network.transfers || []).filter(function (t) { return stations[t.a] && stations[t.b]; }).map(function (t) {
      return { a: t.a, b: t.b, walk: t.walk, outside: t.outside !== false,
        points: [{ u: stations[t.a].u, v: stations[t.a].v }, { u: stations[t.b].u, v: stations[t.b].v }] };
    });

    var segments = [];
    lines.forEach(function (line) {
      line.edges.forEach(function (edge) {
        for (var i = 0; i < edge.points.length - 1; i++) segments.push([edge.points[i], edge.points[i + 1]]);
      });
    });
    transfers.forEach(function (t) { segments.push([t.points[0], t.points[1]]); });
    var clashes = placeLabels(stationList, segments);

    var rows = {}, columns = {};
    stationList.forEach(function (s) { rows[s.v] = true; columns[s.u] = true; });
    var size = Object.keys(rows).length + Object.keys(columns).length;
    return {
      score: pairs.total + clashes + size * COMPACT,
      stations: stations, stationList: stationList, lines: lines, transfers: transfers, segments: segments,
      detail: { arrangement: arrangement, track: Math.round(pairs.total), labels: clashes, rows: Object.keys(rows).length, columns: Object.keys(columns).length }
    };
  }

  /* The network without the stations the operator hid: their stops are spliced out of every service. */
  function withoutHidden(network) {
    var adjust = (network.map && network.map.stations) || {};
    var hidden = {};
    Object.keys(adjust).forEach(function (id) { if (adjust[id].hidden) hidden[id] = true; });
    if (!Object.keys(hidden).length) return network;
    return {
      title: network.title,
      map: network.map,
      stations: network.stations.filter(function (s) { return !hidden[s.id]; }),
      lines: network.lines.map(function (line) {
        var copy = {};
        Object.keys(line).forEach(function (key) { copy[key] = line[key]; });
        copy.services = line.services.map(function (service) {
          var s = {};
          Object.keys(service).forEach(function (key) { s[key] = service[key]; });
          s.stops = service.stops.filter(function (stop) { return !hidden[stop.station]; });
          return s;
        });
        return copy;
      }),
      transfers: (network.transfers || []).filter(function (t) { return !hidden[t.a] && !hidden[t.b]; })
    };
  }

  /*
   * options: an arrangement (legacy), or { only: arrangement, seed: { arrangement, stations: { id: { col, row } } } }.
   * With a seed, stations keep their cells and only that seed's arrangement is laid out.
   */
  function build(network, options) {
    var only = null, seed = null;
    if (Array.isArray(options)) only = options;
    else if (options) { only = options.only || null; seed = options.seed || null; }
    network = withoutHidden(network);
    var overrides = network.map || null;
    if (seed && seed.stations && Object.keys(seed.stations).length) {
      only = only || (seed.arrangement && seed.arrangement.length === 4 ? seed.arrangement : ARRANGEMENTS[0]);
    } else {
      seed = null;
    }
    var best = null;
    (only ? [only] : ARRANGEMENTS).forEach(function (arrangement) {
      var result = arrange(network, arrangement, seed, overrides);
      if (!best || result.score < best.score - 1e-6) best = result;
    });
    var stations = best.stations, lines = best.lines, transfers = best.transfers;
    var cells = {};
    best.stationList.forEach(function (s) { cells[s.id] = { col: s.col, row: s.row }; });

    var bounds = null;
    function extend(u, v) {
      if (!bounds) bounds = { minU: u, maxU: u, minV: v, maxV: v };
      bounds.minU = Math.min(bounds.minU, u); bounds.maxU = Math.max(bounds.maxU, u);
      bounds.minV = Math.min(bounds.minV, v); bounds.maxV = Math.max(bounds.maxV, v);
    }
    best.stationList.forEach(function (s) {
      extend(s.u, s.v);
      extend(s.label.box.x, s.label.box.y);
      extend(s.label.box.x + s.label.box.w, s.label.box.y + s.label.box.h);
    });
    best.segments.forEach(function (segment) { extend(segment[0].u, segment[0].v); extend(segment[1].u, segment[1].v); });

    return {
      cell: CELL,
      stroke: STROKE,
      stations: stations,
      lines: lines,
      transfers: transfers,
      bounds: bounds,
      detail: best.detail,
      /* Where every station landed, to lay the next network out from: a station stays put. */
      seed: { arrangement: best.detail.arrangement, stations: cells },
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

  /* How each arrangement scores, for tuning: lower is better. */
  function arrangements(network) {
    return ARRANGEMENTS.map(function (arrangement) {
      var result = arrange(network, arrangement);
      result.detail.score = Math.round(result.score);
      return result.detail;
    });
  }

  // ---- Helpers for callers -----------------------------------------------------------------------

  function pieces(points) {
    var lengths = [], total = 0;
    for (var i = 0; i < points.length - 1; i++) {
      var l = Math.hypot(points[i + 1].u - points[i].u, points[i + 1].v - points[i].v);
      lengths.push(l);
      total += l;
    }
    return { lengths: lengths, total: total };
  }

  function locate(points, fraction) {
    var measured = pieces(points);
    var target = Math.max(0, Math.min(1, fraction)) * measured.total;
    for (var k = 0; k < measured.lengths.length; k++) {
      if (target <= measured.lengths[k] || k === measured.lengths.length - 1) {
        return { index: k, t: measured.lengths[k] ? Math.min(1, target / measured.lengths[k]) : 0 };
      }
      target -= measured.lengths[k];
    }
    return { index: 0, t: 0 };
  }

  /* A point a fraction (0..1) along a polyline. */
  function along(points, fraction) {
    if (!points || !points.length) return null;
    if (points.length === 1) return points[0];
    var at = locate(points, fraction);
    var a = points[at.index], b = points[at.index + 1];
    return { u: a.u + (b.u - a.u) * at.t, v: a.v + (b.v - a.v) * at.t };
  }

  var api = {
    build: build, along: along, arrangements: arrangements, CELL: CELL, STROKE: STROKE,
    geometry: { distanceToPath: distanceToPath, crosses: crosses, overlap: overlap, hitsBox: hitsBox, boxesOverlap: boxesOverlap }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.ATCSLayout = api;
})(typeof window !== 'undefined' ? window : globalThis);
