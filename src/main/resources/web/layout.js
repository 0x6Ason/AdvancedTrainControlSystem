/* Schematic layout for a transit-map style drawing. Works in the browser and in Node.
 *
 * The map is drawn the way the Hong Kong MTR and New York subway maps are: the shape of the
 * network follows the real world (what is north stays north, a line heading south-west is drawn
 * heading south-west), but lines run straight at 0, 45 or 90 degrees, stations along a line are
 * evenly spaced, the dense middle is spread out and long empty stretches are shortened.
 *
 * 1. The track is drawn once. An express that leaves out stops runs on the local line, as on the
 *    New York map, and the stations every train of such a line calls at are marked.
 * 2. Key stations (interchanges, line ends, branches, transfers, pins, sharp turns in the world)
 *    sit on a square grid: first where a least-squares fit of every run's usual length and real
 *    direction puts them, then moved step by step while the drawing improves (multicriteria hill
 *    climbing after Stott and Rodgers; the criteria are Noellenburg and Wolff's: octilinear runs,
 *    few bends, lines straight through stations, relative positions and the order of lines around
 *    a station kept, no crossings, no line through a station it does not serve).
 * 3. The ordinary stations between two key stations are spread evenly along a straight or
 *    once-bent run between them.
 * 4. Lines sharing a stretch run side by side; walking transfers are straight connectors.
 * 5. Labels go where they cover no station, label, line or connector, preferring the right side.
 * 6. A saved layout (the seed) keeps the key stations where they were. Hand adjustments from
 *    network.yml (map:) pin a station to a grid cell, hide it, choose its label side or recolour
 *    a line.
 */
(function (root) {
  'use strict';

  var CELL = 100;            // a typical station spacing, in map units
  var STROKE = 9;            // line width
  var GAP = 4;               // space between lines running side by side
  var CLEAR = 22;            // how close a line may pass a station it does not serve
  var BESIDE = STROKE + 3;   // closer than this, two lines read as one
  var KINK = 30;             // size of the bend that takes a straight line around a station
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

  /* Whether two segments cross or touch, other than at a shared end point. */
  function meets(a, b, c, d) {
    if (crosses(a, b, c, d)) return true;
    var near = 2;
    var shared = function (p, q) { return Math.abs(p.u - q.u) < near && Math.abs(p.v - q.v) < near; };
    if (shared(a, c) || shared(a, d) || shared(b, c) || shared(b, d)) return false;
    return distanceToSegment(a, c, d) < near || distanceToSegment(b, c, d) < near
      || distanceToSegment(c, a, b) < near || distanceToSegment(d, a, b) < near;
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

  // ---- 1. The track, drawn once ----------------------------------------------------------------

  function median(values) {
    if (!values.length) return 0;
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var middle = Math.floor(sorted.length / 2);
    return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
  }

  function pairKey(a, b) { return a < b ? a + '|' + b : b + '|' + a; }

  /* A service's stops in order, the first one again at the end when it runs in a circle. */
  function sequence(service) {
    var ids = service.stops.map(function (stop) { return stop.station; });
    if (service.circular && ids.length > 2) ids.push(ids[0]);
    return ids;
  }

  /*
   * The stations another service of the same line calls at between a and b, as the chain
   * a ... b, when there is one; the shortest such chain. The chain has to run roughly the same
   * way as the pair: the other way round a loop line is not an express route.
   */
  function coveringChain(sequences, self, a, b, stations) {
    var best = null;
    var direct = Math.hypot(stations[b].x - stations[a].x, stations[b].z - stations[a].z);
    sequences.forEach(function (ids, s) {
      if (s === self) return;
      for (var i = 0; i < ids.length; i++) {
        if (ids[i] !== a && ids[i] !== b) continue;
        var target = ids[i] === a ? b : a;
        for (var j = i + 1; j < ids.length && ids[j] !== ids[i]; j++) {
          if (!stations[ids[j]]) break;
          if (ids[j] !== target) continue;
          if (j - i >= 2) {
            var chain = ids.slice(i, j + 1), along = 0;
            for (var k = 0; k + 1 < chain.length; k++) {
              along += Math.hypot(stations[chain[k + 1]].x - stations[chain[k]].x, stations[chain[k + 1]].z - stations[chain[k]].z);
            }
            if (ids[i] !== a) chain.reverse();
            if (along <= direct * 1.4 + 1 && (!best || chain.length < best.length)) best = chain;
          }
          break;
        }
      }
    });
    return best;
  }

  /*
   * The stretches of track the lines are drawn along: every pair of consecutive stops, except
   * where another service of the same line calls at stations in between. Such an express runs on
   * the local track, as on the New York map, rather than along a second line of its own. Also
   * returns the chain each express pair runs along, and the stations each line's trains pass.
   */
  function corridors(network, stations) {
    var stretches = {}, via = {}, passed = {};
    network.lines.forEach(function (line, lineIndex) {
      var sequences = line.services.map(sequence);
      var skipped = passed[lineIndex] = {};
      var shortcuts = via[lineIndex] = {};
      line.services.forEach(function (service, s) {
        service.stops.forEach(function (stop) { if (stop.pass) skipped[stop.station] = true; });
        var ids = sequences[s];
        for (var i = 0; i + 1 < ids.length; i++) {
          var a = ids[i], b = ids[i + 1];
          if (a === b || !stations[a] || !stations[b]) continue;
          var key = pairKey(a, b);
          if (shortcuts[key]) continue;
          var chain = coveringChain(sequences, s, a, b, stations);
          if (chain) {
            shortcuts[key] = chain;
            for (var k = 1; k < chain.length - 1; k++) skipped[chain[k]] = true;
            continue;
          }
          var stretch = stretches[key] = stretches[key] || { key: key, a: a < b ? a : b, b: a < b ? b : a, lines: [] };
          if (stretch.lines.indexOf(lineIndex) < 0) stretch.lines.push(lineIndex);
        }
      });
    });
    Object.keys(stretches).forEach(function (key) { stretches[key].lines.sort(function (x, y) { return x - y; }); });
    return { stretches: stretches, via: via, passed: passed };
  }

  // ---- 2. Key stations and the runs between them ---------------------------------------------

  /*
   * Key stations are interchanges, line ends, branch points, stations a transfer or a pin is
   * about, and stations where a line turns sharply in the world. Between two key stations a run
   * of ordinary stations is drawn as one straight (or once-bent) stretch with even spacing, the
   * way a designer draws a subway map.
   */
  function skeleton(stations, stretches, transfers, fixed) {
    var around = {};
    Object.keys(stations).forEach(function (id) { around[id] = []; });
    Object.keys(stretches).forEach(function (key) {
      var s = stretches[key];
      around[s.a].push({ id: s.b, stretch: s });
      around[s.b].push({ id: s.a, stretch: s });
    });
    var isKey = {};
    Object.keys(stations).forEach(function (id) {
      var list = around[id];
      isKey[id] = list.length !== 2 || !!fixed[id] || stations[id].interchange
        || list[0].stretch.lines.join(',') !== list[1].stretch.lines.join(',');
    });
    transfers.forEach(function (t) { if (isKey[t.a] !== undefined) isKey[t.a] = true; if (isKey[t.b] !== undefined) isKey[t.b] = true; });

    function walk(start, first) {
      var ids = [start], parts = [], previous = start, step = first;
      for (var guard = 0; guard < 100000; guard++) {
        ids.push(step.id);
        parts.push(step.stretch);
        if (isKey[step.id]) break;
        var pair = around[step.id];
        var next = pair[0].stretch === step.stretch ? pair[1] : pair[0];
        previous = step.id;
        step = next;
      }
      return { ids: ids, stretches: parts };
    }

    function collect() {
      var chains = [], used = {};
      Object.keys(stations).sort().forEach(function (id) {
        if (!isKey[id]) return;
        around[id].forEach(function (step) {
          if (used[step.stretch.key]) return;
          var chain = walk(id, step);
          chain.stretches.forEach(function (s) { used[s.key] = true; });
          chains.push(chain);
        });
      });
      var loose = Object.keys(stretches).filter(function (key) { return !used[key]; }).sort();
      if (loose.length) {
        isKey[stretches[loose[0]].a] = true;
        return collect();
      }
      return chains;
    }

    // Split runs where the line bends a lot in the world, so a U-shaped line stays a U.
    var chains = collect();
    for (var round = 0; round < 20; round++) {
      var split = false;
      chains.forEach(function (chain) {
        if (chain.ids.length < 3) return;
        var a = stations[chain.ids[0]], b = stations[chain.ids[chain.ids.length - 1]];
        var cu = b.x - a.x, cv = b.z - a.z, chord = Math.hypot(cu, cv), far = -1, farthest = 0;
        for (var i = 1; i < chain.ids.length - 1; i++) {
          var p = stations[chain.ids[i]];
          var d = chord > 1e-6 ? Math.abs((p.x - a.x) * cv - (p.z - a.z) * cu) / chord : Math.hypot(p.x - a.x, p.z - a.z);
          if (d > farthest) { farthest = d; far = i; }
        }
        if (far > 0 && (chord < 1e-6 || farthest > 0.25 * chord)) {
          isKey[chain.ids[far]] = true;
          split = true;
        }
      });
      if (!split) break;
      chains = collect();
    }
    var keys = Object.keys(stations).filter(function (id) { return isKey[id]; }).sort();
    return { keys: keys, chains: chains, isKey: isKey };
  }

  // ---- 3. Placing the key stations ------------------------------------------------------------

  var STEP = 40;          // the grid key stations sit on, in map units (square, so diagonals are 45 degrees)
  var SPACING = 3;        // the usual distance between neighbouring stations, in grid steps
  var TIGHTEST = 2;       // the closest neighbouring stations may come, in grid steps
  var LONGEST = 3.5;      // a long stretch with no stations is drawn at most this many times the usual
  var SHORTEN = 0.6;      // and longer stretches grow with this power of their real length, so they come out shorter
  var VERSION = 1;        // saved layouts from another version are laid out afresh

  var W = {
    length: 10, short: 200, direction: 8, sector: 60, reversed: 200, backwards: 30, near: 40,
    turn45: 2, turn90: 10, turn135: 60, onBend: 25, jog: 12,
    straightThrough: { 180: 0, 135: 2, 90: 12, 45: 90, 0: 300 },
    sameWay: 300, order: 40, cross: 150, overlap: 200, occluded: 500, crowded: 150, label: 35, anchor: 1
  };

  function angleBetween(a, b) {
    var la = Math.hypot(a.u, a.v), lb = Math.hypot(b.u, b.v);
    if (!la || !lb) return 0;
    var c = (a.u * b.u + a.v * b.v) / (la * lb);
    return Math.acos(Math.max(-1, Math.min(1, c))) * 180 / Math.PI;
  }

  function polylineLength(points) {
    var total = 0;
    for (var i = 0; i < points.length - 1; i++) total += Math.hypot(points[i + 1].u - points[i].u, points[i + 1].v - points[i].v);
    return total;
  }

  /* Points a set of fractions along a polyline. */
  function spaced(points, count) {
    var total = polylineLength(points), result = [];
    for (var k = 1; k < count; k++) {
      var target = total * k / count, i = 0, walked = 0;
      for (; i < points.length - 2; i++) {
        var l = Math.hypot(points[i + 1].u - points[i].u, points[i + 1].v - points[i].v);
        if (walked + l >= target) break;
        walked += l;
      }
      var a = points[i], b = points[i + 1], len = Math.hypot(b.u - a.u, b.v - a.v) || 1, t = Math.min(1, (target - walked) / len);
      result.push({ u: a.u + (b.u - a.u) * t, v: a.v + (b.v - a.v) * t, at: target });
    }
    return result;
  }

  function measure(chains, stations) {
    var geo = [];
    chains.forEach(function (chain) {
      chain.stretches.forEach(function (s) {
        var a = stations[s.a], b = stations[s.b];
        s.geo = Math.hypot(b.x - a.x, b.z - a.z);
        if (s.geo > 0) geo.push(s.geo);
      });
    });
    var usual = median(geo) || 1;
    chains.forEach(function (chain) {
      chain.target = 0;
      chain.stretches.forEach(function (s) {
        chain.target += SPACING * Math.min(LONGEST, Math.max(TIGHTEST / SPACING, Math.pow(s.geo / usual, SHORTEN)));
      });
      chain.least = chain.stretches.length * TIGHTEST;
      var a = stations[chain.ids[0]], b = stations[chain.ids[chain.ids.length - 1]];
      var gu = b.x - a.x, gv = b.z - a.z, gl = Math.hypot(gu, gv);
      chain.chord = gl;
      chain.dir = gl > 1e-6 ? { u: gu / gl, v: gv / gl } : null;
      // The way a run leaves its key stations, for the order of lines around them: the whole run's
      // direction (runs are split where they bend), not its first stretch, which may be a few blocks.
      var a1 = stations[chain.ids[1]], b1 = stations[chain.ids[chain.ids.length - 2]];
      chain.leaveStart = chain.dir ? { u: gu, v: gv } : { u: a1.x - a.x, v: a1.z - a.z };
      chain.leaveEnd = chain.dir ? { u: -gu, v: -gv } : { u: b1.x - b.x, v: b1.z - b.z };
    });
    return usual;
  }

  /*
   * Where key stations would sit if every run had its usual length and its real direction:
   * a least-squares fit (Gauss-Seidel), anchored lightly to the real map so that parts of the
   * network not joined by track keep their places. In grid steps.
   */
  function relax(keys, chains, stations, fixed) {
    var ratios = chains.filter(function (c) { return c.chord > 1e-6; }).map(function (c) { return c.target / c.chord; });
    var scale = median(ratios) || (SPACING / 100);
    var cx = 0, cz = 0;
    keys.forEach(function (id) { cx += stations[id].x; cz += stations[id].z; });
    cx /= keys.length || 1; cz /= keys.length || 1;
    var anchor = {};
    keys.forEach(function (id) { anchor[id] = { u: (stations[id].x - cx) * scale, v: (stations[id].z - cz) * scale }; });
    var pinned = keys.filter(function (id) { return fixed[id]; });
    if (pinned.length) {
      var tu = 0, tv = 0;
      pinned.forEach(function (id) { tu += fixed[id].col - anchor[id].u; tv += fixed[id].row - anchor[id].v; });
      tu /= pinned.length; tv /= pinned.length;
      keys.forEach(function (id) { anchor[id] = { u: anchor[id].u + tu, v: anchor[id].v + tv }; });
    }
    var at = {}, incident = {};
    keys.forEach(function (id) {
      at[id] = fixed[id] ? { u: fixed[id].col, v: fixed[id].row } : { u: anchor[id].u, v: anchor[id].v };
      incident[id] = [];
    });
    chains.forEach(function (chain) {
      if (!chain.dir) return;
      incident[chain.ids[0]].push({ chain: chain, start: true });
      incident[chain.ids[chain.ids.length - 1]].push({ chain: chain, start: false });
    });
    var LAMBDA = 0.02;
    for (var iteration = 0; iteration < 400; iteration++) {
      keys.forEach(function (id) {
        if (fixed[id]) return;
        var su = LAMBDA * anchor[id].u, sv = LAMBDA * anchor[id].v, w = LAMBDA;
        incident[id].forEach(function (link) {
          var c = link.chain, du = c.dir.u * c.target, dv = c.dir.v * c.target;
          var other = link.start ? c.ids[c.ids.length - 1] : c.ids[0];
          if (link.start) { su += at[other].u - du; sv += at[other].v - dv; } else { su += at[other].u + du; sv += at[other].v + dv; }
          w += 1;
        });
        at[id] = { u: su / w, v: sv / w };
      });
    }
    if (!pinned.length) {
      var minU = Infinity, minV = Infinity;
      keys.forEach(function (id) { minU = Math.min(minU, at[id].u); minV = Math.min(minV, at[id].v); });
      keys.forEach(function (id) { at[id] = { u: at[id].u - minU, v: at[id].v - minV }; });
    }
    return at;
  }

  /*
   * Spreads the real map out without tangling it (PrEd, after Bertault; Hong, Merrick and do
   * Nascimento drew metro maps this way): every stretch pulls towards its usual length in its real
   * direction, stations push apart and off stretches they are not on, and each station's step is
   * capped below a third of its distance to any stretch it is not on, and each stretch's ends below
   * a third of the distance to any station beside it. No station can then pass through a stretch,
   * so a drawing that starts from the real map gains no crossing the real network does not have.
   * Returns positions for every station, in grid steps.
   */
  function untangle(stations, stretches, transfers, usual) {
    var ids = Object.keys(stations).sort(), index = {};
    ids.forEach(function (id, i) { index[id] = i; });
    var n = ids.length, scale = SPACING / (usual || 1);
    var cx = 0, cz = 0;
    ids.forEach(function (id) { cx += stations[id].x; cz += stations[id].z; });
    cx /= n || 1; cz /= n || 1;
    var pu = ids.map(function (id) { return (stations[id].x - cx) * scale; });
    var pv = ids.map(function (id) { return (stations[id].z - cz) * scale; });
    var edges = [];
    Object.keys(stretches).forEach(function (key) {
      var s = stretches[key], a = stations[s.a], b = stations[s.b];
      var gu = b.x - a.x, gv = b.z - a.z, gl = Math.hypot(gu, gv) || 1;
      var target = SPACING * Math.min(LONGEST, Math.max(TIGHTEST / SPACING, Math.pow(gl / (usual || 1), SHORTEN)));
      edges.push({ i: index[s.a], j: index[s.b], target: target, gu: gu / gl, gv: gv / gl, k: 1 });
    });
    transfers.forEach(function (t) {
      if (index[t.a] === undefined || index[t.b] === undefined || t.a === t.b) return;
      var a = stations[t.a], b = stations[t.b], gu = b.x - a.x, gv = b.z - a.z, gl = Math.hypot(gu, gv) || 1;
      edges.push({ i: index[t.a], j: index[t.b], target: TIGHTEST + 0.5, gu: gu / gl, gv: gv / gl, k: 0.5 });
    });
    var neighbours = ids.map(function () { return {}; });
    edges.forEach(function (e) { neighbours[e.i][e.j] = true; neighbours[e.j][e.i] = true; });
    var ROOM = SPACING, OFF = SPACING * 0.6, ROUNDS = n > 150 ? 300 : 600;

    function segmentDistance(x, y, e) {
      var au = pu[e.i], av = pv[e.i], du = pu[e.j] - au, dv = pv[e.j] - av, l2 = du * du + dv * dv;
      var f = l2 ? Math.max(0, Math.min(1, ((x - au) * du + (y - av) * dv) / l2)) : 0;
      return { d: Math.hypot(x - (au + du * f), y - (av + dv * f)), f: f, cu: au + du * f, cv: av + dv * f };
    }

    for (var round = 0; round < ROUNDS; round++) {
      var rate = 0.3 * (1 - round / ROUNDS) + 0.03;
      var fu = new Array(n).fill(0), fv = new Array(n).fill(0);
      // Each stretch turns towards its real direction, resists being squeezed below its usual
      // length and only gently resists being stretched: the middle may grow, the shape may not bend.
      edges.forEach(function (e) {
        var du = pu[e.j] - pu[e.i], dv = pv[e.j] - pv[e.i];
        var along = du * e.gu + dv * e.gv, perpU = du - along * e.gu, perpV = dv - along * e.gv;
        var pull = along < e.target ? (e.target - along) * 0.5 : (e.target - along) * 0.12;
        var gu = (-perpU * 0.5 + pull * e.gu) * e.k, gv = (-perpV * 0.5 + pull * e.gv) * e.k;
        fu[e.j] += gu * 0.5; fv[e.j] += gv * 0.5;
        fu[e.i] -= gu * 0.5; fv[e.i] -= gv * 0.5;
      });
      for (var i = 0; i < n; i++) {
        for (var j = i + 1; j < n; j++) {
          var du = pu[j] - pu[i], dv = pv[j] - pv[i], d = Math.hypot(du, dv);
          if (d >= ROOM) continue;
          if (d < 1e-6) { du = 0.01 * (j - i); dv = 0.013; d = Math.hypot(du, dv); }
          var push = (ROOM - d) / d * 0.5;
          fu[i] -= du * push; fv[i] -= dv * push; fu[j] += du * push; fv[j] += dv * push;
        }
      }
      var bound = new Array(n).fill(0.6);
      edges.forEach(function (e) {
        for (var v = 0; v < n; v++) {
          if (v === e.i || v === e.j) continue;
          var s = segmentDistance(pu[v], pv[v], e);
          var cap = s.d / 3;
          if (cap < bound[v]) bound[v] = cap;
          if (cap < bound[e.i]) bound[e.i] = cap;
          if (cap < bound[e.j]) bound[e.j] = cap;
          if (s.d < OFF && s.f > 0 && s.f < 1 && !neighbours[v][e.i] && !neighbours[v][e.j]) {
            var ou = pu[v] - s.cu, ov = pv[v] - s.cv, od = s.d || 1e-6;
            var strength = (OFF - s.d) / od * 0.5;
            fu[v] += ou * strength; fv[v] += ov * strength;
            fu[e.i] -= ou * strength * (1 - s.f); fv[e.i] -= ov * strength * (1 - s.f);
            fu[e.j] -= ou * strength * s.f; fv[e.j] -= ov * strength * s.f;
          }
        }
      });
      for (var m = 0; m < n; m++) {
        var su = fu[m] * rate, sv = fv[m] * rate, size = Math.hypot(su, sv);
        if (size > bound[m]) { su *= bound[m] / size; sv *= bound[m] / size; }
        pu[m] += su; pv[m] += sv;
      }
    }
    var at = {};
    ids.forEach(function (id, i) { at[id] = { u: pu[i], v: pv[i] }; });
    return at;
  }

  /*
   * Where the climb starts from: the untangled map, moved to the grid's corner; or, when pinned or
   * saved stations fix part of the drawing, the least-squares fit, which works around them.
   */
  function startFrom(keys, fit, untangled) {
    if (!untangled) return fit;
    var minU = Infinity, minV = Infinity, moved = {};
    keys.forEach(function (id) { minU = Math.min(minU, untangled[id].u); minV = Math.min(minV, untangled[id].v); });
    keys.forEach(function (id) { moved[id] = { u: untangled[id].u - minU, v: untangled[id].v - minV }; });
    return moved;
  }

  /* Grid cells for the key stations: the nearest free one to where the fit put each. */
  function snap(keys, at, fixed, degree) {
    var cell = {}, taken = {};
    var order = keys.slice().sort(function (a, b) { return (fixed[b] ? 1 : 0) - (fixed[a] ? 1 : 0) || degree[b] - degree[a] || (a < b ? -1 : 1); });
    order.forEach(function (id) {
      var c = Math.round(at[id].u), r = Math.round(at[id].v);
      if (fixed[id]) { c = fixed[id].col; r = fixed[id].row; }
      for (var radius = 0; taken[c + ':' + r] && radius < 50; radius++) {
        var best = null, bestD = Infinity;
        for (var dc = -radius - 1; dc <= radius + 1; dc++) {
          for (var dr = -radius - 1; dr <= radius + 1; dr++) {
            var k = (c + dc) + ':' + (r + dr);
            if (taken[k]) continue;
            var d = Math.hypot(c + dc - at[id].u, r + dr - at[id].v);
            if (d < bestD) { bestD = d; best = { c: c + dc, r: r + dr }; }
          }
        }
        if (best) { c = best.c; r = best.r; break; }
      }
      cell[id] = { c: c, r: r };
      taken[c + ':' + r] = id;
    });
    return cell;
  }

  /*
   * Moves key stations one grid step at a time while the drawing gets better (Stott and Rodgers'
   * multicriteria hill climbing): runs near their usual length and real direction, lines straight
   * through stations, every run straight or bent once, no run through another station, no
   * crossings, and the order of the lines around each station kept.
   */
  function optimise(keys, chains, stations, fixed, cell, fit, saved) {
    var byKey = {};
    keys.forEach(function (id) { byKey[id] = []; });
    chains.forEach(function (chain, index) {
      chain.index = index;
      byKey[chain.ids[0]].push(chain);
      var last = chain.ids[chain.ids.length - 1];
      if (last !== chain.ids[0]) byKey[last].push(chain);
    });
    var linesOf = function (chain, atStart) {
      if (chain.walk) return [];
      return (atStart ? chain.stretches[0] : chain.stretches[chain.stretches.length - 1]).lines;
    };

    function point(id) { return { u: cell[id].c * STEP, v: cell[id].r * STEP }; }

    function ownCost(chain, points, inside) {
      var length = polylineLength(points) / STEP, cost = W.length * Math.pow(Math.log(Math.max(length, 0.1) / chain.target), 2);
      if (length < chain.least - 0.01) cost += W.short * (chain.least - length);
      var first = points[0], last = points[points.length - 1];
      if (chain.walk) {
        return chain.dir ? cost + W.direction * Math.pow(angleBetween({ u: last.u - first.u, v: last.v - first.v }, chain.dir) / 45, 2) : cost;
      }
      if (chain.dir) {
        var deviation = angleBetween({ u: last.u - first.u, v: last.v - first.v }, chain.dir);
        cost += W.direction * Math.pow(deviation / 45, 2);
        if (deviation > 50) cost += W.sector;
        if (deviation > 95) cost += W.reversed;
        for (var s = 0; s < points.length - 1; s++) {
          if (angleBetween({ u: points[s + 1].u - points[s].u, v: points[s + 1].v - points[s].v }, chain.dir) > 91) cost += W.backwards;
        }
      }
      // A bend needs room: a stub shorter than a grid step and a half reads as a hook.
      if (points.length > 2) {
        for (var j = 0; j < points.length - 1; j++) {
          if (Math.hypot(points[j + 1].u - points[j].u, points[j + 1].v - points[j].v) < STEP * 1.6) cost += W.jog;
        }
      }
      for (var k = 1; k < points.length - 1; k++) {
        var turn = angleBetween({ u: points[k].u - points[k - 1].u, v: points[k].v - points[k - 1].v },
          { u: points[k + 1].u - points[k].u, v: points[k + 1].v - points[k].v });
        cost += turn < 50 ? W.turn45 : turn < 100 ? W.turn90 : W.turn135;
        for (var i = 0; i < inside.length; i++) {
          if (Math.hypot(inside[i].u - points[k].u, inside[i].v - points[k].v) < 26) { cost += W.onBend; break; }
        }
      }
      return cost;
    }

    /* How badly a placed run clashes with the stations and runs not being moved. */
    /* Where a station's name could go: either side, above or below, on one line or two. */
    var boxCache = {};
    function nameBoxes(p) {
      var name = stations[p.id] ? stations[p.id].name : p.id;
      var shapesOfName = boxCache[name];
      if (!shapesOfName) {
        shapesOfName = [{ w: textWidth(name, false), h: LABEL_HEIGHT }];
        var split = twoLines(name);
        if (split) shapesOfName.push({ w: Math.max(textWidth(split[0], false), textWidth(split[1], false)), h: LABEL_HEIGHT * 2 });
        boxCache[name] = shapesOfName;
      }
      var r = 14, boxes = [];
      shapesOfName.forEach(function (s) {
        boxes.push({ x: p.u + r, y: p.v - s.h / 2, w: s.w, h: s.h });
        boxes.push({ x: p.u - r - s.w, y: p.v - s.h / 2, w: s.w, h: s.h });
        boxes.push({ x: p.u - s.w / 2, y: p.v - r - s.h, w: s.w, h: s.h });
        boxes.push({ x: p.u - s.w / 2, y: p.v + r, w: s.w, h: s.h });
      });
      return boxes;
    }

    /* The rectangle around a polyline, grown by a margin. */
    function bounds(points, margin) {
      var b = { x0: Infinity, y0: Infinity, x1: -Infinity, y1: -Infinity };
      for (var i = 0; i < points.length; i++) {
        var q = points[i];
        if (q.u < b.x0) b.x0 = q.u; if (q.u > b.x1) b.x1 = q.u;
        if (q.v < b.y0) b.y0 = q.v; if (q.v > b.y1) b.y1 = q.v;
      }
      b.x0 -= margin; b.y0 -= margin; b.x1 += margin; b.y1 += margin;
      return b;
    }

    function apart(a, b) { return a.x1 < b.x0 || b.x1 < a.x0 || a.y1 < b.y0 || b.y1 < a.y0; }

    function outside(q, b) { return q.u < b.x0 || q.u > b.x1 || q.v < b.y0 || q.v > b.y1; }

    /* A run's points with the rectangle around them, for quick rejection. */
    function route(points) { return { points: points, box: bounds(points, 0) }; }

    function boxHit(box, r) {
      if (box.x + box.w < r.box.x0 || box.x > r.box.x1 || box.y + box.h < r.box.y0 || box.y > r.box.y1) return false;
      var points = r.points;
      for (var i = 0; i < points.length - 1; i++) if (hitsBox(points[i], points[i + 1], box)) return true;
      return false;
    }

    /* The name boxes of a station that no run and no other station is in. */
    function freeBoxes(p, routes, stationPoints) {
      var reach = 200, nearRoutes = [], nearStations = [];
      for (var r = 0; r < routes.length; r++) {
        var b = routes[r].box;
        if (p.u > b.x0 - reach && p.u < b.x1 + reach && p.v > b.y0 - reach && p.v < b.y1 + reach) nearRoutes.push(routes[r]);
      }
      for (var s = 0; s < stationPoints.length; s++) {
        var q = stationPoints[s];
        if (q.id !== p.id && Math.abs(q.u - p.u) < reach && Math.abs(q.v - p.v) < reach) nearStations.push(q);
      }
      return nameBoxes(p).filter(function (box) {
        for (var i = 0; i < nearRoutes.length; i++) if (boxHit(box, nearRoutes[i])) return false;
        for (var j = 0; j < nearStations.length; j++) if (pointInBox(nearStations[j], box, 6)) return false;
        return true;
      });
    }

    function against(chain, points, inside, others, stationPoints) {
      var cost = 0, members = {};
      chain.ids.forEach(function (id) { members[id] = true; });
      var mine = route(points);
      var near = bounds(points, Math.max(CLEAR, TIGHTEST * STEP));
      stationPoints.forEach(function (p) {
        if (members[p.id] || outside(p, near)) return;
        if (distanceToPath(p, points) < CLEAR) cost += W.occluded;
        for (var i = 0; i < inside.length; i++) {
          if (Math.hypot(inside[i].u - p.u, inside[i].v - p.v) < TIGHTEST * STEP * 0.9) cost += W.crowded;
        }
      });
      var reachBox = bounds(points, STEP * 1.2);
      others.forEach(function (other) {
        if (other === chain || !other.points) return;
        if (!other.route || other.route.points !== other.points) other.route = route(other.points);
        if (apart(reachBox, other.route.box)) return;
        for (var i = 0; i < points.length - 1; i++) {
          for (var j = 0; j < other.points.length - 1; j++) {
            if (meets(points[i], points[i + 1], other.points[j], other.points[j + 1])) cost += W.cross;
            var alongside = overlap(points[i], points[i + 1], other.points[j], other.points[j + 1], STEP * 1.1);
            if (alongside > 0) {
              cost += alongside / STEP * W.near;
              cost += overlap(points[i], points[i + 1], other.points[j], other.points[j + 1], BESIDE) / STEP * W.overlap;
            }
          }
        }
        other.inside.forEach(function (p) { if (!outside(p, near) && distanceToPath(p, points) < CLEAR) cost += W.occluded; });
      });
      // Every station needs one free side for its name: this run's own stations, and the stations
      // nearby whose last free side this run would take.
      var routes = [mine];
      others.forEach(function (other) { if (other !== chain && other.points && !other.walk) routes.push(other.route || route(other.points)); });
      var reach = bounds(points, 200);
      var all = stationPoints.filter(function (q) { return !outside(q, reach); }).concat(inside);
      inside.forEach(function (p) { if (!freeBoxes(p, routes, all).length) cost += W.label; });
      stationPoints.forEach(function (q) {
        if (members[q.id] || outside(q, reach)) return;
        var free = freeBoxes(q, routes.slice(1), all);
        if (free.length && free.every(function (box) { return boxHit(box, mine); })) cost += W.label;
      });
      return cost;
    }

    function stationPointsExcept(skip) {
      var list = [];
      keys.forEach(function (id) { var p = point(id); p.id = id; list.push(p); });
      chains.forEach(function (chain) {
        if (skip && skip[chain.index]) return;
        (chain.inside || []).forEach(function (p) { list.push(p); });
      });
      return list;
    }

    /* Places a run between its key stations: the shape that costs least against everything else. */
    function place(chain, others, stationPoints) {
      var a = point(chain.ids[0]), b = point(chain.ids[chain.ids.length - 1]);
      var best = null;
      var options = (chain.walk ? [[a, b]] : shapes(a, b)).map(function (points) {
        var inside = spaced(points, chain.stretches.length);
        inside.forEach(function (p, i) { p.id = chain.ids[i + 1]; });
        var own = ownCost(chain, points, inside);
        // A saved layout also keeps each run's shape: the one whose stations are where they were.
        inside.forEach(function (p) {
          var was = saved[p.id];
          if (was && typeof was.col === 'number') own += Math.hypot(p.u / STEP - was.col, p.v / STEP - was.row) * 20;
        });
        return { points: points, inside: inside, own: own };
      });
      options.sort(function (x, y) { return x.own - y.own; });
      for (var o = 0; o < options.length; o++) {
        var option = options[o];
        if (best && option.own >= best.cost - 1e-9) break;
        var cost = option.own + against(chain, option.points, option.inside, others, stationPoints);
        if (!best || cost < best.cost - 1e-9) best = { points: option.points, inside: option.inside, cost: cost, own: option.own };
      }
      chain.points = best.points;
      chain.inside = best.inside;
      chain.own = best.own;
      return best.cost;
    }

    /* The cost of everything at a key station: lines straight through it, runs apart, order kept. */
    function nodeCost(id) {
      var list = byKey[id], cost = 0;
      var leaving = list.map(function (chain) {
        var atStart = chain.ids[0] === id, points = chain.points;
        var from = atStart ? points[0] : points[points.length - 1], to = atStart ? points[1] : points[points.length - 2];
        return { chain: chain, dir: { u: to.u - from.u, v: to.v - from.v }, geo: atStart ? chain.leaveStart : chain.leaveEnd, lines: linesOf(chain, atStart) };
      });
      var straightness = function (angle) {
        return angle > 160 ? 0 : angle > 115 ? W.straightThrough[135] : angle > 70 ? W.straightThrough[90] : angle > 25 ? W.straightThrough[45] : W.straightThrough[0];
      };
      for (var i = 0; i < leaving.length; i++) {
        for (var j = i + 1; j < leaving.length; j++) {
          if (angleBetween(leaving[i].dir, leaving[j].dir) < 10) cost += W.sameWay;
        }
      }
      // Each line should go straight through: at a branch, every arm should line up with another.
      var lineIds = {};
      leaving.forEach(function (l) { l.lines.forEach(function (line) { lineIds[line] = true; }); });
      Object.keys(lineIds).forEach(function (line) {
        var arms = leaving.filter(function (l) { return l.lines.indexOf(Number(line)) >= 0; });
        if (arms.length < 2) return;
        arms.forEach(function (arm) {
          var widest = 0, widestReal = 0;
          arms.forEach(function (other) {
            if (other === arm) return;
            widest = Math.max(widest, angleBetween(arm.dir, other.dir));
            widestReal = Math.max(widestReal, angleBetween(arm.geo, other.geo));
          });
          // A line that doubles back in the world may double back on the map.
          if (widestReal > 100) cost += straightness(widest) / 2;
        });
      });
      if (leaving.length >= 3) {
        var byGeo = leaving.slice().sort(function (x, y) { return Math.atan2(x.geo.v, x.geo.u) - Math.atan2(y.geo.v, y.geo.u); });
        var byDrawn = leaving.slice().sort(function (x, y) { return Math.atan2(x.dir.v, x.dir.u) - Math.atan2(y.dir.v, y.dir.u); });
        var start = byDrawn.indexOf(byGeo[0]), mismatches = 0;
        for (var k = 0; k < byGeo.length; k++) if (byDrawn[(start + k) % byDrawn.length] !== byGeo[k]) mismatches++;
        cost += mismatches * W.order;
      }
      if (fit[id]) cost += W.anchor * (Math.pow(cell[id].c - fit[id].u, 2) + Math.pow(cell[id].r - fit[id].v, 2)) / (SPACING * SPACING);
      return cost;
    }

    /* Everything a move of these key stations can change. */
    function local(moved) {
      var affected = {}, nodes = {};
      moved.forEach(function (id) {
        nodes[id] = true;
        byKey[id].forEach(function (chain) {
          affected[chain.index] = chain;
          nodes[chain.ids[0]] = true;
          nodes[chain.ids[chain.ids.length - 1]] = true;
        });
      });
      var list = Object.keys(affected).map(function (k) { return affected[k]; });
      var others = chains.filter(function (chain) { return !affected[chain.index]; });
      var stationPoints = stationPointsExcept(affected);
      var cost = 0;
      list.forEach(function (chain) { cost += place(chain, others, stationPoints); others.push(chain); });
      Object.keys(nodes).forEach(function (id) { cost += nodeCost(id); });
      // Moved stations against the runs that did not move with them, and against other stations.
      var everyone = stationPointsExcept({});
      moved.forEach(function (id) {
        var p = point(id);
        chains.forEach(function (chain) {
          if (affected[chain.index] || !chain.points) return;
          if (chain.ids.indexOf(id) < 0 && distanceToPath(p, chain.points) < CLEAR) cost += W.occluded;
        });
        everyone.forEach(function (q) {
          if (q.id !== id && Math.hypot(q.u - p.u, q.v - p.v) < TIGHTEST * STEP * 0.9) cost += W.crowded;
        });
        p.id = id;
        var routes = [];
        chains.forEach(function (chain) {
          if (!chain.points || chain.walk) return;
          if (!chain.route || chain.route.points !== chain.points) chain.route = route(chain.points);
          routes.push(chain.route);
        });
        if (!freeBoxes(p, routes, everyone).length) cost += W.label;
      });
      return cost;
    }

    // Place every run once, then climb.
    chains.forEach(function (chain) {
      chain.points = null; chain.inside = [];
    });
    var placedSoFar = [];
    chains.forEach(function (chain) { place(chain, placedSoFar, stationPointsExcept({})); placedSoFar.push(chain); });

    // What moves together: each free key station alone, and everything beyond a run that is the
    // only link to it (a branch or the far end of a line), so that run can grow or shrink
    // without bending anything else.
    var groups = keys.filter(function (id) { return !fixed[id]; }).map(function (id) { return [id]; });
    chains.forEach(function (chain) {
      [[chain.ids[chain.ids.length - 1], chain.ids[0]], [chain.ids[0], chain.ids[chain.ids.length - 1]]].forEach(function (ends) {
        var seen = {}, queue = [ends[0]], blocked = false;
        seen[ends[0]] = true;
        while (queue.length && !blocked) {
          var id = queue.shift();
          byKey[id].forEach(function (other) {
            if (other === chain) return;
            [other.ids[0], other.ids[other.ids.length - 1]].forEach(function (next) {
              if (next === ends[1]) blocked = true;
              if (!seen[next]) { seen[next] = true; queue.push(next); }
            });
          });
        }
        var members = Object.keys(seen);
        if (blocked || members.length < 2 || members.length > keys.length / 2) return;
        if (members.some(function (id) { return fixed[id]; })) return;
        groups.push(members.sort());
      });
    });
    var groupKeys = {};
    groups = groups.filter(function (group) {
      var key = group.join('|');
      if (groupKeys[key]) return false;
      groupKeys[key] = true;
      return true;
    });
    groups.sort(function (a, b) { return a.length - b.length || (a[0] < b[0] ? -1 : 1); });

    var budget = Math.max(3000, 60000 / Math.max(1, chains.length));
    function tryMoves(radius) {
      var improved = false;
      groups.forEach(function (group) {
        if (budget <= 0) return;
        var home = group.map(function (id) { return cell[id]; });
        var mine = {};
        group.forEach(function (id) { mine[cell[id].c + ':' + cell[id].r] = true; });
        var taken = {};
        keys.forEach(function (id) { var k = cell[id].c + ':' + cell[id].r; if (!mine[k]) taken[k] = true; });
        var before = local(group), best = null, bestDelta = -1e-6;
        for (var dc = -radius; dc <= radius; dc++) {
          for (var dr = -radius; dr <= radius; dr++) {
            if (!dc && !dr) continue;
            var free = home.every(function (h) { return !taken[(h.c + dc) + ':' + (h.r + dr)]; });
            if (!free) continue;
            group.forEach(function (id, i) { cell[id] = { c: home[i].c + dc, r: home[i].r + dr }; });
            budget--;
            var delta = local(group) - before;
            if (delta < bestDelta) { bestDelta = delta; best = { dc: dc, dr: dr }; }
          }
        }
        group.forEach(function (id, i) { cell[id] = best ? { c: home[i].c + best.dc, r: home[i].r + best.dr } : home[i]; });
        if (best) improved = true;
        local(group);
      });
      return improved;
    }

    [3, 2, 1, 1].forEach(function (radius) {
      for (var pass = 0; pass < 12; pass++) {
        if (!tryMoves(radius)) break;
      }
    });
    // Final placement of every run against all the others.
    chains.forEach(function (chain) {
      var skip = {};
      skip[chain.index] = true;
      place(chain, chains.filter(function (c) { return c !== chain; }), stationPointsExcept(skip));
    });
    var total = 0;
    chains.forEach(function (chain) { total += chain.own; });
    keys.forEach(function (id) { total += nodeCost(id); });
    return total;
  }

  /*
   * Positions for every station and the drawn shape of every stretch. Key stations come from the
   * grid; the stations between them are spread evenly along the run.
   */
  function geographic(network, seed, overrides) {
    var adjust = (overrides && overrides.stations) || {};
    var stations = {};
    network.stations.forEach(function (s) {
      if (typeof s.x !== 'number' || typeof s.z !== 'number') return;
      var lines = s.lines || [];
      stations[s.id] = {
        id: s.id, name: s.name, x: s.x, z: s.z, lines: lines, interchange: lines.length > 1,
        labelSide: adjust[s.id] && adjust[s.id].label ? adjust[s.id].label : null
      };
    });
    var fixed = {};
    Object.keys(stations).forEach(function (id) {
      var pin = adjust[id];
      if (pin && typeof pin.column === 'number' && typeof pin.row === 'number') fixed[id] = { col: pin.column, row: pin.row, pinned: true };
    });
    var track = corridors(network, stations);
    var transfers = (network.transfers || []).filter(function (t) { return stations[t.a] && stations[t.b]; });
    var seedCells = seed && seed.stations ? seed.stations : {};
    var shape = skeleton(stations, track.stretches, transfers, fixed);
    shape.keys.forEach(function (id) {
      var c = seedCells[id];
      if (!fixed[id] && c && typeof c.col === 'number' && typeof c.row === 'number') fixed[id] = { col: Math.round(c.col), row: Math.round(c.row) };
    });
    var usual = measure(shape.chains, stations);
    // Walking transfers pull their two stations together, in their real direction from each other.
    transfers.forEach(function (t) {
      if (t.a === t.b) return;
      var a = stations[t.a], b = stations[t.b], gu = b.x - a.x, gv = b.z - a.z, gl = Math.hypot(gu, gv);
      shape.chains.push({
        ids: [t.a, t.b], stretches: [], walk: true, target: TIGHTEST + 0.5, least: 1.5, chord: gl,
        dir: gl > 1e-6 ? { u: gu / gl, v: gv / gl } : null, leaveStart: { u: gu, v: gv }, leaveEnd: { u: -gu, v: -gv }
      });
    });
    var fit = relax(shape.keys, shape.chains, stations, fixed);
    var degree = {};
    shape.keys.forEach(function (id) { degree[id] = 0; });
    shape.chains.forEach(function (c) { degree[c.ids[0]]++; degree[c.ids[c.ids.length - 1]]++; });
    var untangled = Object.keys(fixed).length ? null : untangle(stations, track.stretches, transfers, usual);
    var start = startFrom(shape.keys, fit, untangled);
    var cell = snap(shape.keys, start, fixed, degree);
    var total = optimise(shape.keys, shape.chains, stations, fixed, cell, start, seedCells);

    shape.keys.forEach(function (id) {
      stations[id].u = cell[id].c * STEP;
      stations[id].v = cell[id].r * STEP;
      stations[id].key = true;
    });
    var pieces = {};
    shape.chains.forEach(function (chain) {
      if (chain.walk) return;
      chain.inside.forEach(function (p, i) {
        var s = stations[chain.ids[i + 1]];
        s.u = p.u;
        s.v = p.v;
      });
      // Cut the run at its stations: each stretch is the part between two of them.
      var marks = [0].concat(chain.inside.map(function (p) { return p.at; })).concat([polylineLength(chain.points)]);
      for (var k = 0; k < chain.stretches.length; k++) {
        var from = chain.ids[k], to = chain.ids[k + 1];
        pieces[pairKey(from, to)] = { a: from, b: to, lines: chain.stretches[k].lines, points: cut(chain.points, marks[k], marks[k + 1]) };
      }
    });
    Object.keys(stations).forEach(function (id) {
      var s = stations[id];
      if (typeof s.u !== 'number') { s.u = 0; s.v = 0; }
      s.col = s.u / STEP;
      s.row = s.v / STEP;
    });
    return { stations: stations, pieces: pieces, via: track.via, passed: track.passed, total: total, keys: shape.keys.length, chains: shape.chains.length, usual: usual };
  }

  /* The part of a polyline between two distances along it. */
  function cut(points, from, to) {
    var result = [], walked = 0;
    function at(distance) {
      var w = 0;
      for (var i = 0; i < points.length - 1; i++) {
        var l = Math.hypot(points[i + 1].u - points[i].u, points[i + 1].v - points[i].v);
        if (w + l >= distance - 1e-9 || i === points.length - 2) {
          var t = l ? Math.max(0, Math.min(1, (distance - w) / l)) : 0;
          return { u: points[i].u + (points[i + 1].u - points[i].u) * t, v: points[i].v + (points[i + 1].v - points[i].v) * t };
        }
        w += l;
      }
      return points[points.length - 1];
    }
    result.push(at(from));
    for (var i = 1; i < points.length - 1; i++) {
      walked += Math.hypot(points[i].u - points[i - 1].u, points[i].v - points[i - 1].v);
      if (walked > from + 1e-6 && walked < to - 1e-6) result.push({ u: points[i].u, v: points[i].v });
    }
    result.push(at(to));
    return result;
  }

  // ---- Shapes of a run ------------------------------------------------------------------------

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

  /* Lays the network out completely, with a score: track and labels. */
  function arrange(network, seed, overrides) {
    var placed = geographic(network, seed, overrides);
    var stations = placed.stations;
    var recolour = (overrides && overrides.lines) || {};
    var lineIndex = {};
    var lines = network.lines.map(function (line, index) {
      lineIndex[line.id] = index;
      return { id: line.id, name: line.name, short: line.short, color: recolour[line.id] || line.color, suspended: !!line.suspended, edges: [] };
    });
    var widest = {}, degree = {};
    Object.keys(placed.pieces).forEach(function (key) {
      var piece = placed.pieces[key], count = piece.lines.length;
      [piece.a, piece.b].forEach(function (id) {
        widest[id] = Math.max(widest[id] || 1, count);
        degree[id] = (degree[id] || 0) + 1;
      });
      piece.lines.forEach(function (index, i) {
        var offset = (i - (count - 1) / 2) * (STROKE + GAP);
        lines[index].edges.push({ a: piece.a, b: piece.b, points: offsetPath(piece.points, offset) });
      });
    });

    var stationList = Object.keys(stations).map(function (id) { return stations[id]; });
    stationList.forEach(function (station) {
      var bundle = widest[station.id] || 1;
      var base = station.interchange ? 8.5 : 5.5;
      station.r = bundle > 1 ? Math.max(base, (bundle - 1) * (STROKE + GAP) / 2 + STROKE / 2 + 3) : base;
      station.degree = degree[station.id] || 0;
      // On a line whose faster trains leave out stops, the stations every train calls at are marked.
      station.express = !station.interchange && station.lines.some(function (id) {
        var skipped = placed.passed[lineIndex[id]];
        return !!skipped && Object.keys(skipped).length > 0 && !skipped[station.id];
      });
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

    var minU = Infinity, maxU = -Infinity, minV = Infinity, maxV = -Infinity;
    stationList.forEach(function (s) {
      minU = Math.min(minU, s.u); maxU = Math.max(maxU, s.u); minV = Math.min(minV, s.v); maxV = Math.max(maxV, s.v);
    });
    var columns = stationList.length ? Math.round((maxU - minU) / STEP) + 1 : 0;
    var rows = stationList.length ? Math.round((maxV - minV) / STEP) + 1 : 0;
    return {
      score: placed.total + clashes,
      stations: stations, stationList: stationList, lines: lines, transfers: transfers, segments: segments, via: placed.via,
      detail: {
        arrangement: [STEP, STEP, 'geo', VERSION], track: Math.round(placed.total), labels: clashes,
        rows: rows, columns: columns, keys: placed.keys, runs: placed.chains
      }
    };
  }

  /*
   * options: { seed: { arrangement, stations: { id: { col, row } } } }. A seed from this version
   * keeps its key stations in their cells; one from an older layout is ignored.
   */
  function build(network, options) {
    var seed = options && !Array.isArray(options) ? options.seed || null : null;
    if (seed && !(seed.stations && seed.arrangement && seed.arrangement[2] === 'geo' && seed.arrangement[3] === VERSION)) seed = null;
    network = withoutHidden(network);
    var best = arrange(network, seed, network.map || null);
    var stations = best.stations, lines = best.lines, transfers = best.transfers, via = best.via;
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

    function direct(index, a, b) {
      var edges = lines[index].edges;
      for (var e = 0; e < edges.length; e++) {
        if (edges[e].a === a && edges[e].b === b) return edges[e].points;
        if (edges[e].a === b && edges[e].b === a) return edges[e].points.slice().reverse();
      }
      return null;
    }

    return {
      cell: CELL,
      stroke: STROKE,
      stations: stations,
      lines: lines,
      transfers: transfers,
      bounds: bounds,
      detail: best.detail,
      /* Where every station landed, to lay the next network out from: key stations stay put. */
      seed: { arrangement: best.detail.arrangement, stations: cells },
      /* The drawn path of a line between two stations its trains run between, oriented from a to b. */
      path: function (lineId, a, b) {
        for (var i = 0; i < lines.length; i++) {
          if (lines[i].id !== lineId) continue;
          var points = direct(i, a, b);
          if (points) return points;
          var chain = via[i] && via[i][pairKey(a, b)];
          if (!chain) return null;
          if (chain[0] !== a) chain = chain.slice().reverse();
          var joined = [];
          for (var k = 0; k + 1 < chain.length; k++) {
            var piece = direct(i, chain[k], chain[k + 1]);
            if (!piece) return null;
            joined = joined.concat(k ? piece.slice(1) : piece);
          }
          return joined;
        }
        return null;
      }
    };
  }

  /* How the layout scores, for tuning: lower is better. */
  function arrangements(network) {
    var plain = withoutHidden(network);
    var result = arrange(plain, null, plain.map || null);
    result.detail.score = Math.round(result.score);
    return [result.detail];
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
    build: build, along: along, arrangements: arrangements, CELL: CELL, STROKE: STROKE, STEP: STEP, VERSION: VERSION, weights: W,
    geometry: { distanceToPath: distanceToPath, crosses: crosses, overlap: overlap, hitsBox: hitsBox, boxesOverlap: boxesOverlap }
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.ATCSLayout = api;
})(typeof window !== 'undefined' ? window : globalThis);
