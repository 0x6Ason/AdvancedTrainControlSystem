/* Draws the schematic network map as SVG: lines, stations, labels and a highlighted trip. The map
 * only redraws when the network or the planned trip changes.
 * Navigation: wheel or pinch to zoom, drag to pan, double-click to zoom in. */
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

  function overlaps(a, b) {
    return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
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

    // ---- View ------------------------------------------------------------------------------

    function apply() {
      if (view) svg.setAttribute('viewBox', view.x.toFixed(2) + ' ' + view.y.toFixed(2) + ' ' + view.w.toFixed(2) + ' ' + view.h.toFixed(2));
    }

    function requestApply() {
      if (framePending) return;
      framePending = true;
      requestAnimationFrame(function () { framePending = false; apply(); });
    }

    function aspect() {
      var rect = svg.getBoundingClientRect();
      return rect.width > 0 && rect.height > 0 ? rect.width / rect.height : 1.6;
    }

    function fit() {
      if (!home) return;
      var ratio = aspect();
      view = { x: home.x, y: home.y, w: home.w, h: home.h };
      if (view.w / view.h < ratio) {
        var w = view.h * ratio; view.x -= (w - view.w) / 2; view.w = w;
      } else {
        var h = view.w / ratio; view.y -= (h - view.h) / 2; view.h = h;
      }
      fitted = true;
      apply();
    }

    function resized() {
      if (!view) return;
      if (fitted) { fit(); return; }
      var middle = view.y + view.h / 2;
      view.h = view.w / aspect();
      view.y = middle - view.h / 2;
      apply();
    }

    /* Scales the view by `factor` around the screen point (px, py). */
    function zoomAt(factor, px, py) {
      if (!view || !home) return;
      var rect = svg.getBoundingClientRect();
      var fx = rect.width ? (px - rect.left) / rect.width : 0.5;
      var fy = rect.height ? (py - rect.top) / rect.height : 0.5;
      var w = Math.max(home.w / 14, Math.min(home.w * 3, view.w / factor));
      var h = w * (view.h / view.w);
      view.x += (view.w - w) * fx;
      view.y += (view.h - h) * fy;
      view.w = w;
      view.h = h;
      fitted = false;
    }

    function zoomBy(factor) {
      var rect = svg.getBoundingClientRect();
      zoomAt(factor, rect.left + rect.width / 2, rect.top + rect.height / 2);
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

    // Drag to pan, two fingers to zoom. The pointer is captured only once a drag has begun, so
    // a plain click still reaches the station under it.
    var pointers = {}, press = null, dragging = false, pinchDistance = 0, suppressClick = false;

    function distance(ids) {
      var a = pointers[ids[0]], b = pointers[ids[1]];
      return Math.hypot(a.x - b.x, a.y - b.y);
    }

    svg.addEventListener('pointerdown', function (event) {
      if (event.pointerType === 'mouse' && event.button !== 0) return;
      suppressClick = false;
      pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
      var ids = Object.keys(pointers);
      if (ids.length === 1) {
        press = { id: event.pointerId, x: event.clientX, y: event.clientY, vx: view ? view.x : 0, vy: view ? view.y : 0 };
        dragging = false;
      } else if (ids.length === 2) {
        press = null;
        pinchDistance = distance(ids);
      }
    });

    svg.addEventListener('pointermove', function (event) {
      if (!pointers[event.pointerId] || !view) return;
      pointers[event.pointerId] = { x: event.clientX, y: event.clientY };
      var ids = Object.keys(pointers);
      if (ids.length >= 2) {
        var d = distance(ids);
        if (pinchDistance > 0 && d > 0) {
          zoomAt(d / pinchDistance, (pointers[ids[0]].x + pointers[ids[1]].x) / 2, (pointers[ids[0]].y + pointers[ids[1]].y) / 2);
          requestApply();
        }
        pinchDistance = d;
        suppressClick = true;
        return;
      }
      if (!press || press.id !== event.pointerId) return;
      if (!dragging) {
        if (Math.hypot(event.clientX - press.x, event.clientY - press.y) < 5) return;
        dragging = true;
        try { svg.setPointerCapture(event.pointerId); } catch (ignored) { /* not supported */ }
        svg.classList.add('dragging');
      }
      var rect = svg.getBoundingClientRect();
      var scale = rect.width ? view.w / rect.width : 1;
      view.x = press.vx - (event.clientX - press.x) * scale;
      view.y = press.vy - (event.clientY - press.y) * scale;
      fitted = false;
      requestApply();
    });

    function release(event) {
      if (!pointers[event.pointerId]) return;
      delete pointers[event.pointerId];
      if (dragging) suppressClick = true;
      if (!Object.keys(pointers).length) {
        press = null;
        dragging = false;
        pinchDistance = 0;
        svg.classList.remove('dragging');
      }
    }
    svg.addEventListener('pointerup', release);
    svg.addEventListener('pointercancel', release);
    svg.addEventListener('click', function (event) {
      if (suppressClick) {
        suppressClick = false;
        event.stopPropagation();
        event.preventDefault();
      }
    }, true);
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
      ['lines', 'route', 'stations', 'labels'].forEach(function (name) {
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
            d: pathData(edge.points),
            stroke: line.color,
            'stroke-width': layout.stroke
          }, groups.lines);
        });
      });

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
          { x: station.u + 16, y: station.v - height / 2, anchor: 'start', dx: 16, dy: 0 },
          { x: station.u - 16 - width, y: station.v - height / 2, anchor: 'end', dx: -16, dy: 0 },
          { x: station.u - width / 2, y: station.v + 16, anchor: 'middle', dx: 0, dy: 16 + height / 2 },
          { x: station.u - width / 2, y: station.v - 16 - height, anchor: 'middle', dx: 0, dy: -16 - height / 2 }
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
        var label = el('text', {
          'class': 'label' + (station.interchange ? ' interchange' : ''),
          x: station.u + chosen.dx, y: station.v + chosen.dy,
          dy: '0.36em',
          'text-anchor': chosen.anchor
        }, groups.labels);
        label.textContent = station.name;
        labelNodes[station.id] = label;
      });
    }

    function setLayout(newLayout) {
      var first = !layout;
      layout = newLayout;
      draw();
      var bounds = layout.bounds || { minU: 0, maxU: 100, minV: 0, maxV: 100 };
      var margin = layout.cell * 1.5;
      home = {
        x: bounds.minU - margin, y: bounds.minV - margin * 0.7,
        w: bounds.maxU - bounds.minU + margin * 2.6, h: bounds.maxV - bounds.minV + margin * 1.4
      };
      if (first || fitted || !view) fit(); else apply();
      if (selected) select(selected, false);
    }

    // ---- Trip highlight and selection --------------------------------------------------------

    function highlight(legs) {
      if (!groups) return;
      var hops = [];
      (legs || []).forEach(function (leg) { (leg.hops || []).forEach(function (hop) { hops.push(hop); }); });
      var signature = JSON.stringify(hops);
      if (signature === highlighted) return;
      highlighted = signature;
      clear(groups.route);
      var onRoute = {};
      hops.forEach(function (hop) {
        onRoute[hop.a] = true;
        onRoute[hop.b] = true;
        var points = layout.path(hop.line, hop.a, hop.b);
        if (!points) return;
        var line = lineById[hop.line];
        el('path', { 'class': 'route-outline', d: pathData(points), 'stroke-width': layout.stroke + 10 }, groups.route);
        el('path', { 'class': 'route-path', d: pathData(points), stroke: line ? line.color : 'currentColor', 'stroke-width': layout.stroke + 3 }, groups.route);
      });
      var dim = hops.length > 0;
      groups.lines.classList.toggle('dimmed', dim);
      Object.keys(stationNodes).forEach(function (id) {
        stationNodes[id].classList.toggle('dim', dim && !onRoute[id]);
        labelNodes[id].classList.toggle('dim', dim && !onRoute[id]);
      });
    }

    /* Marks a station; brings it into view only when it is off screen. */
    function select(id, reveal) {
      selected = id;
      Object.keys(stationNodes).forEach(function (key) { stationNodes[key].classList.toggle('selected', key === id); });
      var station = id && layout ? layout.stations[id] : null;
      if (!station || !view || reveal === false) return;
      var mx = view.w * 0.12, my = view.h * 0.12;
      if (station.u < view.x + mx || station.u > view.x + view.w - mx || station.v < view.y + my || station.v > view.y + view.h - my) {
        view.x = station.u - view.w / 2;
        view.y = station.v - view.h / 2;
        fitted = false;
        apply();
      }
    }

    return { setLayout: setLayout, fit: fit, zoomBy: zoomBy, highlight: highlight, select: select };
  }

  root.ATCSMap = { create: create, textOn: textOn, bullet: bullet };
})(window);
