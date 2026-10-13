/* Draws the schematic network map as SVG: lines, stations, labels and a highlighted trip. The map
 * only redraws when the network or the planned trip changes.
 * Navigation: wheel or pinch to zoom, drag to pan, double-click to zoom in. Taps are matched to
 * the nearest station within a finger's reach (or on its label), so small dots are easy to hit.
 * Panels covering part of the map (the bottom sheet on phones) are set as insets, and fitting or
 * revealing a station uses only the part that can be seen. */
(function (root) {
  'use strict';

  var NS = 'http://www.w3.org/2000/svg';

  function el(name, attributes, parent) {
    var node = document.createElementNS(NS, name);
    Object.keys(attributes || {}).forEach(function (key) { node.setAttribute(key, attributes[key]); });
    if (parent) parent.appendChild(node);
    return node;
  }

  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  function pathData(points) {
    return points.map(function (p, i) { return (i ? 'L' : 'M') + p.u.toFixed(1) + ' ' + p.v.toFixed(1); }).join(' ');
  }

  var BEND = 28;   // radius of the rounded bends, as on subway diagrams

  function point(p) { return p.u.toFixed(1) + ' ' + p.v.toFixed(1); }

  /* A polyline with every bend rounded off. */
  function roundedPath(points, radius) {
    if (points.length < 3) return pathData(points);
    var d = 'M' + point(points[0]);
    for (var i = 1; i < points.length - 1; i++) {
      var p = points[i - 1], c = points[i], n = points[i + 1];
      var l1 = Math.hypot(c.u - p.u, c.v - p.v), l2 = Math.hypot(n.u - c.u, n.v - c.v);
      var r = Math.min(radius, l1 / 2, l2 / 2);
      if (!l1 || !l2 || r <= 0) { d += ' L' + point(c); continue; }
      var a = { u: c.u - (c.u - p.u) / l1 * r, v: c.v - (c.v - p.v) / l1 * r };
      var b = { u: c.u + (n.u - c.u) / l2 * r, v: c.v + (n.v - c.v) / l2 * r };
      d += ' L' + point(a) + ' Q' + point(c) + ' ' + point(b);
    }
    return d + ' L' + point(points[points.length - 1]);
  }

  /* A station name as one or two lines of text around its label point. */
  function labelText(group, station) {
    var label = station.label, lines = label.lines || [station.name], height = label.lineHeight || 18;
    var text = el('text', {
      'class': 'label' + (station.interchange ? ' interchange' : ''),
      x: label.x, y: label.y, 'text-anchor': label.anchor
    }, group);
    lines.forEach(function (line, i) {
      var span = el('tspan', { x: label.x, y: (label.y + (i - (lines.length - 1) / 2) * height).toFixed(1), dy: '0.36em' }, text);
      span.textContent = line;
    });
    return text;
  }

    /* Black or white, whichever reads better on the colour. */
  function textOn(hex) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#fff';
    var n = parseInt(m[1], 16);
    return (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) > 170 ? '#000' : '#fff';
  }

  function escapeXml(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  /* A route bullet as inline SVG. The letter sits on its baseline 0.36em below the centre, which
   * puts the middle of capitals and figures exactly in the middle of the disc in every browser. */
  function bullet(line, size) {
    if (!line) return '';
    size = size || 28;
    var text = String(line.short || line.id);
    var font = Math.round(size * (text.length > 2 ? 0.43 : text.length > 1 ? 0.5 : 0.56));
    var width = text.length <= 2 ? size : Math.round(size + (text.length - 2) * font * 0.62);
    return '<svg class="bullet" width="' + width + '" height="' + size + '" viewBox="0 0 ' + width + ' ' + size + '" role="img" aria-label="' + escapeXml(line.name) + '">'
      + '<title>' + escapeXml(line.name) + '</title>'
      + '<rect width="' + width + '" height="' + size + '" rx="' + (size / 2) + '" fill="' + escapeXml(line.color) + '"/>'
      + '<text x="' + (width / 2) + '" y="' + (size / 2) + '" dy="0.36em" text-anchor="middle" font-size="' + font + '" fill="' + textOn(line.color) + '">' + escapeXml(text) + '</text>'
      + '</svg>';
  }

  function create(svg, wrap, options) {
    options = options || {};
    var layout = null, lineById = {}, stationNodes = {}, labelNodes = {}, groups = null;
    var home = null, view = null, fitted = true, framePending = false;
    var highlighted = '', selected = null;
    // The part of the map covered by panels, in pixels (the bottom sheet on phones).
    var inset = { top: 0, bottom: 0 };
    var size = null;

    // ---- View ------------------------------------------------------------------------------

    function apply() {
      if (view) svg.setAttribute('viewBox', view.x.toFixed(2) + ' ' + view.y.toFixed(2) + ' ' + view.w.toFixed(2) + ' ' + view.h.toFixed(2));
    }

    function requestApply() {
      if (framePending) return;
      framePending = true;
      requestAnimationFrame(function () { framePending = false; apply(); });
    }

    function measure() {
      var rect = svg.getBoundingClientRect();
      return { left: rect.left, top: rect.top, width: rect.width || 1, height: rect.height || 1 };
    }

    /* Map units per screen pixel. */
    function scale() { return view ? view.w / measure().width : 1; }

    /* Shows the box {x, y, w, h} as large as possible in the part of the map nothing covers. */
    function frame(box, maxZoomWidth) {
      var rect = measure();
      var visible = Math.max(80, rect.height - inset.top - inset.bottom);
      var s = Math.max(box.w / rect.width, box.h / visible, (maxZoomWidth || 0) / rect.width);
      view = {
        w: rect.width * s, h: rect.height * s,
        x: box.x + box.w / 2 - rect.width * s / 2,
        y: box.y + box.h / 2 - (inset.top + visible / 2) * s
      };
      size = { width: rect.width, height: rect.height };
      apply();
    }

    function fit() {
      if (!home) return;
      frame(home);
      fitted = true;
    }

    /* After a resize the map keeps its scale and its centre, so nothing jumps. */
    function resized() {
      if (!view) return;
      if (fitted) { fit(); return; }
      var rect = measure();
      if (size && Math.abs(size.width - rect.width) < 0.5 && Math.abs(size.height - rect.height) < 0.5) return;
      var s = size ? view.w / size.width : view.w / rect.width;
      var cx = view.x + view.w / 2, cy = view.y + view.h / 2;
      view.w = rect.width * s;
      view.h = rect.height * s;
      view.x = cx - view.w / 2;
      view.y = cy - view.h / 2;
      size = { width: rect.width, height: rect.height };
      apply();
    }

    function setInsets(top, bottom) {
      inset = { top: Math.max(0, top || 0), bottom: Math.max(0, bottom || 0) };
    }

    /* Scales the view by `factor` around the screen point (px, py). */
    function zoomAt(factor, px, py) {
      if (!view || !home) return;
      var rect = measure();
      var fx = (px - rect.left) / rect.width;
      var fy = (py - rect.top) / rect.height;
      var w = Math.max(home.w / 14, Math.min(home.w * 3, view.w / factor));
      var h = w * (view.h / view.w);
      view.x += (view.w - w) * fx;
      view.y += (view.h - h) * fy;
      view.w = w;
      view.h = h;
      fitted = false;
    }

    function zoomBy(factor) {
      var rect = measure();
      zoomAt(factor, rect.left + rect.width / 2, rect.top + inset.top + (rect.height - inset.top - inset.bottom) / 2);
      apply();
    }

    // Wheel and trackpad: proportional to the scroll distance, at most one redraw per frame.
    var wheel = null;
    wrap.addEventListener('wheel', function (event) {
      if (!view) return;
      event.preventDefault();
      var unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? (wrap.clientHeight || 800) : 1;
      var factor = Math.exp(-event.deltaY * unit * (event.ctrlKey ? 0.01 : 0.002));
      if (!wheel) {
        wheel = { factor: 1, x: event.clientX, y: event.clientY };
        requestAnimationFrame(function () {
          var pending = wheel;
          wheel = null;
          zoomAt(Math.max(0.25, Math.min(4, pending.factor)), pending.x, pending.y);
          apply();
        });
      }
      wheel.factor *= factor;
      wheel.x = event.clientX;
      wheel.y = event.clientY;
    }, { passive: false });

    // ---- Taps ------------------------------------------------------------------------------

    /* The station under a screen point: within a finger's reach of its dot, or on its label. */
    function stationAt(clientX, clientY, touch) {
      if (!layout || !view) return null;
      var rect = measure(), s = view.w / rect.width;
      var mx = view.x + (clientX - rect.left) * s, my = view.y + (clientY - rect.top) * s;
      var reach = (touch ? 24 : 12) * s, best = null, bestDistance = Infinity;
      Object.keys(layout.stations).forEach(function (id) {
        var station = layout.stations[id];
        var d = Math.hypot(station.u - mx, station.v - my) - station.r;
        var box = station.label && station.label.box;
        if (box && mx >= box.x - 4 * s && mx <= box.x + box.w + 4 * s && my >= box.y - 4 * s && my <= box.y + box.h + 4 * s) d = Math.min(d, 0);
        if (d <= reach && d < bestDistance) { best = id; bestDistance = d; }
      });
      return best;
    }

    // Drag to pan, two fingers to zoom. A press that hardly moves is a tap.
    var pointers = {}, press = null, dragging = false, pinchDistance = 0;

    function distance(ids) {
      var a = pointers[ids[0]], b = pointers[ids[1]];
      return Math.hypot(a.x - b.x, a.y - b.y);
    }

    function startPress(id, x, y, type) {
      press = { id: id, x: x, y: y, vx: view ? view.x : 0, vy: view ? view.y : 0, type: type, tap: true };
      dragging = false;
    }

    svg.addEventListener('pointerdown', function (event) {
      if (event.pointerType === 'mouse' && event.button !== 0) return;
      pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
      var ids = Object.keys(pointers);
      if (ids.length === 1) {
        startPress(event.pointerId, event.clientX, event.clientY, event.pointerType);
      } else if (ids.length === 2) {
        if (press) press.tap = false;
        press = null;
        pinchDistance = distance(ids);
      }
    });

    svg.addEventListener('pointermove', function (event) {
      if (!pointers[event.pointerId]) {
        if (event.pointerType === 'mouse' && !event.buttons) {
          svg.classList.toggle('over-station', !!stationAt(event.clientX, event.clientY, false));
        }
        return;
      }
      if (!view) return;
      pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
      var ids = Object.keys(pointers);
      if (ids.length >= 2) {
        var d = distance(ids);
        if (pinchDistance > 0 && d > 0) {
          zoomAt(d / pinchDistance, (pointers[ids[0]].x + pointers[ids[1]].x) / 2, (pointers[ids[0]].y + pointers[ids[1]].y) / 2);
          requestApply();
        }
        pinchDistance = d;
        return;
      }
      if (!press || press.id !== event.pointerId) return;
      if (!dragging) {
        var threshold = press.type === 'mouse' ? 4 : 10;
        if (Math.hypot(event.clientX - press.x, event.clientY - press.y) < threshold) return;
        dragging = true;
        press.tap = false;
        // Continue from here, so the map does not jump by the threshold.
        press.x = event.clientX;
        press.y = event.clientY;
        press.vx = view.x;
        press.vy = view.y;
        try { svg.setPointerCapture(event.pointerId); } catch (ignored) { /* not supported */ }
        svg.classList.add('dragging');
      }
      var s = scale();
      view.x = press.vx - (event.clientX - press.x) * s;
      view.y = press.vy - (event.clientY - press.y) * s;
      fitted = false;
      requestApply();
    });

    function release(event) {
      if (!pointers[event.pointerId]) return;
      var wasTap = event.type === 'pointerup' && press && press.id === event.pointerId && press.tap && Object.keys(pointers).length === 1;
      var tapType = press ? press.type : 'mouse';
      delete pointers[event.pointerId];
      var left = Object.keys(pointers);
      if (left.length === 1) {
        // One finger of a pinch lifted: the other one pans from where it is.
        var p = pointers[left[0]];
        startPress(left[0], p.x, p.y, 'touch');
        press.tap = false;
      } else if (!left.length) {
        press = null;
        dragging = false;
        pinchDistance = 0;
        svg.classList.remove('dragging');
      }
      if (wasTap) {
        var id = stationAt(event.clientX, event.clientY, tapType !== 'mouse');
        if (id) {
          if (options.onStation) options.onStation(id);
        } else if (options.onBackground) {
          options.onBackground();
        }
      }
    }
    svg.addEventListener('pointerup', release);
    svg.addEventListener('pointercancel', release);
    // A finger lifted outside the map must not leave a pointer behind.
    window.addEventListener('pointerup', release);
    window.addEventListener('pointercancel', release);
    svg.addEventListener('dblclick', function (event) {
      event.preventDefault();
      zoomAt(1.8, event.clientX, event.clientY);
      apply();
    });

    if (typeof ResizeObserver !== 'undefined') {
      new ResizeObserver(resized).observe(wrap);
    } else {
      window.addEventListener('resize', resized);
    }

    // ---- Drawing ---------------------------------------------------------------------------

    function draw() {
      clear(svg);
      groups = {};
      ['lines', 'transfers', 'route', 'stations', 'labels'].forEach(function (name) {
        groups[name] = el('g', { 'class': name }, svg);
      });
      lineById = {};
      stationNodes = {};
      labelNodes = {};
      highlighted = '';

      layout.lines.forEach(function (line) {
        lineById[line.id] = line;
        line.edges.forEach(function (edge) {
          el('path', {
            'class': 'line-path' + (line.suspended ? ' suspended' : ''),
            d: roundedPath(edge.points, BEND),
            stroke: line.color,
            'stroke-width': layout.stroke
          }, groups.lines);
        });
      });

      // Walking transfers: dotted when passengers leave the station, solid inside it.
      layout.transfers.forEach(function (transfer) {
        var path = el('path', { 'class': 'transfer ' + (transfer.outside ? 'outside' : 'inside'), d: pathData(transfer.points) }, groups.transfers);
        el('title', {}, path).textContent = 'Walk between ' + layout.stations[transfer.a].name + ' and ' + layout.stations[transfer.b].name;
      });

      Object.keys(layout.stations).forEach(function (id) {
        var station = layout.stations[id];
        var group = el('g', { 'class': 'station' + (station.interchange ? ' interchange' : ''), 'data-id': station.id }, groups.stations);
        el('circle', { cx: station.u, cy: station.v, r: station.r }, group);
        el('title', {}, group).textContent = station.name;
        stationNodes[station.id] = group;
        labelNodes[station.id] = labelText(groups.labels, station);
      });
    }

    function setLayout(newLayout) {
      var first = !layout;
      layout = newLayout;
      draw();
      var bounds = layout.bounds || { minU: 0, maxU: 100, minV: 0, maxV: 100 };
      var margin = layout.cell * 0.6;
      home = {
        x: bounds.minU - margin, y: bounds.minV - margin,
        w: bounds.maxU - bounds.minU + margin * 2, h: bounds.maxV - bounds.minV + margin * 2
      };
      if (first || fitted || !view) fit(); else apply();
      if (selected) select(selected, false);
    }

    // ---- Trip highlight and selection --------------------------------------------------------

    /* Draws a planned trip over the dimmed map and marks where it starts and ends. */
    function highlight(legs) {
      if (!groups) return;
      var hops = [];
      (legs || []).forEach(function (leg) { (leg.hops || []).forEach(function (hop) { hops.push(hop); }); });
      var ends = legs && legs.length ? [legs[0].from, legs[legs.length - 1].to] : [];
      var signature = JSON.stringify([hops, ends]);
      if (signature === highlighted) return;
      highlighted = signature;
      clear(groups.route);
      var onRoute = {};
      hops.forEach(function (hop) {
        onRoute[hop.a] = true;
        onRoute[hop.b] = true;
        var a = layout.stations[hop.a], b = layout.stations[hop.b];
        if (hop.walk) {
          if (!a || !b) return;
          var walk = [{ u: a.u, v: a.v }, { u: b.u, v: b.v }];
          el('path', { 'class': 'route-walk-outline', d: pathData(walk) }, groups.route);
          el('path', { 'class': 'route-walk', d: pathData(walk) }, groups.route);
          return;
        }
        var points = layout.path(hop.line, hop.a, hop.b);
        if (!points) return;
        var line = lineById[hop.line];
        el('path', { 'class': 'route-outline', d: roundedPath(points, BEND), 'stroke-width': layout.stroke + 10 }, groups.route);
        el('path', { 'class': 'route-path', d: roundedPath(points, BEND), stroke: line ? line.color : 'currentColor', 'stroke-width': layout.stroke + 3 }, groups.route);
      });
      var dim = hops.length > 0;
      groups.lines.classList.toggle('dimmed', dim);
      groups.transfers.classList.toggle('dimmed', dim);
      Object.keys(stationNodes).forEach(function (id) {
        stationNodes[id].classList.toggle('dim', dim && !onRoute[id]);
        stationNodes[id].classList.toggle('endpoint', ends.indexOf(id) >= 0);
        labelNodes[id].classList.toggle('dim', dim && !onRoute[id]);
        labelNodes[id].classList.toggle('endpoint', ends.indexOf(id) >= 0);
      });
    }

    /* Marks a station; brings it into the uncovered part of the map only when it is not there. */
    function select(id, reveal) {
      selected = id;
      Object.keys(stationNodes).forEach(function (key) { stationNodes[key].classList.toggle('selected', key === id); });
      var station = id && layout ? layout.stations[id] : null;
      if (!station || !view || reveal === false) return;
      var rect = measure(), s = view.w / rect.width;
      var top = view.y + (inset.top + 40) * s, bottom = view.y + (rect.height - inset.bottom - 40) * s;
      var left = view.x + 40 * s, right = view.x + view.w - 40 * s;
      if (station.u < left || station.u > right || station.v < top || station.v > bottom) {
        var visible = rect.height - inset.top - inset.bottom;
        view.x = station.u - view.w / 2;
        view.y = station.v - (inset.top + visible / 2) * s;
        fitted = false;
        apply();
      }
    }

    /* Frames a planned trip in the uncovered part of the map. */
    function showRoute(legs) {
      if (!layout || !legs || !legs.length) return;
      var minU = Infinity, minV = Infinity, maxU = -Infinity, maxV = -Infinity;
      function add(p) {
        minU = Math.min(minU, p.u); maxU = Math.max(maxU, p.u);
        minV = Math.min(minV, p.v); maxV = Math.max(maxV, p.v);
      }
      legs.forEach(function (leg) {
        (leg.hops || []).forEach(function (hop) {
          [hop.a, hop.b].forEach(function (id) { if (layout.stations[id]) add(layout.stations[id]); });
          if (!hop.walk) (layout.path(hop.line, hop.a, hop.b) || []).forEach(add);
        });
      });
      if (!isFinite(minU)) return;
      var pad = layout.cell * 0.8;
      frame({ x: minU - pad, y: minV - pad, w: maxU - minU + pad * 2, h: maxV - minV + pad * 2 }, home ? home.w / 5 : 0);
      fitted = false;
    }

    return {
      setLayout: setLayout, fit: fit, zoomBy: zoomBy, highlight: highlight, select: select,
      showRoute: showRoute, setInsets: setInsets
    };
  }

  root.ATCSMap = { create: create, textOn: textOn, bullet: bullet };
})(window);
