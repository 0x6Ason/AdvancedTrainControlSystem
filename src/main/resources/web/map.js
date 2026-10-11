/* Draws the schematic map as SVG: lines, stations, labels, a highlighted trip and live trains
 * that keep moving between updates, with mouse, wheel and touch navigation. */
(function (root) {
  'use strict';

  var NS = 'http://www.w3.org/2000/svg';
  var MAX_FRACTION = 0.985;

  function el(name, attributes, parent) {
    var node = document.createElementNS(NS, name);
    Object.keys(attributes || {}).forEach(function (key) { node.setAttribute(key, attributes[key]); });
    if (parent) parent.appendChild(node);
    return node;
  }

  function pathData(points) {
    return points.map(function (p, i) { return (i ? 'L' : 'M') + p.u.toFixed(1) + ' ' + p.v.toFixed(1); }).join(' ');
  }

  function overlaps(a, b) {
    return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
  }

  function textOn(hex) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#fff';
    var n = parseInt(m[1], 16);
    return (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) > 170 ? '#000' : '#fff';
  }

  function create(svg, layout, options) {
    options = options || {};
    var stroke = layout.stroke;
    var groups = {};
    while (svg.firstChild) svg.removeChild(svg.firstChild);
    ['lines', 'route', 'stations', 'labels', 'trains'].forEach(function (name) {
      groups[name] = el('g', { 'class': name }, svg);
    });

    var lineById = {};
    layout.lines.forEach(function (line) {
      lineById[line.id] = line;
      line.edges.forEach(function (edge) {
        el('path', {
          'class': 'line-path' + (line.suspended ? ' suspended' : ''),
          d: pathData(edge.points),
          stroke: line.color,
          'stroke-width': stroke
        }, groups.lines);
      });
    });

    var stationNodes = {};
    var placedLabels = [];
    var stationList = Object.keys(layout.stations).map(function (id) { return layout.stations[id]; });
    stationList.sort(function (a, b) { return a.v - b.v || a.u - b.u; });
    stationList.forEach(function (station) {
      var group = el('g', { 'class': 'station' + (station.interchange ? ' interchange' : ''), 'data-id': station.id }, groups.stations);
      el('circle', { cx: station.u, cy: station.v, r: station.interchange ? 8.5 : 5.5 }, group);
      el('title', {}, group).textContent = station.name;
      group.addEventListener('click', function () { if (options.onStation) options.onStation(station.id); });
      stationNodes[station.id] = group;

      var width = station.name.length * 7.6 + 10, height = 18;
      var candidates = [
        { x: station.u + 16, y: station.v - height / 2, anchor: 'start', dx: 16, dy: 5 },
        { x: station.u - 16 - width, y: station.v - height / 2, anchor: 'end', dx: -16, dy: 5 },
        { x: station.u - width / 2, y: station.v + 16, anchor: 'middle', dx: 0, dy: 29 },
        { x: station.u - width / 2, y: station.v - 16 - height, anchor: 'middle', dx: 0, dy: -19 }
      ];
      var chosen = candidates[0];
      for (var c = 0; c < candidates.length; c++) {
        var box = { x: candidates[c].x, y: candidates[c].y, w: width, h: height };
        var clash = placedLabels.some(function (other) { return overlaps(box, other); })
          || stationList.some(function (other) {
            return other !== station && other.u > box.x - 12 && other.u < box.x + box.w + 12 && other.v > box.y - 12 && other.v < box.y + box.h + 12;
          });
        if (!clash) { chosen = candidates[c]; break; }
      }
      placedLabels.push({ x: chosen.x, y: chosen.y, w: width, h: height });
      el('text', {
        'class': 'label' + (station.interchange ? ' interchange' : ''),
        x: station.u + chosen.dx, y: station.v + chosen.dy,
        'text-anchor': chosen.anchor
      }, groups.labels).textContent = station.name;
    });

    // View box and navigation
    var bounds = layout.bounds || { minU: 0, maxU: 100, minV: 0, maxV: 100 };
    var margin = layout.cell * 1.5;
    var home = { x: bounds.minU - margin, y: bounds.minV - margin * 0.7, w: bounds.maxU - bounds.minU + margin * 2.6, h: bounds.maxV - bounds.minV + margin * 1.4 };
    var view = { x: home.x, y: home.y, w: home.w, h: home.h };

    function apply() { svg.setAttribute('viewBox', view.x + ' ' + view.y + ' ' + view.w + ' ' + view.h); }

    function fit() {
      var rect = svg.getBoundingClientRect();
      var aspect = rect.width && rect.height ? rect.width / rect.height : 1.6;
      view = { x: home.x, y: home.y, w: home.w, h: home.h };
      if (view.w / view.h < aspect) {
        var w = view.h * aspect; view.x -= (w - view.w) / 2; view.w = w;
      } else {
        var h = view.w / aspect; view.y -= (h - view.h) / 2; view.h = h;
      }
      apply();
    }

    function zoomAt(factor, px, py) {
      var rect = svg.getBoundingClientRect();
      var fx = rect.width ? (px - rect.left) / rect.width : 0.5;
      var fy = rect.height ? (py - rect.top) / rect.height : 0.5;
      var w = Math.max(home.w / 12, Math.min(home.w * 3, view.w / factor));
      var h = w * (view.h / view.w);
      view.x += (view.w - w) * fx;
      view.y += (view.h - h) * fy;
      view.w = w; view.h = h;
      apply();
    }

    function centerOn(u, v) { view.x = u - view.w / 2; view.y = v - view.h / 2; apply(); }

    var pointers = {};
    var dragging = null;
    svg.addEventListener('pointerdown', function (event) {
      pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
      if (Object.keys(pointers).length === 1) dragging = { x: event.clientX, y: event.clientY, vx: view.x, vy: view.y };
      svg.setPointerCapture(event.pointerId);
    });
    svg.addEventListener('pointermove', function (event) {
      if (!pointers[event.pointerId]) return;
      var ids = Object.keys(pointers);
      if (ids.length === 2) {
        var before = Math.hypot(pointers[ids[0]].x - pointers[ids[1]].x, pointers[ids[0]].y - pointers[ids[1]].y);
        pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
        var after = Math.hypot(pointers[ids[0]].x - pointers[ids[1]].x, pointers[ids[0]].y - pointers[ids[1]].y);
        if (before > 0 && after > 0) zoomAt(after / before, (pointers[ids[0]].x + pointers[ids[1]].x) / 2, (pointers[ids[0]].y + pointers[ids[1]].y) / 2);
        dragging = null;
        return;
      }
      pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
      if (!dragging) return;
      var rect = svg.getBoundingClientRect();
      var scale = rect.width ? view.w / rect.width : 1;
      view.x = dragging.vx - (event.clientX - dragging.x) * scale;
      view.y = dragging.vy - (event.clientY - dragging.y) * scale;
      apply();
    });
    function release(event) { delete pointers[event.pointerId]; dragging = null; }
    svg.addEventListener('pointerup', release);
    svg.addEventListener('pointercancel', release);
    svg.addEventListener('wheel', function (event) {
      event.preventDefault();
      zoomAt(event.deltaY < 0 ? 1.2 : 1 / 1.2, event.clientX, event.clientY);
    }, { passive: false });
    window.addEventListener('resize', fit);
    fit();

    // Highlighted trip
    function highlight(legs) {
      while (groups.route.firstChild) groups.route.removeChild(groups.route.firstChild);
      var onRoute = {};
      if (legs && legs.length) {
        legs.forEach(function (leg) {
          var line = lineById[leg.line.id];
          for (var i = 0; i < leg.stops.length - 1; i++) {
            var points = layout.path(leg.line.id, leg.stops[i], leg.stops[i + 1]);
            if (!points) continue;
            el('path', { 'class': 'route-outline', d: pathData(points), 'stroke-width': stroke + 10 }, groups.route);
            el('path', { 'class': 'route-path', d: pathData(points), stroke: line ? line.color : 'currentColor', 'stroke-width': stroke + 3 }, groups.route);
          }
          leg.stops.forEach(function (id) { onRoute[id] = true; });
        });
      }
      var dim = !!(legs && legs.length);
      groups.lines.classList.toggle('dimmed', dim);
      Object.keys(stationNodes).forEach(function (id) { stationNodes[id].classList.toggle('dim', dim && !onRoute[id]); });
    }

    function select(id) {
      Object.keys(stationNodes).forEach(function (key) { stationNodes[key].classList.toggle('selected', key === id); });
      var station = layout.stations[id];
      if (station) centerOn(station.u, station.v);
    }

    // Live trains: placed from the last data, then advanced every frame at the pace the data
    // implies, so they glide instead of jumping once per refresh.
    var trainNodes = {};
    var states = {};
    var animating = false;

    function serviceOf(train, network) {
      for (var l = 0; l < network.lines.length; l++) {
        if (network.lines[l].id !== train.line) continue;
        for (var s = 0; s < network.lines[l].services.length; s++) {
          if (network.lines[l].services[s].id === train.service) return network.lines[l].services[s];
        }
      }
      return null;
    }

    function placement(train, network) {
      var service = serviceOf(train, network);
      if (!service) return null;
      var stops = service.stops;
      if (train.at && layout.stations[train.at]) return { stationId: train.at, point: layout.stations[train.at] };
      if (train.last < 0 || train.last >= stops.length) return null;
      var nextIndex = train.last + 1 < stops.length ? train.last + 1 : (service.circular ? 0 : -1);
      if (nextIndex < 0) return null;
      var a = stops[train.last].station, b = stops[nextIndex].station;
      var path = layout.path(train.line, a, b);
      if (!path) return null;
      var fraction = typeof train.fraction === 'number' ? train.fraction : 0.5;
      var rate = 0;
      if (typeof train.eta === 'number' && train.eta > 0 && train.next === nextIndex) {
        rate = (1 - fraction) / train.eta;
      } else if (typeof service.segments[train.last] === 'number' && service.segments[train.last] > 0) {
        rate = 1 / service.segments[train.last];
      }
      return { segment: a + '>' + b, path: path, fraction: fraction, rate: rate };
    }

    function setTrains(trains, network) {
      var now = performance.now();
      var seen = {};
      (trains || []).forEach(function (train) {
        var where = placement(train, network);
        if (!where) return;
        seen[train.name] = true;
        var line = lineById[train.line];
        var node = trainNodes[train.name];
        if (!node) {
          node = el('g', { 'class': 'train', 'data-name': train.name }, groups.trains);
          el('circle', { r: 10, fill: line ? line.color : 'currentColor' }, node);
          el('text', { fill: textOn(line ? line.color : '#000000') }, node).textContent = line ? (line.short || line.id).slice(0, 3) : '';
          node.addEventListener('pointerenter', function (event) { if (options.onTrainHover) options.onTrainHover(node.train, event); });
          node.addEventListener('pointerleave', function () { if (options.onTrainHover) options.onTrainHover(null); });
          node.addEventListener('click', function (event) { if (options.onTrainHover) options.onTrainHover(node.train, event); });
          trainNodes[train.name] = node;
        }
        node.train = train;
        node.classList.toggle('stopped', !!where.stationId);
        var previous = states[train.name];
        if (where.stationId) {
          states[train.name] = { point: where.point };
        } else {
          var fraction = where.fraction;
          if (previous && previous.segment === where.segment) {
            fraction = Math.max(fraction, currentFraction(previous, now));
          }
          states[train.name] = { segment: where.segment, path: where.path, fraction: fraction, rate: where.rate, since: now };
        }
        position(node, states[train.name], now);
      });
      Object.keys(trainNodes).forEach(function (name) {
        if (!seen[name]) { groups.trains.removeChild(trainNodes[name]); delete trainNodes[name]; delete states[name]; }
      });
      if (!animating) { animating = true; requestAnimationFrame(frame); }
    }

    function currentFraction(state, now) {
      return Math.min(MAX_FRACTION, state.fraction + state.rate * (now - state.since) / 1000);
    }

    function position(node, state, now) {
      var point = state.point || ATCSLayout.along(state.path, currentFraction(state, now));
      if (point) node.setAttribute('transform', 'translate(' + point.u.toFixed(1) + ' ' + point.v.toFixed(1) + ')');
    }

    function frame(now) {
      var any = false;
      Object.keys(states).forEach(function (name) {
        var state = states[name];
        if (state.point || !state.rate) return;
        any = true;
        position(trainNodes[name], state, now);
      });
      animating = any;
      if (any) requestAnimationFrame(frame);
    }

    return {
      fit: fit,
      zoomBy: function (factor) { var rect = svg.getBoundingClientRect(); zoomAt(factor, rect.left + rect.width / 2, rect.top + rect.height / 2); },
      highlight: highlight, select: select, setTrains: setTrains, centerOn: centerOn
    };
  }

  root.ATCSMap = { create: create, textOn: textOn };
})(window);
