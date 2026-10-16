/* The railway editor. It edits network.yml and fares.yml as documents with the files' own
 * structure, so every check the server makes on the files (the same messages the commands give)
 * applies here too. Changes stay in the page until saved; the save goes through the same store as
 * the commands, so a file edited by hand on disk is never overwritten and a stale page is told so.
 *
 * Layout: the site's diagram on the left, which redraws as you edit (drag a station to pin it to
 * a cell); on the right a directory rail of sections and the section being edited; the save bar
 * spans the panel. Local undo (Ctrl+Z) covers every edit since the page loaded. */
(function () {
  'use strict';

  // ---- Helpers ---------------------------------------------------------------------------------

  function $(id) { return document.getElementById(id); }

  function esc(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function clone(value) { return value === undefined ? undefined : JSON.parse(JSON.stringify(value)); }
  function keys(object) { return object && typeof object === 'object' ? Object.keys(object) : []; }
  function plural(count, word, many) { return count + ' ' + (count === 1 ? word : (many || word + 's')); }
  function path(parts) { return esc(JSON.stringify(parts)); }
  function fold(text) { return String(text || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim(); }

  var ID = /^[a-z0-9_-]{1,32}$/;
  var POSITION = /^(.+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)$/;

  function slugify(text) {
    var slug = String(text || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase()
      .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    if (slug.length > 32) slug = slug.slice(0, 32).replace(/-+$/, '');
    return slug;
  }

  function parsePosition(text) {
    var m = POSITION.exec(String(text || '').trim());
    return m ? { world: m[1].trim(), x: +m[2], y: +m[3], z: +m[4] } : null;
  }

  function parseStop(text) {
    var raw = String(text || '').trim(), pass = raw.charAt(0) === '~';
    if (pass) raw = raw.slice(1);
    var colon = raw.indexOf(':');
    return { station: colon < 0 ? raw : raw.slice(0, colon), platform: colon < 0 ? null : raw.slice(colon + 1), pass: pass };
  }

  function formatStop(stop) { return (stop.pass ? '~' : '') + stop.station + (stop.platform ? ':' + stop.platform : ''); }

  function getPath(root, parts) {
    var node = root;
    for (var i = 0; i < parts.length; i++) {
      if (node == null || typeof node !== 'object') return undefined;
      node = node[parts[i]];
    }
    return node;
  }

  function setPath(root, parts, value) {
    var node = root;
    for (var i = 0; i < parts.length - 1; i++) {
      var key = parts[i];
      if (node[key] == null || typeof node[key] !== 'object') {
        if (value === undefined) return;
        node[key] = typeof parts[i + 1] === 'number' ? [] : {};
      }
      node = node[key];
    }
    var last = parts[parts.length - 1];
    if (value === undefined) {
      if (Array.isArray(node)) node.splice(last, 1); else delete node[last];
    } else {
      node[last] = value;
    }
  }

  /* Removes objects left empty by an edit where an empty object means nothing (map:, match:). */
  function prune(root, parts) {
    for (var depth = parts.length - 1; depth >= 1; depth--) {
      var parent = parts.slice(0, depth), object = getPath(root, parent);
      var prunable = parent[0] === 'map' || parent[parent.length - 1] === 'match';
      if (prunable && object && typeof object === 'object' && !Array.isArray(object) && !keys(object).length) {
        setPath(root, parent, undefined);
      } else {
        break;
      }
    }
  }

  function numberText(value) {
    if (value === undefined || value === null || value === '') return '';
    var n = +value;
    return isFinite(n) ? String(Math.round(n * 1000) / 1000) : String(value);
  }

  // ---- Server ----------------------------------------------------------------------------------

  var SESSION_KEY = 'atcs-editor-session';
  var session = null;

  function request(method, url, body) {
    var options = { method: method, headers: { Accept: 'application/json' } };
    if (session) options.headers.Authorization = 'Bearer ' + session;
    if (body !== undefined) {
      options.headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(body);
    }
    return fetch(url, options).then(function (response) {
      return response.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (ignored) { data = { error: text }; }
        if (response.status === 401) signedOut();
        if (!response.ok) {
          var error = new Error((data && data.error) || ('The server answered ' + response.status));
          error.status = response.status;
          error.detail = data && data.detail;
          throw error;
        }
        offline(false);
        return data;
      });
    }, function () {
      offline(true);
      var error = new Error('The editor cannot reach the server. Is it running?');
      error.status = 0;
      throw error;
    });
  }

  // ---- State -----------------------------------------------------------------------------------

  var state = {
    net: null, saved: '', doc: null,
    fares: null, faresSaved: '', faresDoc: null,
    check: null, faresCheck: null,
    section: 'stations', open: null, creating: null, filter: {},
    seed: null, layout: null,
    undo: [], redo: [], coalesce: null, coalesceAt: 0,
    saving: false, savedAt: null, offline: false, remote: { network: false, fares: false }, rev: 0, seedLoaded: false,
    config: { name: 'config.yml', text: '', saved: null, version: null, problems: null, loading: false }
  };
  var map = null;

  function doc() { return state.doc; }
  function stations() { return (state.doc && state.doc.stations) || {}; }
  function lines() { return (state.doc && state.doc.lines) || {}; }
  function context() { return (state.net && state.net.context) || {}; }

  /* The documents as text, worked out once per change rather than on every render. */
  var memo = { rev: -1, doc: '', fares: '' };
  function texts() {
    if (memo.rev !== state.rev) {
      memo.rev = state.rev;
      memo.doc = state.doc ? JSON.stringify(state.doc) : '';
      memo.fares = state.faresDoc ? JSON.stringify(state.faresDoc) : '';
    }
    return memo;
  }
  function bump() { state.rev++; }

  function networkDirty() { return !!state.doc && texts().doc !== state.saved; }
  function faresDirty() { return !!state.faresDoc && texts().fares !== state.faresSaved; }
  function faresPending() { return faresDirty() && !(state.faresCheck && state.faresCheck.for === texts().fares); }
  function configDirty() { return state.config.saved !== null && state.config.text !== state.config.saved; }
  function dirty() { return networkDirty() || faresDirty(); }

  function stationName(id) { var s = stations()[id]; return s && s.name ? s.name : id; }
  function services(lineId) { var l = lines()[lineId]; return (l && l.services) || {}; }

  function lineView(id) {
    var l = lines()[id] || {};
    var color = (l.color || '#9E9E9E');
    return { id: id, name: l.name || id, short: l['short-name'] || id.toUpperCase(), color: color, suspended: l.suspended !== undefined };
  }

  function bullet(lineId, size) { return ATCSMap.bullet(lineView(lineId), size || 24); }

  /* Every service in the network, in file order: { line, id, key, service }. */
  function allServices() {
    var list = [];
    keys(lines()).forEach(function (lineId) {
      keys(services(lineId)).forEach(function (id) { list.push({ line: lineId, id: id, key: lineId + '/' + id, service: services(lineId)[id] }); });
    });
    return list;
  }

  /* Lines that call (not only run through) at a station. */
  function linesAt(stationId) {
    var found = [];
    allServices().forEach(function (entry) {
      if (found.indexOf(entry.line) >= 0) return;
      (entry.service.stops || []).forEach(function (text) {
        var stop = parseStop(text);
        if (stop.station === stationId && !stop.pass && found.indexOf(entry.line) < 0) found.push(entry.line);
      });
    });
    return found;
  }

  function stationCode(id) {
    var s = stations()[id];
    if (s && s.code) return String(s.code);
    var codes = context().codes || {};
    if (codes[id]) return codes[id];
    var parts = id.split(/[-_]+/).filter(Boolean);
    if (!parts.length) return 's';
    if (parts.length === 1) return parts[0].length <= 4 ? parts[0] : parts[0].slice(0, 3);
    return parts.map(function (p) { return /^\d+$/.test(p) ? p : p.charAt(0); }).join('').slice(0, 8);
  }

  /* Where a station sits for the diagram: its location, else the middle of its platform signs, else where the server saw it. */
  function stationPosition(id) {
    var s = stations()[id];
    var at = s && parsePosition(s.location);
    if (at) return at;
    var world = null, x = 0, z = 0, n = 0;
    keys(s && s.platforms).forEach(function (pid) {
      ((s.platforms[pid] || {}).triggers || []).forEach(function (text) {
        var p = parsePosition(text);
        if (!p || (world && p.world !== world)) return;
        world = p.world; x += p.x; z += p.z; n++;
      });
    });
    if (n) return { world: world, x: Math.round(x / n), z: Math.round(z / n) };
    var known = (context().positions || {})[id];
    return known ? { world: known.world, x: known.x, z: known.z } : null;
  }

  /* The station roundel, drawn as the plugin draws it for BlueMap and the item-frame logos. */
  function roundel(id, size) {
    var colors = linesAt(id).map(function (l) { return lineView(l).color; });
    if (!colors.length) colors = ['#9E9E9E'];
    var code = stationCode(id).toUpperCase();
    var ring = Math.max(2, size / 6), c = size / 2, r = c - ring / 2 - 1;
    var svg = '<svg class="roundel" viewBox="0 0 ' + size + ' ' + size + '" width="' + size + '" height="' + size + '" aria-hidden="true">'
      + '<circle cx="' + c + '" cy="' + c + '" r="' + (c - 1) + '" fill="#fff"/>';
    if (colors.length === 1) {
      svg += '<circle cx="' + c + '" cy="' + c + '" r="' + r + '" fill="none" stroke="' + esc(colors[0]) + '" stroke-width="' + ring + '"/>';
    } else {
      var sweep = 2 * Math.PI / colors.length;
      colors.forEach(function (color, i) {
        var a = -Math.PI / 2 + i * sweep, b = a + sweep;
        svg += '<path d="M ' + (c + r * Math.cos(a)).toFixed(2) + ' ' + (c + r * Math.sin(a)).toFixed(2) + ' A ' + r.toFixed(2) + ' ' + r.toFixed(2)
          + ' 0 0 1 ' + (c + r * Math.cos(b)).toFixed(2) + ' ' + (c + r * Math.sin(b)).toFixed(2) + '" fill="none" stroke="' + esc(color)
          + '" stroke-width="' + ring + '"/>';
      });
    }
    var font = Math.min(size * 0.42, (size - 2 * ring) * 0.72 / Math.max(1, code.length) * 1.6);
    return svg + '<circle cx="' + c + '" cy="' + c + '" r="' + (r + ring / 2) + '" fill="none" stroke="#1f2328" stroke-width="' + Math.max(1, size / 40) + '"/>'
      + '<text x="' + c + '" y="' + c + '" text-anchor="middle" dy="0.36em" font-family="Helvetica Neue, Helvetica, Arial, sans-serif" font-weight="700" font-size="'
      + font.toFixed(1) + '" fill="#1f2328">' + esc(code) + '</text></svg>';
  }

  // ---- Errors and findings ---------------------------------------------------------------------

  /* What the server says about the version being edited: the unsaved one once checked, else the saved one. */
  function findings() {
    var pending = networkDirty() && !(state.check && state.check.for === texts().doc);
    if (networkDirty() && state.check) {
      return { errors: state.check.errors || [], issues: state.check.issues || [], lint: state.check.lint || [], unsaved: true, pending: pending };
    }
    return { errors: [], issues: (state.net && state.net.issues) || [], lint: (state.net && state.net.lint) || [], unsaved: false, pending: pending };
  }

  function fareErrors() { return faresDirty() && state.faresCheck ? state.faresCheck.errors || [] : []; }

  /* Codec errors start with the path of what they are about: stations.main.platforms.1: ... */
  function errorPath(text) {
    var colon = text.indexOf(': ');
    var head = colon > 0 ? text.slice(0, colon) : '';
    if (!/^[a-z-]+[\w.\-\[\]|]*$/i.test(head)) return [];
    return head.replace(/\[(\d+)\]/g, '.$1').split('.');
  }

  function errorsUnder(prefix) {
    return findings().errors.filter(function (error) {
      var p = errorPath(error);
      for (var i = 0; i < prefix.length; i++) if (String(p[i]) !== String(prefix[i])) return false;
      return true;
    });
  }

  /* Network issues and checks that mention a thing: a station, line, service or board. */
  function mentions(kind, id) {
    var f = findings();
    var all = f.issues.map(function (text) { return { level: 'problem', text: text }; }).concat(f.lint);
    return all.filter(function (finding) {
      var t = finding.text;
      if (kind === 'service') return t.indexOf(id + ' ') >= 0 || t.indexOf(id + ',') >= 0 || t.slice(-id.length) === id;
      if (kind === 'station') return t.indexOf("'" + id + "'") >= 0 || t.indexOf(stationName(id)) >= 0;
      if (kind === 'line') return t.indexOf('Line ' + lineView(id).name) >= 0 || t.indexOf(lineView(id).name + ' ') === 0 || new RegExp('\\b' + id + '/').test(t);
      if (kind === 'board') return t.indexOf('Board ' + id) >= 0 || t.indexOf('board ' + id) >= 0;
      return false;
    });
  }

  function problemBox(errors, others) {
    var items = errors.map(function (e) { return '<li class="error">' + linkify(e) + '</li>'; })
      .concat((others || []).map(function (f) { return '<li class="' + esc(f.level) + '">' + linkify(f.text) + '</li>'; }));
    if (!items.length) return '';
    var head = errors.length ? plural(errors.length, 'error') + ' to fix before saving' : 'Worth a look';
    return '<div class="problems-box"' + (errors.length ? '' : ' style="border-color:var(--rule)"') + '><header'
      + (errors.length ? '' : ' style="background:var(--ink)"') + '>' + esc(head) + '</header><ul class="findings">' + items.join('') + '</ul></div>';
  }

  /* Turns station ids, station names and service keys in a message into links. */
  function linkify(text) {
    var html = esc(text);
    var names = keys(stations()).map(function (id) { return { id: id, name: stationName(id) }; })
      .filter(function (s) { return s.name.length > 2; })
      .sort(function (a, b) { return b.name.length - a.name.length; });
    var marks = [];
    html = html.replace(/\b([a-z0-9_-]+)\/([a-z0-9_-]+)\b/g, function (all, l, s) {
      if (!services(l)[s]) return all;
      marks.push('<a class="link" data-act="open-service" data-line="' + esc(l) + '" data-service="' + esc(s) + '">' + all + '</a>');
      return '\u0000' + (marks.length - 1) + '\u0000';
    });
    html = html.replace(/&#39;([a-z0-9_-]+)&#39;/g, function (all, id) {
      if (!stations()[id]) return all;
      marks.push('&#39;<a class="link" data-act="open-station" data-id="' + esc(id) + '">' + esc(id) + '</a>&#39;');
      return '\u0000' + (marks.length - 1) + '\u0000';
    });
    names.forEach(function (s) {
      var safe = esc(s.name);
      if (html.indexOf(safe) < 0) return;
      html = html.split(safe).join('\u0001' + marks.length + '\u0001');
      marks.push('<a class="link" data-act="open-station" data-id="' + esc(s.id) + '">' + safe + '</a>');
    });
    return html.replace(/[\u0000\u0001](\d+)[\u0000\u0001]/g, function (all, n) { return marks[+n]; });
  }

  // ---- Page ------------------------------------------------------------------------------------

  var PAGE = ''
    + '<header class="sign">'
    + '  <div class="sign-left"><h1 id="title">Railway<span class="sub">Editor</span></h1><div class="sign-bullets" id="header-bullets" aria-label="Lines"></div></div>'
    + '  <div class="sign-right">'
    + '    <span class="sign-note" id="state-note"></span>'
    + '    <span class="chip" id="state-chip">LOADING</span>'
    + '    <button type="button" class="sign-button optional" data-act="restore" title="Put network.yml back as it was before the last saved edit, from here or from a command">Restore previous version</button>'
    + '  </div>'
    + '</header>'
    + '<main class="layout editor" id="layout">'
    + '  <section class="map-wrap" id="map-wrap" aria-label="Network diagram">'
    + '    <svg id="map" role="img" aria-label="Schematic network diagram"></svg>'
    + '    <div class="map-tools">'
    + '      <button type="button" id="zoom-in" aria-label="Zoom in" title="Zoom in">+</button>'
    + '      <button type="button" id="zoom-out" aria-label="Zoom out" title="Zoom out">&minus;</button>'
    + '      <button type="button" id="zoom-fit" aria-label="Show the whole network" title="Show the whole network">'
    + '        <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M2 7V2h5M13 2h5v5M18 13v5h-5M7 18H2v-5" fill="none" stroke="currentColor" stroke-width="2.5"/></svg>'
    + '      </button>'
    + '    </div>'
    + '    <div class="legend map-hint" id="map-hint"><div class="legend-item"><span class="legend-name">Tap a station to edit it. Drag one to pin it to another cell.</span></div></div>'
    + '    <div id="map-empty" class="map-empty" hidden></div>'
    + '  </section>'
    + '  <aside class="panel editor-panel" id="panel">'
    + '    <div class="editor-main">'
    + '      <nav class="rail" id="rail" aria-label="Sections"></nav>'
    + '      <section class="pane" id="pane" aria-live="polite"></section>'
    + '    </div>'
    + '    <footer class="savebar" id="savebar"></footer>'
    + '  </aside>'
    + '</main>'
    + '<div id="modal-root"></div>'
    + '<div id="toast-root"></div>';

  var SECTIONS = [
    { id: 'stations', label: 'Stations', count: function () { return keys(stations()).length; } },
    { id: 'lines', label: 'Lines', count: function () { return keys(lines()).length; } },
    { id: 'boards', label: 'Boards', count: function () { return keys(doc().boards).length; } },
    { id: 'transfers', label: 'Transfers', count: function () { return (doc().transfers || []).length; } },
    { id: 'fares', label: 'Fares', count: function () { return state.faresDoc ? keys(state.faresDoc.pairs).length || '' : ''; } },
    { id: 'diagram', label: 'Diagram', count: function () { return keys(doc().map && doc().map.stations).length || ''; } },
    { id: 'displays', label: 'Displays', count: function () { return keys(doc().displays).length; } },
    { id: 'spacer' },
    { id: 'settings', label: 'Settings', count: function () { return configDirty() ? 'edited' : ''; } },
    { id: 'check', label: 'Check' },
    { id: 'dispatch', label: 'Dispatch', count: function () { return dispatch.live && dispatch.live.trains ? dispatch.live.trains.length : ''; } }
  ];

  // ---- Chrome: header, rail, save bar ---------------------------------------------------------

  function updateChrome() {
    if (!state.doc) return;
    var f = findings();
    var errors = f.errors.length + fareErrors().length;
    var chip = $('state-chip');
    var label, kind;
    if (state.offline) { label = 'OFFLINE'; kind = ''; }
    else if (!state.net.writable) { label = 'READ ONLY'; kind = 'error'; }
    else if (state.saving) { label = 'SAVING'; kind = 'dirty'; }
    else if (errors) { label = plural(errors, 'ERROR', 'ERRORS'); kind = 'error'; }
    else if (dirty()) { label = 'UNSAVED'; kind = 'dirty'; }
    else { label = 'SAVED'; kind = 'saved'; }
    chip.textContent = label;
    chip.className = 'chip ' + kind;
    $('state-note').textContent = plural(keys(stations()).length, 'station') + ', ' + plural(keys(lines()).length, 'line');
    $('header-bullets').innerHTML = keys(lines()).map(function (id) { return bullet(id, 24); }).join('');
    var title = context().title;
    if (title) {
      $('title').innerHTML = esc(title) + '<span class="sub">Editor</span>';
      document.title = title + ' editor';
    }
    renderRail();
    renderSavebar();
  }

  function renderRail() {
    var f = findings();
    var bad = f.errors.length + fareErrors().length + f.issues.length + f.lint.filter(function (x) { return x.level === 'problem'; }).length;
    var warn = f.lint.filter(function (x) { return x.level === 'warning'; }).length;
    $('rail').innerHTML = SECTIONS.map(function (s) {
      if (s.id === 'spacer') return '<div class="spacer" aria-hidden="true"></div>';
      var count = s.count ? s.count() : '';
      var countHtml = '<span class="count">' + esc(count) + '</span>';
      if (s.id === 'check') countHtml = bad ? '<span class="count bad">' + bad + '</span>' : warn ? '<span class="count warn">' + warn + '</span>' : '<span class="count">ok</span>';
      return '<button type="button" data-act="section" data-section="' + s.id + '"' + (state.section === s.id ? ' aria-current="true"' : '') + '>'
        + '<span>' + esc(s.label) + '</span>' + countHtml + '</button>';
    }).join('');
  }

  function changeSummary() {
    var parts = [];
    if (networkDirty()) {
      var before = JSON.parse(state.saved), after = state.doc;
      [['stations', 'station'], ['lines', 'line'], ['boards', 'board'], ['displays', 'display']].forEach(function (pair) {
        var a = before[pair[0]] || {}, b = after[pair[0]] || {}, n = 0;
        keys(a).concat(keys(b).filter(function (k) { return !(k in a); })).forEach(function (k) {
          if (JSON.stringify(a[k]) !== JSON.stringify(b[k])) n++;
        });
        if (n) parts.push(plural(n, pair[1]));
      });
      if (JSON.stringify(before.transfers || []) !== JSON.stringify(after.transfers || [])) parts.push('transfers');
      if (JSON.stringify(before.map || {}) !== JSON.stringify(after.map || {})) parts.push('the diagram');
    }
    if (faresDirty()) parts.push('fares');
    return parts;
  }

  function renderSavebar() {
    var bar = $('savebar');
    var undoButtons = '<button type="button" class="square" data-act="local-undo" title="Undo (Ctrl+Z)" aria-label="Undo"' + (state.undo.length ? '' : ' disabled') + '>'
      + '<svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M7 4L3 8l4 4M3 8h9a5 5 0 0 1 0 10H8" fill="none" stroke="currentColor" stroke-width="2.2"/></svg></button>'
      + '<button type="button" class="square" data-act="local-redo" title="Redo (Ctrl+Shift+Z)" aria-label="Redo"' + (state.redo.length ? '' : ' disabled') + '>'
      + '<svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M13 4l4 4-4 4M17 8H8a5 5 0 0 0 0 10h4" fill="none" stroke="currentColor" stroke-width="2.2"/></svg></button>';
    if (!state.net.writable) {
      bar.innerHTML = '<div class="state"><b>network.yml has errors on disk</b><small>Fix the file and run /atcs reload; until then nothing can be saved. The Check section lists the errors.</small></div>'
        + '<button type="button" data-act="section" data-section="check">Show errors</button>';
      return;
    }
    if (state.saving) {
      bar.innerHTML = '<div class="state"><b>Saving&hellip;</b><small>Writing network.yml through the same checks as the commands</small></div>';
      return;
    }
    if (!dirty()) {
      bar.innerHTML = '<div class="state quiet"><b>All changes saved</b><small>' + (state.savedAt ? 'Last saved at ' + esc(state.savedAt) + '. ' : '')
        + 'Ctrl+S saves, Ctrl+Z undoes.</small></div>' + undoButtons;
      return;
    }
    var f = findings(), errors = f.errors.length + fareErrors().length;
    var summary = changeSummary();
    var save = errors
      ? '<button type="button" class="errors" data-act="section" data-section="check">' + plural(errors, 'error') + '</button>'
      : '<button type="button" class="primary" data-act="save"' + (f.pending ? ' disabled title="Checking your changes"' : '') + '>Save</button>';
    bar.innerHTML = '<div class="state"><b>Unsaved changes</b><small>' + esc(summary.length ? summary.join(', ') : 'Changed') + (f.pending ? ' · checking' : '') + '</small></div>'
      + undoButtons
      + '<button type="button" data-act="review">Review</button>'
      + '<button type="button" data-act="discard">Discard</button>' + save;
  }

  // ---- Pane rendering ------------------------------------------------------------------------

  function renderPane(keepScroll) {
    var pane = $('pane');
    var top = keepScroll ? pane.scrollTop : 0;
    var html;
    switch (state.section) {
      case 'stations': html = state.open && state.open.kind === 'station' ? stationView(state.open.id) : stationsView(); break;
      case 'lines':
        html = state.open && state.open.kind === 'service' ? serviceView(state.open.line, state.open.id)
          : state.open && state.open.kind === 'line' ? lineView2(state.open.id) : linesView();
        break;
      case 'boards': html = state.open && state.open.kind === 'board' ? boardView(state.open.id) : boardsView(); break;
      case 'transfers': html = transfersView(); break;
      case 'fares': html = faresView(); break;
      case 'diagram': html = diagramView(); break;
      case 'displays': html = displaysView(); break;
      case 'settings': html = settingsView(); break;
      case 'dispatch': html = dispatchView(); break;
      default: html = checkView();
    }
    pane.innerHTML = remoteNotice() + '<div class="pane-inner">' + html + '</div>';
    pane.scrollTop = top;
    if (state.section === 'settings') wireCode();
    applyFocus();
    renderRail();
  }

  function remoteNotice() {
    if (!(state.remote.network && networkDirty()) && !(state.remote.fares && faresDirty())) return '';
    return '<div class="pane-inner" style="padding-bottom:0"><div class="notice warn"><div class="main"><b>Changed on the server meanwhile</b>'
      + 'Someone edited the network in game or on disk since you started. Your edits are kept here; saving will show both versions and ask which to keep.</div>'
      + '<button type="button" class="button small secondary" data-act="load-theirs">Load theirs</button></div></div>';
  }

  /* Updates the parts of an open form that depend on what is typed, without touching the inputs. */
  function refreshLive() {
    var pane = $('pane');
    Array.prototype.forEach.call(pane.querySelectorAll('[data-preview]'), function (node) {
      var spec = node.getAttribute('data-preview').split(':');
      var kind = spec[0], id = spec.slice(1).join(':');
      if (kind === 'roundel') node.innerHTML = roundel(id, 44);
      else if (kind === 'code') node.innerHTML = codeHint(id);
      else if (kind === 'bullet') node.innerHTML = bullet(id, 40);
      else if (kind === 'name') node.textContent = stationName(id);
      else if (kind === 'line-name') node.textContent = lineView(id).name;
      else if (kind === 'service-name') { var p = id.split('/'); node.textContent = (services(p[0])[p[1]] || {}).name || p[1]; }
      else if (kind === 'errors') node.innerHTML = errorsBoxFor(id);
    });
  }

  function errorsBoxFor(spec) {
    var parts = spec.split('|');
    if (parts[0] === 'station') return problemBox(errorsUnder(['stations', parts[1]]), mentions('station', parts[1]).filter(notNote));
    if (parts[0] === 'line') return problemBox(errorsUnder(['lines', parts[1]]).filter(function (e) { return errorPath(e).length <= 3 || errorPath(e)[2] !== 'services'; }),
      mentions('line', parts[1]).filter(notNote));
    if (parts[0] === 'service') return problemBox(errorsUnder(['lines', parts[1], 'services', parts[2]]), mentions('service', parts[1] + '/' + parts[2]).filter(notNote));
    if (parts[0] === 'board') return problemBox(errorsUnder(['boards', parts[1]]), mentions('board', parts[1]).filter(notNote));
    if (parts[0] === 'transfers') return problemBox(errorsUnder(['transfers']), []);
    if (parts[0] === 'fares') return problemBox(fareErrors(), []);
    if (parts[0] === 'map') return problemBox(errorsUnder(['map']), []);
    return '';
  }

  function notNote(f) { return f.level !== 'note'; }

  function field(label, input, options) {
    options = options || {};
    return '<label class="field' + (options.wide ? ' wide' : '') + (options.big ? ' big' : '') + '"><span>' + esc(label) + '</span>' + input
      + (options.hint ? '<small class="hint">' + options.hint + '</small>' : '') + '</label>';
  }

  function textInput(parts, value, options) {
    options = options || {};
    return '<input type="text" class="' + (options.mono ? 'mono' : '') + '" data-path="' + path(parts) + '" data-kind="' + (options.kind || 'text') + '"'
      + (options.doc ? ' data-doc="' + options.doc + '"' : '') + (options.prune ? ' data-prune' : '')
      + ' value="' + esc(value == null ? '' : value) + '"' + (options.placeholder ? ' placeholder="' + esc(options.placeholder) + '"' : '')
      + ' spellcheck="false" autocomplete="off"' + (options.inputmode ? ' inputmode="' + options.inputmode + '"' : '') + '>';
  }

  function seg(act, choices, current, extra) {
    return '<div class="seg" role="group">' + choices.map(function (c) {
      return '<button type="button" data-act="' + act + '" data-value="' + esc(c[0]) + '"' + (extra || '') + ' aria-pressed="' + (String(current) === String(c[0])) + '">' + esc(c[1]) + '</button>';
    }).join('') + '</div>';
  }

  function stationOptions(selected, blank) {
    var ids = keys(stations()).sort(function (a, b) { return stationName(a).localeCompare(stationName(b)); });
    var html = blank ? '<option value="">' + esc(blank) + '</option>' : '';
    if (selected && !stations()[selected]) html += '<option value="' + esc(selected) + '" selected>' + esc(selected) + ' (unknown)</option>';
    return html + ids.map(function (id) {
      return '<option value="' + esc(id) + '"' + (id === selected ? ' selected' : '') + '>' + esc(stationName(id)) + '</option>';
    }).join('');
  }

  function searchBar(section, placeholder, button) {
    return '<div class="toolbar"><input type="search" class="search" data-filter="' + section + '" placeholder="' + esc(placeholder) + '" value="'
      + esc(state.filter[section] || '') + '" aria-label="' + esc(placeholder) + '" spellcheck="false" autocomplete="off">'
      + (button || '') + '</div>';
  }

  function crumb(label, act, extra) { return '<button type="button" class="crumb" data-act="' + act + '"' + (extra || '') + '>' + esc(label) + '</button>'; }

  // ---- Stations --------------------------------------------------------------------------------

  function stationsView() {
    var platforms = 0;
    keys(stations()).forEach(function (id) { platforms += keys(stations()[id].platforms).length; });
    return '<h2 class="title">Stations</h2><p class="lede">' + plural(keys(stations()).length, 'station') + ' with ' + plural(platforms, 'platform')
      + '. Choose one here or on the diagram.</p>'
      + searchBar('stations', 'Find a station', '<button type="button" class="button small" data-act="new" data-kind="station">New station</button>')
      + (state.creating === 'station' ? newStationForm() : '')
      + '<ul class="rows" id="list">' + stationRows() + '</ul>';
  }

  function stationRows() {
    var q = fold(state.filter.stations);
    var ids = keys(stations()).filter(function (id) { return !q || fold(stationName(id) + ' ' + id + ' ' + stationCode(id)).indexOf(q) >= 0; })
      .sort(function (a, b) { return stationName(a).localeCompare(stationName(b)); });
    if (!ids.length) return '<li class="empty-state">' + (keys(stations()).length ? 'No station matches.' : '<b>No stations yet</b>Make one with New station, or draft them from your signs with <code>/atcs import scan</code> in game.') + '</li>';
    var hidden = (doc().map && doc().map.stations) || {};
    return ids.map(function (id) {
      var s = stations()[id], count = keys(s.platforms).length;
      var bad = errorsUnder(['stations', id]).length || mentions('station', id).some(function (f) { return f.level === 'problem'; });
      var warn = !bad && mentions('station', id).some(function (f) { return f.level === 'warning'; });
      var tags = (hidden[id] && hidden[id].hidden ? '<span class="tag soft">hidden</span>' : '')
        + (hidden[id] && typeof hidden[id].column === 'number' ? '<span class="tag soft">pinned</span>' : '');
      return '<li><button type="button" class="row' + (state.open && state.open.id === id ? ' selected' : '') + '" data-act="open-station" data-id="' + esc(id) + '">'
        + roundel(id, 26) + '<span class="main"><span class="name">' + esc(stationName(id)) + '</span><span class="meta">' + esc(id) + ' · '
        + esc(stationCode(id)) + ' · ' + plural(count, 'platform') + '</span></span><span class="end">' + tags
        + '<span class="bullets">' + linesAt(id).map(function (l) { return bullet(l, 20); }).join('') + '</span>'
        + (bad ? '<span class="mark bad" title="Has problems"></span>' : warn ? '<span class="mark warn" title="Has warnings"></span>' : '') + '</span></button></li>';
    }).join('');
  }

  function newStationForm() {
    return '<div class="card" style="margin-bottom:14px"><div class="card-body" style="border-top:0">'
      + '<div class="grid">'
      + '<label class="field"><span>Name</span><input type="text" id="new-name" placeholder="Golden Beach St." data-enter="create-station" autocomplete="off"></label>'
      + '<label class="field"><span>Id</span><input type="text" id="new-id" class="mono" placeholder="from the name" data-enter="create-station" spellcheck="false" autocomplete="off"></label>'
      + '</div><p class="hint" style="margin:-6px 0 12px">The id is used in commands, signs and service stop lists and cannot be changed later. Lowercase letters, digits, - and _.</p>'
      + '<div class="form-row"><button type="button" class="button" data-act="create-station">Create station</button>'
      + '<button type="button" class="button secondary" data-act="cancel-new">Cancel</button></div></div></div>';
  }

  function stationView(id) {
    var s = stations()[id];
    if (!s) { state.open = null; return stationsView(); }
    var mapEntry = ((doc().map && doc().map.stations) || {})[id] || {};
    var html = crumb('Stations', 'close')
      + '<div class="entity-head"><span data-preview="roundel:' + esc(id) + '">' + roundel(id, 44) + '</span><div class="main"><h2 data-preview="name:' + esc(id) + '">'
      + esc(stationName(id)) + '</h2><div class="meta">' + esc(id) + '</div></div>'
      + '<button type="button" class="head-button" data-act="delete-station" data-id="' + esc(id) + '">Delete</button></div>'
      + '<div data-preview="errors:station|' + esc(id) + '">' + errorsBoxFor('station|' + id) + '</div>'
      + '<div class="grid">'
      + field('Name', textInput(['stations', id, 'name'], s.name, { placeholder: 'Passenger name' }), { wide: true, big: true })
      + field('Short code', textInput(['stations', id, 'code'], s.code, { kind: 'optional', placeholder: stationCode(id) + ' (automatic)' }),
        { hint: '<span data-preview="code:' + esc(id) + '">' + codeHint(id) + '</span>' })
      + field('Map position', textInput(['stations', id, 'location'], s.location, { kind: 'optional', mono: true, placeholder: 'world,x,y,z' }),
        { hint: 'Leave empty to use the middle of its platform signs.' })
      + '</div>'
      + '<div class="field"><span>Footprint</span>' + (s.footprint
        ? '<div class="inline"><div class="input mono" style="border-style:dashed">' + esc(s.footprint) + '</div><button type="button" class="button small secondary" data-act="clear-footprint" data-id="' + esc(id) + '">Clear</button></div>'
        : '<p class="hint" style="margin:0">None. In game, select the station with <code>/atcs wand</code> and run <code>/atcs station footprint ' + esc(id) + '</code>.</p>')
      + '</div>';

    html += '<div class="group-head"><span>Platforms</span><button type="button" class="button small" data-act="add-platform" data-id="' + esc(id) + '">Add platform</button></div>';
    var pids = keys(s.platforms);
    if (!pids.length) html += '<p class="empty">No platforms yet. Add one, then give it the sign trains stop at.</p>';
    pids.forEach(function (pid) {
      var p = s.platforms[pid] || {};
      var triggers = p.triggers || [];
      html += '<div class="platform-box"><header><span class="number">' + esc(pid) + '</span>'
        + '<input type="text" class="input" data-path="' + path(['stations', id, 'platforms', pid, 'name']) + '" data-kind="optional" value="' + esc(p.name || '') + '" placeholder="Platform ' + esc(pid) + ' (name shown to passengers)" autocomplete="off">'
        + '<button type="button" class="x" data-act="remove-platform" data-id="' + esc(id) + '" data-platform="' + esc(pid) + '" title="Remove platform ' + esc(pid) + '" aria-label="Remove platform">&times;</button></header>'
        + '<div class="body"><ul class="coords">' + (triggers.length ? triggers.map(function (t, i) {
          return '<li><span class="value">' + esc(t) + '</span><button type="button" class="x" data-act="remove-trigger" data-id="' + esc(id) + '" data-platform="' + esc(pid) + '" data-index="' + i + '" aria-label="Remove this sign">&times;</button></li>';
        }).join('') : '<li><span class="value" style="font-family:var(--font);color:var(--muted)">No sign yet: trains here are not seen.</span></li>') + '</ul>'
        + '<div class="inline"><input type="text" class="input mono" id="trigger-' + esc(pid) + '" placeholder="world,x,y,z of a sign" data-enter="add-trigger" data-id="' + esc(id) + '" data-platform="' + esc(pid) + '" spellcheck="false" autocomplete="off">'
        + '<button type="button" class="button small secondary" data-act="add-trigger" data-id="' + esc(id) + '" data-platform="' + esc(pid) + '">Add sign</button></div>'
        + '<p class="hint">Or look at the sign in game and run <code>/atcs station bind ' + esc(id) + ' ' + esc(pid) + '</code>.</p></div></div>';
    });
    var seen = ((context().observed || {})[id] || []).filter(function (t) {
      return !pids.some(function (pid) { return ((s.platforms[pid] || {}).triggers || []).indexOf(t) >= 0; });
    });
    if (seen.length) {
      html += '<div class="notice"><div class="main"><b>Also reporting here: ' + plural(seen.length, 'atcs station sign') + '</b>'
        + seen.map(function (t) { return '<span class="mono">' + esc(t) + '</span>'; }).join(', ') + '. They mark platforms themselves and need no binding.</div></div>';
    }

    html += '<div class="group-head"><span>Trains calling here</span></div>';
    var calls = [];
    allServices().forEach(function (entry) {
      (entry.service.stops || []).forEach(function (text) {
        var stop = parseStop(text);
        if (stop.station === id) calls.push({ entry: entry, stop: stop });
      });
    });
    html += calls.length ? '<ul class="rows">' + calls.map(function (c) {
      var dest = destination(c.entry.service);
      return '<li><button type="button" class="row" data-act="open-service" data-line="' + esc(c.entry.line) + '" data-service="' + esc(c.entry.id) + '">'
        + bullet(c.entry.line, 22) + '<span class="main"><span class="name">' + esc(c.entry.service.name || c.entry.id) + (dest ? ' to ' + esc(dest) : '') + '</span>'
        + '<span class="meta">' + esc(c.entry.key) + '</span></span><span class="end">'
        + (c.stop.pass ? '<span class="tag soft">runs through</span>' : '<span class="platform">' + esc(c.stop.platform ? 'P' + c.stop.platform : 'any') + '</span>')
        + '</span></button></li>';
    }).join('') + '</ul>' : '<p class="empty">No service lists this station. Add it to a service under Lines.</p>';

    html += '<div class="group-head"><span>On the diagram</span></div>'
      + '<div class="field"><span>Name goes</span>' + seg('station-label', [['', 'Where it fits'], ['left', 'Left'], ['right', 'Right'], ['above', 'Above'], ['below', 'Below']], mapEntry.label || '', ' data-id="' + esc(id) + '"') + '</div>'
      + '<div class="field"><span>Place</span>' + (typeof mapEntry.column === 'number'
        ? '<div class="inline"><div class="input">Pinned to column ' + mapEntry.column + ', row ' + mapEntry.row + '</div><button type="button" class="button small secondary" data-act="unpin" data-id="' + esc(id) + '">Unpin</button></div>'
        : '<p class="hint" style="margin:0">Placed automatically from where it is in the world. Drag it on the diagram to pin it.</p>') + '</div>'
      + '<div class="field"><span>Shown</span>' + seg('station-hidden', [['shown', 'On the diagram'], ['hidden', 'Hidden (a depot)']], mapEntry.hidden ? 'hidden' : 'shown', ' data-id="' + esc(id) + '"') + '</div>';

    var walks = (doc().transfers || []).map(function (t, i) { return { t: t, i: i }; }).filter(function (w) { return (w.t.between || []).indexOf(id) >= 0; });
    html += '<div class="group-head"><span>Walking transfers</span><button type="button" class="button small" data-act="add-transfer" data-from="' + esc(id) + '">Add a walk</button></div>';
    html += walks.length ? '<ul class="rows">' + walks.map(function (w) {
      var other = w.t.between[0] === id ? w.t.between[1] : w.t.between[0];
      return '<li><button type="button" class="row" data-act="section" data-section="transfers"><span class="main"><span class="name">Walk to ' + esc(stationName(other)) + '</span>'
        + '<span class="meta">' + (w.t.walk != null ? esc(w.t.walk) + ' s' : 'time estimated from the distance') + ' · ' + (w.t.outside === false ? 'inside the station' : 'outside') + '</span></span></button></li>';
    }).join('') + '</ul>' : '<p class="empty">None.</p>';
    return html;
  }

  function codeHint(id) {
    var code = stationCode(id);
    return 'Sign variables: <code>%' + esc(code) + '.1t%</code> next time, <code>%' + esc(code) + '.1d%</code> destination.';
  }

  function destination(service) {
    var stops = (service && service.stops) || [];
    if (!stops.length) return '';
    if (service.circular) return '';
    for (var i = stops.length - 1; i >= 0; i--) { var s = parseStop(stops[i]); if (!s.pass) return stationName(s.station); }
    return '';
  }

  // ---- Lines and services ----------------------------------------------------------------------

  function linesView() {
    return '<h2 class="title">Lines</h2><p class="lede">Each line has a colour and a badge, and runs one or more services: a direction, an express, a loop.</p>'
      + searchBar('lines', 'Find a line or service', '<button type="button" class="button small" data-act="new" data-kind="line">New line</button>')
      + (state.creating === 'line' ? newLineForm() : '')
      + '<ul class="rows" id="list">' + lineRows() + '</ul>';
  }

  function lineRows() {
    var q = fold(state.filter.lines);
    var ids = keys(lines()).filter(function (id) {
      if (!q) return true;
      var text = id + ' ' + lineView(id).name + ' ' + keys(services(id)).map(function (s) { return s + ' ' + (services(id)[s].name || ''); }).join(' ');
      return fold(text).indexOf(q) >= 0;
    });
    if (!ids.length) return '<li class="empty-state">' + (keys(lines()).length ? 'No line matches.' : '<b>No lines yet</b>Make one with New line.') + '</li>';
    return ids.map(function (id) {
      var v = lineView(id), count = keys(services(id)).length;
      var bad = errorsUnder(['lines', id]).length > 0;
      return '<li><button type="button" class="row" data-act="open-line" data-id="' + esc(id) + '">' + bullet(id, 30)
        + '<span class="main"><span class="name">' + esc(v.name) + '</span><span class="meta">' + esc(id) + ' · ' + plural(count, 'service') + ' · '
        + keys(services(id)).map(function (s) { return esc(services(id)[s].name || s); }).join(', ') + '</span></span><span class="end">'
        + (v.suspended ? '<span class="tag bad">suspended</span>' : '') + '<span class="swatch" style="background:' + esc(v.color) + '"></span>'
        + (bad ? '<span class="mark bad"></span>' : '') + '</span></button></li>';
    }).join('');
  }

  function newLineForm() {
    return '<div class="card" style="margin-bottom:14px"><div class="card-body" style="border-top:0"><div class="grid">'
      + '<label class="field wide"><span>Name</span><input type="text" id="new-name" placeholder="Line B Riverside" data-enter="create-line" autocomplete="off"></label>'
      + '<label class="field"><span>Id</span><input type="text" id="new-id" class="mono" placeholder="b" data-enter="create-line" spellcheck="false" autocomplete="off"></label>'
      + '<label class="field"><span>Badge</span><input type="text" id="new-badge" placeholder="B" maxlength="4" data-enter="create-line" autocomplete="off"></label>'
      + '<label class="field wide"><span>Colour</span><div class="color-field"><input type="color" id="new-color" value="#1e88e5" style="width:100%;height:42px;border-right:2px solid var(--rule)"></div></label>'
      + '</div><div class="form-row"><button type="button" class="button" data-act="create-line">Create line</button>'
      + '<button type="button" class="button secondary" data-act="cancel-new">Cancel</button></div></div></div>';
  }

  function colorField(parts, value, extra) {
    var hex = /^#[0-9a-f]{6}$/i.test(value || '') ? value : '#9E9E9E';
    return '<div class="color-field"><input type="color" data-path="' + path(parts) + '" data-kind="color" value="' + esc(hex.toLowerCase()) + '"' + (extra || '') + '>'
      + '<input type="text" class="input" data-path="' + path(parts) + '" data-kind="color" value="' + esc(value || '') + '" spellcheck="false" autocomplete="off"' + (extra || '') + '></div>';
  }

  function lineView2(id) {
    var l = lines()[id];
    if (!l) { state.open = null; return linesView(); }
    var mapColor = doc().map && doc().map.lines && doc().map.lines[id];
    var html = crumb('Lines', 'close')
      + '<div class="entity-head"><span data-preview="bullet:' + esc(id) + '">' + bullet(id, 40) + '</span><div class="main"><h2 data-preview="line-name:' + esc(id) + '">'
      + esc(lineView(id).name) + '</h2><div class="meta">' + esc(id) + '</div></div>'
      + '<button type="button" class="head-button" data-act="delete-line" data-id="' + esc(id) + '">Delete</button></div>'
      + '<div data-preview="errors:line|' + esc(id) + '">' + errorsBoxFor('line|' + id) + '</div>'
      + '<div class="grid">'
      + field('Name', textInput(['lines', id, 'name'], l.name, { placeholder: 'Line A Upperground' }), { wide: true, big: true })
      + field('Badge', textInput(['lines', id, 'short-name'], l['short-name'], { kind: 'optional', placeholder: id.toUpperCase() }), { hint: 'One to four characters, in the route bullet.' })
      + field('Colour', colorField(['lines', id, 'color'], l.color))
      + '</div>'
      + '<div class="field"><span>Website diagram</span><label class="check"><input type="checkbox" data-act-change="diagram-color" data-id="' + esc(id) + '"' + (mapColor ? ' checked' : '') + '> Draw it in a different colour on the diagram</label>'
      + (mapColor ? '<div style="margin-top:8px">' + colorField(['map', 'lines', id], mapColor, ' data-prune') + '</div>' : '')
      + '<small class="hint">For a badge colour too light to read as a line on paper.</small></div>'
      + '<div class="field"><span>Service</span>' + seg('line-status', [['running', 'Running'], ['suspended', 'Suspended']], l.suspended !== undefined ? 'suspended' : 'running', ' data-id="' + esc(id) + '"') + '</div>';
    if (l.suspended !== undefined) {
      html += field('Notice shown to riders', textInput(['lines', id, 'suspended'], l.suspended, { placeholder: 'Signal failure at Main St.' }),
        { hint: 'Boards show Suspended, and the boss bar of this line\'s trains shows the notice.' });
    }
    html += '<div class="group-head"><span>Services</span><button type="button" class="button small" data-act="new" data-kind="service">New service</button></div>';
    if (state.creating === 'service') {
      html += '<div class="card" style="margin-bottom:12px"><div class="card-body" style="border-top:0"><div class="grid">'
        + '<label class="field"><span>Name</span><input type="text" id="new-name" placeholder="Local" data-enter="create-service" autocomplete="off"></label>'
        + '<label class="field"><span>Id</span><input type="text" id="new-id" class="mono" placeholder="north" data-enter="create-service" spellcheck="false" autocomplete="off"></label>'
        + '</div><div class="form-row"><button type="button" class="button" data-act="create-service" data-line="' + esc(id) + '">Create service</button>'
        + '<button type="button" class="button secondary" data-act="cancel-new">Cancel</button></div></div></div>';
    }
    var sids = keys(l.services);
    html += sids.length ? '<ul class="cards">' + sids.map(function (sid) {
      var svc = l.services[sid], stops = svc.stops || [], dest = destination(svc);
      var tags = (svc.circular ? '<span class="tag soft">loop</span>' : '') + (svc['continues-as'] ? '<span class="tag soft">continues as ' + esc(svc['continues-as']) + '</span>' : '')
        + (svc.fleet ? '<span class="tag soft">fleet of ' + esc(svc.fleet.trains) + '</span>' : '');
      return '<li class="card"><button type="button" class="row" data-act="open-service" data-line="' + esc(id) + '" data-service="' + esc(sid) + '">'
        + '<span class="main"><span class="name">' + esc(svc.name || sid) + (dest ? ' to ' + esc(dest) : '') + '</span><span class="meta">' + esc(id + '/' + sid) + ' · '
        + plural(stops.filter(function (t) { return !parseStop(t).pass; }).length, 'stop') + '</span>' + strip(id, stops) + '</span><span class="end">' + tags + '</span></button></li>';
    }).join('') + '</ul>' : '<p class="empty">No services yet. A service is a direction trains run in, with its stops.</p>';
    return html;
  }

  function strip(lineId, stops) {
    if (stops.length < 2) return '';
    return '<span class="strip" style="--line:' + esc(lineView(lineId).color) + '">' + stops.map(function (t, i) {
      return (i ? '<i></i>' : '') + '<b class="' + (parseStop(t).pass ? 'pass' : '') + '" title="' + esc(stationName(parseStop(t).station)) + '"></b>';
    }).join('') + '</span>';
  }

  function serviceView(lineId, sid) {
    var svc = services(lineId)[sid];
    if (!svc) { state.open = lines()[lineId] ? { kind: 'line', id: lineId } : null; return state.open ? lineView2(lineId) : linesView(); }
    var base = ['lines', lineId, 'services', sid];
    var stops = svc.stops || [];
    var color = lineView(lineId).color;
    var html = crumb(lineView(lineId).name, 'open-line', ' data-id="' + esc(lineId) + '"')
      + '<div class="entity-head">' + bullet(lineId, 40) + '<div class="main"><h2 data-preview="service-name:' + esc(lineId + '/' + sid) + '">' + esc(svc.name || sid) + '</h2>'
      + '<div class="meta">' + esc(lineId + '/' + sid) + '</div></div>'
      + '<button type="button" class="head-button" data-act="delete-service" data-line="' + esc(lineId) + '" data-service="' + esc(sid) + '">Delete</button></div>'
      + '<div data-preview="errors:service|' + esc(lineId) + '|' + esc(sid) + '">' + errorsBoxFor('service|' + lineId + '|' + sid) + '</div>'
      + '<div class="grid">'
      + field('Name', textInput(base.concat('name'), svc.name, { placeholder: 'Local' }), { big: true, hint: 'Shown on boards and the boss bar: Local, Express.' })
      + '<label class="field"><span>Continues as</span><select data-act-change="continues" data-line="' + esc(lineId) + '" data-service="' + esc(sid) + '">'
      + '<option value="">Nothing (the trip ends)</option>' + allServices().filter(function (e) { return e.key !== lineId + '/' + sid; }).map(function (e) {
        var current = svc['continues-as'] ? (String(svc['continues-as']).indexOf('/') >= 0 ? svc['continues-as'] : lineId + '/' + svc['continues-as']) : '';
        return '<option value="' + esc(e.key) + '"' + (current === e.key ? ' selected' : '') + '>' + esc(lineView(e.line).short + ' ' + (e.service.name || e.id) + ' (' + e.key + ')') + '</option>';
      }).join('') + '</select><small class="hint">At its last stop the train carries on as this service.</small></label>'
      + field('Speed limit', textInput(base.concat('speed'), svc.speed == null ? '' : svc.speed, { kind: 'number', inputmode: 'decimal', placeholder: 'as the train is' }),
        { hint: 'Blocks per tick, set on trains when they take up the service. An express can run faster than a local on shared track.' })
      + field('Timetable', textInput(base.concat('timetable'), (svc.timetable || []).join(' '), { kind: 'list', placeholder: ':00 :30' }),
        { hint: 'Minutes past the hour trains leave the first stop, at its station sign. Empty: as soon as the dwell is over.' })
      + '</div>'
      + '<div class="field"><span>Shape</span>' + seg('service-circular', [['line', 'End to end'], ['loop', 'A loop']], svc.circular ? 'loop' : 'line', ' data-line="' + esc(lineId) + '" data-service="' + esc(sid) + '"') + '</div>';

    html += '<div class="group-head"><span>Stops (' + stops.length + ')</span><span style="display:flex;gap:6px">'
      + '<button type="button" class="button small secondary" data-act="reverse-stops" data-line="' + esc(lineId) + '" data-service="' + esc(sid) + '"' + (stops.length < 2 ? ' disabled' : '') + '>Reverse</button>'
      + '<button type="button" class="button small secondary" data-act="make-return" data-line="' + esc(lineId) + '" data-service="' + esc(sid) + '"' + (stops.length < 2 ? ' disabled' : '') + '>Make the return service</button></span></div>';
    html += '<ol class="stops' + (svc.circular ? ' circular' : '') + '" id="stops" style="--line:' + esc(color) + '" data-line="' + esc(lineId) + '" data-service="' + esc(sid) + '">';
    stops.forEach(function (text, i) {
      var stop = parseStop(text);
      var st = stations()[stop.station];
      var interchange = linesAt(stop.station).length > 1;
      var end = !svc.circular && (i === 0 || i === stops.length - 1);
      var platformOptions = '<option value="">Any platform</option>' + keys(st && st.platforms).map(function (pid) {
        return '<option value="' + esc(pid) + '"' + (stop.platform === pid ? ' selected' : '') + '>Platform ' + esc(pid) + '</option>';
      }).join('') + (stop.platform && !(st && st.platforms && st.platforms[stop.platform]) ? '<option value="' + esc(stop.platform) + '" selected>' + esc(stop.platform) + ' (unknown)</option>' : '');
      html += '<li class="stop' + (stop.pass ? ' pass' : '') + (end ? ' end' : '') + (interchange ? ' interchange' : '') + '" data-index="' + i + '">'
        + '<button type="button" class="handle" aria-label="Move ' + esc(stationName(stop.station)) + ' (arrow keys)" title="Drag to reorder"></button>'
        + '<span class="rail-cell"><span class="dot"></span></span>'
        + '<select class="input station" data-act-change="stop-station" data-index="' + i + '" aria-label="Station">' + stationOptions(stop.station) + '</select>'
        + '<select class="input platform-select" data-act-change="stop-platform" data-index="' + i + '" aria-label="Platform"' + (stop.pass ? ' disabled' : '') + '>' + platformOptions + '</select>'
        + '<button type="button" class="toggle" data-act="stop-pass" data-index="' + i + '" aria-pressed="' + stop.pass + '" title="Pass: trains run through without stopping, and riders hear Passing">Pass</button>'
        + '<button type="button" class="icon-button insert" data-act="stop-insert" data-index="' + i + '" title="Add a stop after this one" aria-label="Add a stop after">+</button>'
        + '<button type="button" class="x" data-act="stop-remove" data-index="' + i + '" aria-label="Remove this stop">&times;</button></li>';
    });
    html += '</ol><div class="stop-add"><span></span><span class="rail-cell"></span><select class="input" id="add-stop" aria-label="Station to add"><option value="">Add a stop at the end&hellip;</option>'
      + stationOptions('', '').replace(/ selected/g, '') + '</select><button type="button" class="button small" data-act="stop-add">Add</button></div>'
      + '<p class="hint">Drag the dots on the left to reorder, or focus them and use the arrow keys. Learned travel times are kept for the stops that stay.</p>';

    var match = svc.match || {};
    html += '<div class="group-head"><span>Which trains run it</span></div><p class="hint" style="margin:-4px 0 12px">Trains from its spawn signs run it; these rules catch other trains. Comma-separated; * matches anything.</p><div class="grid">'
      + field('TrainCarts destinations', textInput(base.concat('match', 'destinations'), (match.destinations || []).join(', '), { kind: 'list', prune: true, placeholder: 'NewVil2Main' }))
      + field('Train tags', textInput(base.concat('match', 'tags'), (match.tags || []).join(', '), { kind: 'list', prune: true, placeholder: 'a-local' }))
      + field('Train names', textInput(base.concat('match', 'train-names'), (match['train-names'] || []).join(', '), { kind: 'list', prune: true, placeholder: 'loc*' }), { wide: true })
      + '</div>';

    var signs = svc['spawn-signs'] || [];
    html += '<div class="field"><span>Spawn signs</span>' + (signs.length ? '<ul class="coords">' + signs.map(function (t, i) {
      return '<li><span class="value">' + esc(t) + '</span><button type="button" class="x" data-act="remove-spawn" data-line="' + esc(lineId) + '" data-service="' + esc(sid) + '" data-index="' + i + '" aria-label="Unbind this spawn sign">&times;</button></li>';
    }).join('') + '</ul>' : '') + '<small class="hint">' + (signs.length ? '' : 'None. ') + 'Bind one in game: look at the spawn sign and run <code>/atcs service spawn ' + esc(lineId + '/' + sid) + '</code>.</small></div>';

    var fleet = svc.fleet;
    html += '<div class="group-head"><span>Fleet</span></div><div class="field">' + seg('service-fleet', [['off', 'No fleet'], ['on', 'ATCS keeps trains running']], fleet ? 'on' : 'off', ' data-line="' + esc(lineId) + '" data-service="' + esc(sid) + '"')
      + '<small class="hint">For lines without spawn signs: ATCS spawns trains itself and keeps the number running.</small></div>';
    if (fleet) {
      var fb = base.concat('fleet');
      html += '<div class="grid three">'
        + field('Trains', textInput(fb.concat('trains'), fleet.trains, { kind: 'int', inputmode: 'numeric' }))
        + field('One every', textInput(fb.concat('every'), fleet.every, { placeholder: '01:30' }), { hint: 'mm:ss' })
        + field('Train', textInput(fb.concat('train'), fleet.train, { placeholder: '1m' }), { hint: 'TrainCarts spawn pattern' })
        + field('Spawn at', textInput(fb.concat('spawn'), fleet.spawn, { mono: true, placeholder: 'world,x,y,z' }), { wide: true })
        + field('Runs', textInput(fb.concat('hours'), fleet.hours, { kind: 'optional', placeholder: 'all day, or 06:00-23:00' }),
          { hint: 'Outside these hours trains are stabled at the end of their trip.' })
        + field('Off-peak trains', textInput(fb.concat('off-peak', 'trains'), fleet['off-peak'] ? fleet['off-peak'].trains : '', { kind: 'int', inputmode: 'numeric', placeholder: 'same' }))
        + field('Off-peak hours', textInput(fb.concat('off-peak', 'hours'), fleet['off-peak'] ? fleet['off-peak'].hours : '', { kind: 'optional', placeholder: '22:00-06:00' }))
        + '<label class="field"><span>Facing</span><select data-path="' + path(fb.concat('direction')) + '" data-kind="text">'
        + ['north', 'east', 'south', 'west'].map(function (d) { return '<option value="' + d + '"' + (fleet.direction === d ? ' selected' : '') + '>' + d.charAt(0).toUpperCase() + d.slice(1) + '</option>'; }).join('')
        + '</select></label></div>';
    }
    return html;
  }

  // ---- Boards ----------------------------------------------------------------------------------

  function boardsView() {
    var ids = keys(doc().boards);
    var q = fold(state.filter.boards);
    var shown = ids.filter(function (id) { var b = doc().boards[id]; return !q || fold(id + ' ' + stationName(b.station)).indexOf(q) >= 0; });
    return '<h2 class="title">Departure boards</h2><p class="lede">Ordinary signs that show the next departures, one departure per sign. '
      + 'Make one in game by looking at a sign: <code>/atcs board create &lt;id&gt; &lt;station&gt;</code>.</p>'
      + searchBar('boards', 'Find a board', '<button type="button" class="button small" data-act="new" data-kind="board">New board</button>')
      + (state.creating === 'board' ? '<div class="card" style="margin-bottom:14px"><div class="card-body" style="border-top:0"><div class="grid">'
        + '<label class="field"><span>Id</span><input type="text" id="new-id" class="mono" placeholder="main-east" data-enter="create-board" spellcheck="false" autocomplete="off"></label>'
        + '<label class="field"><span>Station</span><select id="new-station">' + stationOptions('') + '</select></label></div>'
        + '<div class="form-row"><button type="button" class="button" data-act="create-board">Create board</button><button type="button" class="button secondary" data-act="cancel-new">Cancel</button></div></div></div>' : '')
      + '<ul class="rows" id="list">' + (shown.length ? shown.map(function (id) {
        var b = doc().boards[id];
        var bad = errorsUnder(['boards', id]).length || mentions('board', id).some(function (f) { return f.level !== 'note'; });
        return '<li><button type="button" class="row" data-act="open-board" data-id="' + esc(id) + '"><span class="main"><span class="name">' + esc(id) + '</span>'
          + '<span class="meta">' + esc(stationName(b.station)) + (b.platforms && b.platforms.length ? ' · platform ' + esc(b.platforms.join(', ')) : ' · all platforms')
          + ' · ' + plural((b.signs || []).length, 'sign') + '</span></span><span class="end">' + (bad ? '<span class="mark warn"></span>' : '') + '</span></button></li>';
      }).join('') : '<li class="empty-state">' + (ids.length ? 'No board matches.' : '<b>No boards yet</b>') + '</li>') + '</ul>';
  }

  function boardView(id) {
    var b = doc().boards && doc().boards[id];
    if (!b) { state.open = null; return boardsView(); }
    var st = stations()[b.station] || {};
    var html = crumb('Boards', 'close')
      + '<div class="entity-head"><div class="main"><h2>' + esc(id) + '</h2><div class="meta">board at ' + esc(b.station) + '</div></div>'
      + '<button type="button" class="head-button" data-act="delete-board" data-id="' + esc(id) + '">Delete</button></div>'
      + '<div data-preview="errors:board|' + esc(id) + '">' + errorsBoxFor('board|' + id) + '</div>'
      + '<label class="field"><span>Station</span><select data-act-change="board-station" data-id="' + esc(id) + '">' + stationOptions(b.station) + '</select></label>'
      + '<div class="field"><span>Platforms shown</span><div style="display:flex;flex-wrap:wrap;gap:14px">'
      + '<label class="check"><input type="checkbox" data-act-change="board-all" data-id="' + esc(id) + '"' + (!b.platforms || !b.platforms.length ? ' checked' : '') + '> All</label>'
      + keys(st.platforms).map(function (pid) {
        return '<label class="check"><input type="checkbox" data-act-change="board-platform" data-id="' + esc(id) + '" data-platform="' + esc(pid) + '"'
          + ((b.platforms || []).indexOf(pid) >= 0 ? ' checked' : '') + '> Platform ' + esc(pid) + '</label>';
      }).join('') + '</div></div>'
      + '<div class="group-head"><span>Signs, first departure first</span></div><ul class="coords">'
      + (b.signs || []).map(function (t, i) {
        return '<li><span class="value">' + (i + 1) + '. ' + esc(t) + '</span>'
          + '<button type="button" class="icon-button" data-act="sign-up" data-id="' + esc(id) + '" data-index="' + i + '"' + (i ? '' : ' disabled') + ' aria-label="Earlier">&uarr;</button>'
          + '<button type="button" class="icon-button" data-act="sign-down" data-id="' + esc(id) + '" data-index="' + i + '"' + (i < b.signs.length - 1 ? '' : ' disabled') + ' aria-label="Later">&darr;</button>'
          + '<button type="button" class="x" data-act="remove-sign" data-id="' + esc(id) + '" data-index="' + i + '" aria-label="Remove this sign">&times;</button></li>';
      }).join('') + '</ul>'
      + '<div class="inline"><input type="text" class="input mono" id="board-sign" placeholder="world,x,y,z of an ordinary sign" data-enter="add-sign" data-id="' + esc(id) + '" spellcheck="false" autocomplete="off">'
      + '<button type="button" class="button small secondary" data-act="add-sign" data-id="' + esc(id) + '">Add sign</button></div>'
      + '<p class="hint">Or in game: look at the next sign and run <code>/atcs board add ' + esc(id) + '</code>.</p>';
    return html;
  }

  // ---- Transfers -------------------------------------------------------------------------------

  function transfersView() {
    var list = doc().transfers || [];
    var q = fold(state.filter.transfers);
    var html = '<h2 class="title">Walking transfers</h2><p class="lede">Walks between nearby stations: the trip planner uses them to change trains, '
      + 'and announcements name the lines a walk away. Leave the time empty to estimate it from the distance.</p>'
      + searchBar('transfers', 'Find by station', '<button type="button" class="button small" data-act="add-transfer">Add a walk</button>')
      + '<div data-preview="errors:transfers|">' + errorsBoxFor('transfers|') + '</div>';
    var shown = list.map(function (t, i) { return { t: t, i: i }; }).filter(function (w) {
      return !q || fold((w.t.between || []).map(stationName).join(' ')).indexOf(q) >= 0;
    });
    if (!shown.length) return html + '<p class="empty-state">' + (list.length ? 'No walk matches.' : '<b>No walks yet</b>Add one between two stations people walk between.') + '</p>';
    return html + '<ul class="cards">' + shown.map(function (w) {
      var t = w.t, between = t.between || ['', ''];
      return '<li class="card"><div class="walk-card">'
        + '<label class="field"><span>Between</span><select data-path="' + path(['transfers', w.i, 'between', 0]) + '" data-kind="text">' + stationOptions(between[0]) + '</select></label>'
        + '<span class="arrow" aria-hidden="true">&harr;</span>'
        + '<label class="field"><span>and</span><select data-path="' + path(['transfers', w.i, 'between', 1]) + '" data-kind="text">' + stationOptions(between[1]) + '</select></label>'
        + '<button type="button" class="x" data-act="remove-transfer" data-index="' + w.i + '" aria-label="Remove this walk" style="align-self:end;margin-bottom:6px">&times;</button></div>'
        + '<div class="walk-extra"><label class="field"><span>Walk, seconds</span>' + textInput(['transfers', w.i, 'walk'], t.walk, { kind: 'int', placeholder: 'estimated', inputmode: 'numeric' }) + '</label>'
        + seg('transfer-outside', [['outside', 'Outside'], ['inside', 'Inside the station']], t.outside === false ? 'inside' : 'outside', ' data-index="' + w.i + '"') + '</div></li>';
    }).join('') + '</ul>';
  }

  // ---- Fares -----------------------------------------------------------------------------------

  function faresView() {
    var f = state.faresDoc;
    if (!f) return '<h2 class="title">Fares</h2><p class="empty-state"><b>Fares are not available</b>The plugin did not load fares.yml.</p>';
    var formula = f.formula || {};
    var cur = f.currency || '$';
    var html = '<h2 class="title">Fares</h2><p class="lede">The highest layer that applies wins: a fare set for the pair, then the zones, then the formula. '
      + 'Check one in game with <code>/atcs fare check &lt;from&gt; &lt;to&gt;</code>.</p>'
      + '<div data-preview="errors:fares|">' + errorsBoxFor('fares|') + '</div>'
      + (state.fares.loadErrors && state.fares.loadErrors.length ? '<div class="notice bad"><div class="main"><b>fares.yml has errors on disk</b>' + esc(state.fares.loadErrors.join(' · ')) + '</div></div>' : '')
      + '<div class="grid three">' + field('Currency', textInput(['currency'], f.currency, { doc: 'fares', placeholder: '$' })) + '</div>'
      + '<div class="group-head"><span>3. Formula</span></div><div class="grid three">'
      + [['flat', 'Every trip'], ['per-stop', 'Per stop'], ['per-change', 'Per change'], ['round-to', 'Round to'], ['minimum', 'At least']].map(function (k) {
        return field(k[1] + ' (' + cur + ')', '<input type="text" inputmode="decimal" data-doc="fares" data-path="' + path(['formula', k[0]]) + '" data-kind="number" value="' + esc(numberText(formula[k[0]])) + '" placeholder="0">');
      }).join('') + '</div>'
      + '<div class="field"><span>Distance bands</span><table class="table"><thead><tr><th>Up to, blocks</th><th>Adds (' + esc(cur) + ')</th><th></th></tr></thead><tbody>'
      + (formula['distance-bands'] || []).map(function (band, i) {
        return '<tr><td><input type="text" class="input" inputmode="decimal" data-doc="fares" data-path="' + path(['formula', 'distance-bands', i, 'up-to']) + '" data-kind="number" value="' + esc(numberText(band['up-to'])) + '" placeholder="any distance"></td>'
          + '<td><input type="text" class="input" inputmode="decimal" data-doc="fares" data-path="' + path(['formula', 'distance-bands', i, 'fare']) + '" data-kind="number" value="' + esc(numberText(band.fare)) + '"></td>'
          + '<td class="tiny"><button type="button" class="x" data-act="remove-band" data-index="' + i + '" aria-label="Remove band">&times;</button></td></tr>';
      }).join('') + '</tbody></table><button type="button" class="button small secondary" data-act="add-band">Add a band</button>'
      + '<small class="hint">The first band whose distance fits applies; leave the distance empty on the last one for any distance.</small></div>';

    var zones = f.zones || {};
    html += '<div class="group-head"><span>2. Zones</span></div><table class="table"><thead><tr><th>Station</th><th>Zone</th></tr></thead><tbody>'
      + keys(stations()).sort(function (a, b) { return stationName(a).localeCompare(stationName(b)); }).map(function (id) {
        return '<tr><td class="name">' + esc(stationName(id)) + '</td><td class="narrow"><input type="text" class="input" data-doc="fares" data-path="' + path(['zones', id]) + '" data-kind="optional" value="' + esc(zones[id] == null ? '' : zones[id]) + '" placeholder="none"></td></tr>';
      }).join('') + '</tbody></table>';
    var zoneFares = f['zone-fares'] || {};
    html += '<div class="field"><span>Between zones</span><table class="table"><thead><tr><th>Zones</th><th>Fare (' + esc(cur) + ')</th><th></th></tr></thead><tbody>'
      + keys(zoneFares).map(function (k) {
        return '<tr><td class="name">' + esc(k.replace('-', ' and ')) + '</td><td class="narrow"><input type="text" class="input" inputmode="decimal" data-doc="fares" data-path="' + path(['zone-fares', k]) + '" data-kind="number" value="' + esc(numberText(zoneFares[k])) + '"></td>'
          + '<td class="tiny"><button type="button" class="x" data-act="remove-zone-fare" data-key="' + esc(k) + '" aria-label="Remove">&times;</button></td></tr>';
      }).join('') + '</tbody></table><div class="inline"><input type="text" class="input" id="zone-a" placeholder="Zone" style="max-width:110px">'
      + '<input type="text" class="input" id="zone-b" placeholder="Zone" style="max-width:110px"><input type="text" class="input" id="zone-fare" inputmode="decimal" placeholder="Fare" style="max-width:110px">'
      + '<button type="button" class="button small secondary" data-act="add-zone-fare">Add</button></div></div>';

    var pairs = f.pairs || {};
    var q = fold(state.filter.fares);
    var pairKeys = keys(pairs).filter(function (k) { return !q || fold(k.split('|').map(stationName).join(' ')).indexOf(q) >= 0; });
    var shown = pairKeys.slice(0, 150);
    html += '<div class="group-head"><span>1. Fares set for a pair (' + keys(pairs).length + ')</span></div>'
      + searchBar('fares', 'Find pairs by station')
      + '<table class="table"><thead><tr><th>Between</th><th>Fare (' + esc(cur) + ')</th><th>Kept</th><th></th></tr></thead><tbody id="list">'
      + shown.map(function (k) {
        var v = pairs[k], kept = v && typeof v === 'object';
        var fare = kept ? v.fare : v, parts = k.split('|');
        return '<tr><td class="name">' + esc(stationName(parts[0])) + ' &ndash; ' + esc(stationName(parts[1])) + '</td>'
          + '<td class="narrow"><input type="text" class="input" inputmode="decimal" data-doc="fares" data-path="' + path(kept ? ['pairs', k, 'fare'] : ['pairs', k]) + '" data-kind="number" value="' + esc(numberText(fare)) + '"></td>'
          + '<td class="tiny"><input type="checkbox" data-act-change="pair-keep" data-key="' + esc(k) + '"' + (kept ? ' checked' : '') + ' title="Set by hand: /atcs fare generate leaves it alone"></td>'
          + '<td class="tiny"><button type="button" class="x" data-act="remove-pair" data-key="' + esc(k) + '" aria-label="Remove">&times;</button></td></tr>';
      }).join('') + '</tbody></table>'
      + (pairKeys.length > shown.length ? '<p class="hint">' + (pairKeys.length - shown.length) + ' more; narrow the search to see them.</p>' : '')
      + '<div class="inline" style="margin-top:8px"><select class="input" id="pair-a">' + stationOptions('', 'From') + '</select><select class="input" id="pair-b">' + stationOptions('', 'To') + '</select>'
      + '<input type="text" class="input" id="pair-fare" inputmode="decimal" placeholder="Fare" style="max-width:100px"><button type="button" class="button small secondary" data-act="add-pair">Add</button></div>'
      + '<p class="hint">Kept pairs were set by hand; <code>/atcs fare generate</code> never replaces them.</p>';
    return html;
  }

  // ---- Diagram ---------------------------------------------------------------------------------

  function diagramView() {
    var entries = (doc().map && doc().map.stations) || {};
    var arrangement = state.layout && state.layout.detail ? state.layout.detail : null;
    var html = '<h2 class="title">Diagram</h2><p class="lede">The website draws the network as a subway diagram from where stations are in the world. '
      + 'Drag a station on the map to pin it to a cell; the adjustments are saved in network.yml under <code>map:</code>.</p>'
      + '<div data-preview="errors:map|">' + errorsBoxFor('map|') + '</div>'
      + '<div class="group-head"><span>Layout for every visitor</span></div>'
      + '<p class="hint" style="margin:0 0 10px">' + (arrangement ? 'Grid of ' + arrangement.columns + ' columns and ' + arrangement.rows + ' rows. ' : '')
      + (context().layout ? 'A saved layout exists; browsers start from it.' : 'No layout saved yet: each browser works one out and keeps it.') + '</p>'
      + '<div class="form-row"><button type="button" class="button" data-act="save-layout">Save this layout for everyone</button>'
      + '<button type="button" class="button secondary" data-act="fresh-layout">Lay out from scratch</button></div>'
      + '<p class="hint">Saving writes <code>data/map-layout.yml</code>, so adding a station later moves that station, not the whole map.</p>';

    var ids = keys(entries);
    html += '<div class="group-head"><span>Adjusted stations</span></div>';
    html += ids.length ? '<ul class="rows">' + ids.map(function (id) {
      var e = entries[id], what = [];
      if (typeof e.column === 'number') what.push('pinned to column ' + e.column + ', row ' + e.row);
      if (e.label) what.push('name ' + (e.label === 'above' || e.label === 'below' ? e.label : 'on the ' + e.label));
      if (e.hidden) what.push('hidden');
      return '<li><div class="row" style="cursor:default"><span class="main"><span class="name"><a class="link" data-act="open-station" data-id="' + esc(id) + '">' + esc(stationName(id)) + '</a></span>'
        + '<span class="meta">' + esc(what.join(' · ')) + '</span></span><span class="end">'
        + (typeof e.column === 'number' ? '<button type="button" class="button small secondary" data-act="unpin" data-id="' + esc(id) + '">Unpin</button>' : '')
        + '<button type="button" class="button small secondary" data-act="clear-map" data-id="' + esc(id) + '">Reset</button></span></div></li>';
    }).join('') + '</ul>' : '<p class="empty">None: every station is placed and labelled automatically.</p>';

    var clashes = [];
    if (state.layout) {
      var seen = {};
      keys(state.layout.stations).forEach(function (id) {
        var s = state.layout.stations[id], k = Math.round(s.u) + ',' + Math.round(s.v);
        if (seen[k]) clashes.push([seen[k], id]); else seen[k] = id;
      });
    }
    if (clashes.length) {
      html += '<div class="notice warn" style="margin-top:14px"><div class="main"><b>Stations on the same spot</b>'
        + clashes.map(function (c) { return esc(stationName(c[0])) + ' and ' + esc(stationName(c[1])); }).join('; ') + '. Pin one of them elsewhere.</div></div>';
    }
    html += '<div class="group-head"><span>Line colours on the diagram</span></div><ul class="rows">' + keys(lines()).map(function (id) {
      var own = doc().map && doc().map.lines && doc().map.lines[id];
      return '<li><div class="row" style="cursor:default">' + bullet(id, 26) + '<span class="main"><span class="name">' + esc(lineView(id).name) + '</span>'
        + '<span class="meta">' + (own ? 'drawn in ' + esc(own) : 'drawn in its badge colour ' + esc(lineView(id).color)) + '</span></span>'
        + '<span class="end"><button type="button" class="button small secondary" data-act="open-line" data-id="' + esc(id) + '">Change</button></span></div></li>';
    }).join('') + '</ul>';
    return html;
  }

  // ---- Displays ------------------------------------------------------------------------------

  var KINDS = { roadmap: 'Roadmap', logo: 'Logo', board: 'Departure board picture', map: 'Network map', status: 'Service status', hologram: 'Hologram board' };

  function displaysView() {
    var all = doc().displays || {};
    var ids = keys(all);
    var html = '<h2 class="title">Displays</h2><p class="lede">Pictures in item frames and hologram boards. They are placed in the world, '
      + 'so they are put up and taken down in game: <code>/atcs wand</code>, then <code>/atcs display roadmap</code>, <code>logo</code>, <code>board</code>, <code>map</code>, <code>status</code> or <code>hologram</code>.</p>';
    if (!ids.length) return html + '<p class="empty-state"><b>No displays yet</b>Hang item frames on a wall, select them with the wand and choose what they show.</p>';
    return html + '<ul class="cards">' + ids.map(function (id) {
      var d = all[id], kind = KINDS[d.kind] || d.kind;
      var where = d.at ? 'floating at ' + d.at : d.frames && d.frames.length ? (d.columns || 1) + ' x ' + (d.rows || 1) + ' frames at ' + d.frames[0].at : d.maps ? 'hand-held map' : '';
      var about = [d.service ? d.service : '', d.station ? stationName(d.station) : '', d.platforms && d.platforms.length ? 'platform ' + d.platforms.join(', ') : ''].filter(Boolean).join(' · ');
      var command = '/atcs display remove ' + id;
      return '<li class="card"><div class="row" style="cursor:default"><span class="main"><span class="name">' + esc(kind) + (about ? ': ' + esc(about) : '') + '</span>'
        + '<span class="meta">' + esc(id) + ' · ' + esc(where) + '</span></span><span class="end">'
        + '<button type="button" class="button small secondary" data-act="copy" data-text="' + esc(command) + '" title="Copy the command that takes it down">Copy remove command</button></span></div></li>';
    }).join('') + '</ul>';
  }

  // ---- Settings (config files) ---------------------------------------------------------------

  function settingsView() {
    var c = state.config;
    if (c.saved === null && !c.loading) loadConfig(c.name);
    var html = '<h2 class="title">Settings</h2><p class="lede">The plugin\'s configuration files, as on disk. Saving checks the YAML, writes the file and reloads ATCS '
      + '(config, messages and network.yml are read again).</p>'
      + '<div class="field">' + seg('config-file', [['config.yml', 'config.yml'], ['messages.yml', 'messages.yml']], c.name) + '</div>';
    if (c.loading || c.saved === null) return html + '<p class="empty">Loading ' + esc(c.name) + '&hellip;</p>';
    html += '<div class="code"><pre class="gutter" id="gutter" aria-hidden="true"></pre><textarea id="code" spellcheck="false" autocapitalize="off" autocomplete="off" aria-label="' + esc(c.name) + '">' + esc(c.text) + '</textarea></div>'
      + '<div class="form-row" style="margin-top:10px"><button type="button" class="button" data-act="save-config"' + (configDirty() ? '' : ' disabled') + '>Save and reload</button>'
      + '<button type="button" class="button secondary" data-act="revert-config"' + (configDirty() ? '' : ' disabled') + '>Revert</button></div>';
    if (c.problems) {
      html += c.problems.length ? '<div class="problems-box" style="margin-top:14px"><header>Reloaded, with ' + plural(c.problems.length, 'remark') + '</header><ul class="findings">'
        + c.problems.map(function (p) { return '<li class="warning">' + esc(p) + '</li>'; }).join('') + '</ul></div>'
        : '<div class="notice" style="margin-top:14px"><div class="main"><b>Saved and reloaded</b>No problems reported.</div></div>';
    }
    return html;
  }

  function loadConfig(name) {
    state.config.loading = true;
    request('GET', '/api/config?name=' + encodeURIComponent(name)).then(function (data) {
      state.config = { name: name, text: data.text, saved: data.text, version: data.version, problems: null, loading: false };
      if (state.section === 'settings') renderPane();
    }).catch(function (error) {
      state.config.loading = false;
      toast(error.message, 'bad');
    });
  }

  function wireCode() {
    var area = $('code'), gutter = $('gutter');
    if (!area) return;
    function numbers() {
      var count = area.value.split('\n').length, text = '';
      for (var i = 1; i <= count; i++) text += i + '\n';
      gutter.textContent = text;
    }
    numbers();
    area.addEventListener('input', function () {
      state.config.text = area.value;
      numbers();
      var dirtyNow = configDirty();
      Array.prototype.forEach.call(document.querySelectorAll('[data-act="save-config"],[data-act="revert-config"]'), function (b) { b.disabled = !dirtyNow; });
      renderRail();
    });
    area.addEventListener('scroll', function () { gutter.scrollTop = area.scrollTop; });
    area.addEventListener('keydown', function (event) {
      if (event.key === 'Tab' && !event.shiftKey) {
        event.preventDefault();
        var start = area.selectionStart, end = area.selectionEnd;
        area.value = area.value.slice(0, start) + '  ' + area.value.slice(end);
        area.selectionStart = area.selectionEnd = start + 2;
        area.dispatchEvent(new Event('input'));
      }
    });
  }

  // ---- Dispatch --------------------------------------------------------------------------------

  var dispatch = { live: null, state: null, timer: null, loading: false, error: null, at: 0 };

  function dispatchPoll(force) {
    clearTimeout(dispatch.timer);
    if (state.section !== 'dispatch') return;
    dispatch.timer = setTimeout(function () { dispatchPoll(false); }, 3000);
    if (dispatch.loading && !force) return;
    if (document.hidden) return;
    dispatch.loading = true;
    Promise.all([request('GET', '/api/live'), request('GET', '/api/dispatch')]).then(function (results) {
      dispatch.live = results[0];
      dispatch.state = results[1];
      dispatch.error = null;
      dispatch.at = Date.now();
      dispatch.loading = false;
      if (state.section === 'dispatch') renderDispatchLive();
    }).catch(function (error) {
      dispatch.loading = false;
      dispatch.error = error.message;
      if (state.section === 'dispatch') renderDispatchLive();
    });
  }

  function order(body, done) {
    return request('POST', '/api/dispatch', body).then(function (result) {
      if (result.state) dispatch.state = result.state;
      toast(done || ('Done: ' + (result.done || body.action)));
      dispatchPoll(true);
    }).catch(function (error) { toast(error.message, 'bad'); });
  }

  function dispatchView() {
    dispatchPoll(true);
    return '<h2 class="title">Dispatch</h2><p class="lede">Every train live, with what holds it. Orders take effect at once and are logged. '
      + 'A hold keeps a train at its current or next station sign; a skip makes it run through a station once, and riders are told.</p>'
      + '<div id="dispatch-live">' + dispatchLiveHtml() + '</div>';
  }

  function renderDispatchLive() {
    var holder = $('dispatch-live');
    if (!holder) return;
    var open = {};
    Array.prototype.forEach.call(holder.querySelectorAll('details'), function (d) { if (d.open && d.id) open[d.id] = true; });
    var focused = document.activeElement && document.activeElement.closest && document.activeElement.closest('#dispatch-live');
    if (focused && document.activeElement.tagName !== 'BUTTON') return;
    holder.innerHTML = dispatchLiveHtml();
    Object.keys(open).forEach(function (id) { var d = $(id); if (d) d.open = true; });
    renderRail();
  }

  function fmtWhen(ms) {
    var d = new Date(ms);
    return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') + ':' + String(d.getSeconds()).padStart(2, '0');
  }

  function dispatchLiveHtml() {
    var live = dispatch.live, st = dispatch.state;
    if (dispatch.error && !live) return '<div class="notice bad"><div class="main"><b>No live data</b>' + esc(dispatch.error) + '</div></div>';
    if (!live || !st) return '<p class="empty">Loading live data&hellip;</p>';
    var html = '';
    var age = Math.round((Date.now() - dispatch.at) / 1000);
    html += '<div class="group-head"><span>Lines</span><span class="hint" style="margin:0">' + (dispatch.error ? 'offline' : 'updated ' + (age < 2 ? 'just now' : age + ' s ago')) + '</span></div>';
    html += '<ul class="rows">' + (st.lines || []).map(function (l) {
      return '<li><div class="row" style="cursor:default">' + ATCSMap.bullet({ id: l.id, short: l.short, name: l.name, color: l.color }, 26)
        + '<span class="main"><span class="name">' + esc(l.name) + (l.suspended ? ' <span class="tag bad">suspended</span>' : '') + '</span>'
        + (l.suspended ? '<span class="meta">' + esc(l.suspended) + '</span>' : '') + '</span><span class="end">'
        + (l.suspended ? '<button type="button" class="button small secondary" data-act="dispatch-resume" data-line="' + esc(l.id) + '">Resume</button>'
          : '<button type="button" class="button small secondary" data-act="dispatch-suspend" data-line="' + esc(l.id) + '">Suspend</button>') + '</span></div></li>';
    }).join('') + '</ul>';

    var trains = (live.trains || []).slice().sort(function (a, b) { return (a.line + a.name).localeCompare(b.line + b.name); });
    html += '<div class="group-head"><span>Trains in service (' + trains.length + ')</span></div>';
    if (!trains.length) html += '<p class="empty">No train is running a service.</p>';
    html += '<ul class="rows">' + trains.map(function (t) {
      var entry = serviceIndexOf(t.key), line = lineView(t.line);
      var stops = entry ? entry.stops : [];
      var where = t.at ? 'At ' + esc(stationName(t.at)) + (t.platform ? ' <span class="platform">P' + esc(t.platform) + '</span>' : '')
        : (t.next >= 0 && stops[t.next] ? 'Next ' + esc(stationName(parseStop(stops[t.next]).station)) + (typeof t.eta === 'number' ? ' in ' + Math.round(t.eta) + ' s' : '') : '');
      var hold = st.holds && st.holds[t.name];
      var skips = st.skips && st.skips[t.name];
      var flags = (hold ? '<span class="tag warn" title="' + esc(hold) + '">held</span>' : '') + (t.late ? '<span class="tag bad">late</span>' : '')
        + (t.held && !hold ? '<span class="tag soft">waiting</span>' : '') + (skips ? '<span class="tag soft">skips ' + esc(skips.map(stationName).join(', ')) + '</span>' : '');
      var nextStations = [];
      if (entry && t.next >= 0) {
        var i = t.next, guard = 0;
        while (i >= 0 && guard++ < stops.length) { var s = parseStop(stops[i]); if (!s.pass && s.station !== t.at) nextStations.push(s.station); i = i + 1 < stops.length ? i + 1 : (entry.circular ? 0 : -1); if (nextStations.length >= 6) break; }
      }
      return '<li><div class="row" style="cursor:default;flex-wrap:wrap">' + bullet(t.line, 24) + '<span class="main"><span class="name">' + esc(t.name) + ' ' + flags + '</span>'
        + '<span class="meta">' + esc((entry ? entry.name : t.service) + ' to ' + t.destination) + ' · ' + where + '</span>'
        + (hold ? '<span class="meta">Hold: ' + esc(hold) + '</span>' : '') + '</span><span class="end">'
        + (hold ? '<button type="button" class="button small" data-act="dispatch-release" data-train="' + esc(t.name) + '">Release</button>'
          : '<button type="button" class="button small secondary" data-act="dispatch-hold" data-train="' + esc(t.name) + '">Hold</button>')
        + (nextStations.length ? '<select class="input" style="width:auto;padding:7px 28px 7px 9px;font-size:13px" data-act-change="dispatch-skip" data-train="' + esc(t.name) + '" aria-label="Run through a station">'
          + '<option value="">Skip&hellip;</option>' + nextStations.map(function (id) { return '<option value="' + esc(id) + '">' + esc(stationName(id)) + '</option>'; }).join('') + '</select>' : '')
        + '</span></div></li>';
    }).join('') + '</ul>';

    var blockIds = keys(st.blocks);
    html += '<details id="d-blocks" class="card" style="margin-top:16px"><summary class="row" style="cursor:pointer"><span class="name">Blocks (' + blockIds.length + ' occupied)</span></summary><div class="card-body">'
      + (blockIds.length ? '<ul class="coords">' + blockIds.map(function (id) { return '<li><span class="value" style="font-family:var(--font)"><b>' + esc(id) + '</b> · ' + esc(st.blocks[id].train) + ' since ' + fmtWhen(st.blocks[id].since) + '</span></li>'; }).join('') + '</ul>'
        : '<p class="empty">Every block is free.' + (st.junctionPriority ? ' Junction priority is on.' : '') + '</p>') + '</div></details>';

    var switches = st.switches || [];
    html += '<details id="d-switches" class="card" style="margin-top:10px"><summary class="row" style="cursor:pointer"><span class="name">Switch signs (' + switches.length + ' loaded)</span></summary><div class="card-body">'
      + (switches.length ? '<ul class="rows">' + switches.map(function (s) {
        return '<li><div class="row" style="cursor:default;flex-wrap:wrap"><span class="main"><span class="name mono">' + esc(s.at) + (s.set ? ' <span class="tag warn">set ' + esc(s.set) + ' until ' + fmtWhen(s.until) + '</span>' : '') + '</span>'
          + '<span class="meta">' + esc(s.rules) + '</span></span><span class="end">'
          + (s.set ? '<button type="button" class="button small" data-act="dispatch-clear-switch" data-sign="' + esc(s.at) + '">Let rules decide</button>'
            : '<select class="input" style="width:auto;padding:7px 28px 7px 9px;font-size:13px" data-act-change="dispatch-switch" data-sign="' + esc(s.at) + '" aria-label="Set direction"><option value="">Set&hellip;</option>'
              + ['left', 'right', 'continue', 'north', 'east', 'south', 'west'].map(function (h) { return '<option value="' + h + '">' + h + ' for 10 min</option>'; }).join('') + '</select>')
          + '</span></div></li>';
      }).join('') + '</ul>' : '<p class="empty">No switch sign is in a loaded chunk.</p>') + '</div></details>';

    var events = (st.events || []).slice().reverse();
    html += '<details id="d-events" class="card" style="margin-top:10px" open><summary class="row" style="cursor:pointer"><span class="name">Recent events</span></summary><div class="card-body">'
      + (events.length ? '<ul class="coords">' + events.slice(0, 40).map(function (e) {
        var when = e.t ? new Date(e.t) : null;
        var text = (e.e || '?') + (e.train ? ' ' + e.train : '') + (e.station ? ' at ' + stationName(e.station) + (e.platform ? ' P' + e.platform : '') : '')
          + (e.block ? ' block ' + e.block : '') + (e.reason ? ': ' + e.reason : '') + (e.detail ? ': ' + e.detail : '') + (e.to ? ' -> ' + e.to : '');
        return '<li><span class="value" style="font-family:var(--font)"><span class="mono" style="color:var(--muted)">' + (when && !isNaN(when) ? fmtWhen(when.getTime()) : '') + '</span> ' + esc(text) + '</span></li>';
      }).join('') + '</ul>' : '<p class="empty">Nothing yet.</p>') + '</div></details>';
    return html;
  }

  function serviceIndexOf(key) {
    if (!key) return null;
    var parts = key.split('/'), svc = services(parts[0])[parts[1]];
    return svc ? { name: svc.name || parts[1], stops: svc.stops || [], circular: !!svc.circular } : null;
  }

  // ---- Check -----------------------------------------------------------------------------------

  function checkView() {
    var f = findings();
    var html = '<h2 class="title">Check</h2><p class="lede">' + (f.unsaved ? 'For your unsaved version.' : 'For the saved network.')
      + ' The same checks as <code>/atcs lint</code> and the warnings <code>/atcs reload</code> gives.' + (f.pending ? ' Checking your latest edit&hellip;' : '') + '</p>';
    if (state.net.loadErrors && state.net.loadErrors.length) {
      html += '<div class="problems-box"><header>network.yml on disk has errors: the editor and commands are read-only</header><ul class="findings">'
        + state.net.loadErrors.map(function (e) { return '<li class="error">' + esc(e) + '</li>'; }).join('') + '</ul></div>';
    }
    var groups = [
      ['Will not save', f.errors.concat(fareErrors()).map(function (e) { return { level: 'error', text: e }; })],
      ['Problems', f.issues.map(function (t) { return { level: 'problem', text: t }; }).concat(f.lint.filter(function (x) { return x.level === 'problem'; }))],
      ['Warnings', f.lint.filter(function (x) { return x.level === 'warning'; })],
      ['Notes', f.lint.filter(function (x) { return x.level === 'note'; })]
    ];
    var any = false;
    groups.forEach(function (g) {
      if (!g[1].length) return;
      any = true;
      html += '<div class="group-head"><span>' + esc(g[0]) + ' (' + g[1].length + ')</span></div><ul class="findings">' + g[1].map(function (x) {
        var p = x.level === 'error' ? errorPath(x.text) : [];
        return '<li class="' + esc(x.level) + '">' + linkify(x.text) + (p.length ? '<span class="path">' + esc(p.join(' › ')) + '</span>' : '') + '</li>';
      }).join('') + '</ul>';
    });
    if (!any) html += '<div class="notice"><div class="main"><b>Nothing to report</b>Every station has a platform with a sign, every line runs, every board stands.</div></div>';
    return html;
  }

  // ---- Diagram on the map --------------------------------------------------------------------

  function siteNetwork() {
    var net = {
      title: context().title || 'Railway',
      stations: [],
      lines: keys(lines()).map(function (id) {
        var v = lineView(id);
        return {
          id: id, name: v.name, short: v.short, color: v.color, suspended: v.suspended,
          services: keys(services(id)).map(function (sid) {
            var s = services(id)[sid];
            return { id: sid, key: id + '/' + sid, name: s.name || sid, circular: !!s.circular, stops: (s.stops || []).map(parseStop) };
          })
        };
      }),
      transfers: (doc().transfers || []).filter(function (t) { return t.between && t.between.length === 2; }).map(function (t) {
        return { a: t.between[0], b: t.between[1], walk: t.walk, outside: t.outside !== false };
      }),
      map: doc().map ? { stations: doc().map.stations || {}, lines: doc().map.lines || {} } : null
    };
    keys(stations()).forEach(function (id) {
      var at = stationPosition(id);
      net.stations.push({ id: id, name: stationName(id), x: at ? at.x : null, z: at ? at.z : null, platforms: keys(stations()[id].platforms), lines: linesAt(id) });
    });
    return net;
  }

  function relayout() {
    if (!map || !state.doc) return;
    var net = siteNetwork();
    var layout;
    try {
      layout = ATCSLayout.build(net, { seed: state.seed });
    } catch (error) {
      toast('The diagram could not be drawn: ' + error.message, 'bad');
      return;
    }
    state.layout = layout;
    state.seed = layout.seed;
    map.setLayout(layout);
    var placed = keys(layout.stations).length;
    $('map-empty').hidden = placed > 0;
    if (!placed) $('map-empty').innerHTML = 'No station has a position yet. Give one a map position, or bind its platform signs.';
    applyFocus();
  }

  var relayoutTimer = null;
  function relayoutSoon() { clearTimeout(relayoutTimer); relayoutTimer = setTimeout(relayout, 140); }

  /* Shows what is open on the map: a station selected, a line's or service's route drawn over the rest. */
  function applyFocus() {
    if (!map || !state.layout) return;
    var open = state.open, legs = null;
    if (open && (open.kind === 'service' || open.kind === 'line')) {
      var list = open.kind === 'service' ? [{ line: open.line, id: open.id }] : keys(services(open.id)).map(function (sid) { return { line: open.id, id: sid }; });
      var hops = [], first = null, last = null;
      list.forEach(function (entry) {
        var s = services(entry.line)[entry.id];
        if (!s) return;
        var stops = (s.stops || []).map(parseStop);
        stops.forEach(function (stop, i) {
          var next = i + 1 < stops.length ? stops[i + 1] : s.circular ? stops[0] : null;
          if (next && next.station !== stop.station) hops.push({ line: entry.line, a: stop.station, b: next.station });
        });
        if (open.kind === 'service' && stops.length) { first = stops[0].station; last = stops[stops.length - 1].station; }
      });
      legs = hops.length ? [{ from: first, to: last, hops: hops }] : null;
      if (open.kind === 'line' && legs) { legs[0].from = null; legs[0].to = null; }
    }
    map.highlight(legs);
    map.select(open && open.kind === 'station' ? open.id : null, false);
  }

  // ---- Editing ---------------------------------------------------------------------------------

  function snapshot(coalesce) {
    var now = Date.now();
    if (coalesce && state.coalesce === coalesce && now - state.coalesceAt < 1500) { state.coalesceAt = now; return; }
    state.coalesce = coalesce || null;
    state.coalesceAt = now;
    state.undo.push({ doc: JSON.stringify(state.doc), fares: state.faresDoc ? JSON.stringify(state.faresDoc) : null });
    if (state.undo.length > 300) state.undo.shift();
    state.redo = [];
  }

  /* Runs a change to the documents with an undo step, then refreshes what depends on it. */
  function edit(change, options) {
    options = options || {};
    snapshot(options.coalesce);
    change();
    changed(options);
  }

  function changed(options) {
    options = options || {};
    bump();
    updateChrome();
    validateSoon();
    if (!options.noMap) relayoutSoon();
    if (options.render) renderPane(true); else refreshLive();
  }

  function localUndo(redo) {
    var from = redo ? state.redo : state.undo, to = redo ? state.undo : state.redo;
    if (!from.length) return;
    to.push({ doc: JSON.stringify(state.doc), fares: state.faresDoc ? JSON.stringify(state.faresDoc) : null });
    var step = from.pop();
    state.doc = JSON.parse(step.doc);
    if (step.fares) state.faresDoc = JSON.parse(step.fares);
    state.coalesce = null;
    if (state.open && state.open.kind === 'station' && !stations()[state.open.id]) state.open = null;
    changed({ render: true });
  }

  function readValue(node, kind) {
    var raw = node.type === 'checkbox' ? node.checked : node.value;
    switch (kind) {
      case 'optional': return String(raw).trim() === '' ? undefined : String(raw);
      case 'int': { var t = String(raw).trim(); if (t === '') return undefined; var n = parseInt(t, 10); return isNaN(n) ? t : n; }
      case 'number': { var u = String(raw).trim(); if (u === '') return undefined; var m = Number(u.replace(',', '.')); return isNaN(m) ? u : m; }
      case 'list': { var items = String(raw).split(/[,\s]+/).map(function (s) { return s.trim(); }).filter(Boolean); return items.length ? items : undefined; }
      case 'color': { var c = String(raw).trim(); if (/^[0-9a-f]{6}$/i.test(c)) c = '#' + c; return /^#[0-9a-f]{6}$/i.test(c) ? c.toUpperCase() : c; }
      case 'bool': return raw ? true : undefined;
      default: return String(raw);
    }
  }

  function applyBinding(node) {
    var which = node.getAttribute('data-doc') === 'fares' ? 'fares' : 'network';
    var root = which === 'fares' ? state.faresDoc : state.doc;
    if (!root) return;
    var parts = JSON.parse(node.getAttribute('data-path'));
    var kind = node.getAttribute('data-kind') || 'text';
    var value = readValue(node, kind);
    if (kind === 'color' && node.type === 'text' && !/^#[0-9A-F]{6}$/.test(value || '')) {
      node.classList.toggle('invalid', true);
      return;
    }
    node.classList.remove('invalid');
    if (JSON.stringify(getPath(root, parts)) === JSON.stringify(value)) return;
    snapshot(which + JSON.stringify(parts));
    setPath(root, parts, value);
    if (node.hasAttribute('data-prune') || value === undefined) prune(root, parts);
    // Keep twin inputs of the same value (a colour picker and its hex field) in step.
    Array.prototype.forEach.call(document.querySelectorAll('[data-path]'), function (other) {
      if (other !== node && other.getAttribute('data-path') === node.getAttribute('data-path') && other.getAttribute('data-doc') === node.getAttribute('data-doc')) {
        if (kind === 'color' && other.type === 'color' && value) other.value = value.toLowerCase();
        else if (kind === 'color' && other.type === 'text') { other.value = value || ''; other.classList.remove('invalid'); }
      }
    });
    var affectsMap = which === 'network' && (parts[0] !== 'boards' && parts[0] !== 'displays' && parts[2] !== 'match' && parts[4] !== 'match' && parts[4] !== 'fleet');
    changed({ noMap: !affectsMap });
  }

  var validateTimer = null, validateSeq = 0;
  function validateSoon() { clearTimeout(validateTimer); validateTimer = setTimeout(validate, 380); }

  function validate() {
    var seq = ++validateSeq, jobs = [];
    if (networkDirty()) {
      var text = texts().doc;
      if (!state.check || state.check.for !== text) {
        jobs.push(request('POST', '/api/validate', { document: state.doc }).then(function (result) {
          if (seq !== validateSeq) return;
          result.for = text;
          state.check = result;
        }));
      }
    } else {
      state.check = null;
    }
    if (faresDirty()) {
      var ftext = texts().fares;
      if (!state.faresCheck || state.faresCheck.for !== ftext) {
        jobs.push(request('POST', '/api/validate-fares', { document: state.faresDoc }).then(function (result) {
          if (seq !== validateSeq) return;
          result.for = ftext;
          state.faresCheck = result;
        }));
      }
    } else {
      state.faresCheck = null;
    }
    return Promise.all(jobs).then(function () {
      if (seq !== validateSeq) return;
      updateChrome();
      if (state.section === 'check') { renderPane(true); return; }
      refreshLive();
      var list = $('list');
      if (list && !state.open && state.section === 'stations') list.innerHTML = stationRows();
      if (list && !state.open && state.section === 'lines') list.innerHTML = lineRows();
    }).catch(function (error) { if (error.status !== 401) toast(error.message, 'bad'); });
  }

  // ---- Saving, reloading, conflicts ----------------------------------------------------------

  function applyNetwork(payload, replaceDoc) {
    var document = payload.document;
    bump();
    state.net = payload;
    state.saved = JSON.stringify(document);
    if (replaceDoc || !state.doc) state.doc = clone(document);
    state.check = null;
    state.remote.network = false;
    if (state.seed === null && context().layout && context().layout.stations && !state.seedLoaded) {
      var cells = {};
      keys(context().layout.stations).forEach(function (id) {
        var cell = context().layout.stations[id];
        if (cell && cell.length === 2) cells[id] = { col: cell[0], row: cell[1] };
      });
      state.seed = { arrangement: context().layout.arrangement, stations: cells };
    }
    state.seedLoaded = true;
  }

  function applyFares(payload, replaceDoc) {
    if (!payload) { state.fares = null; state.faresDoc = null; state.faresSaved = ''; return; }
    bump();
    state.fares = payload;
    state.faresSaved = JSON.stringify(payload.document);
    if (replaceDoc || !state.faresDoc) state.faresDoc = clone(payload.document);
    state.faresCheck = null;
    state.remote.fares = false;
  }

  function canSave() {
    if (!state.net.writable || state.saving || !dirty()) return false;
    var f = findings();
    return !f.pending && !faresPending() && !f.errors.length && !fareErrors().length;
  }

  function save() {
    if (!canSave()) {
      if (dirty() && (findings().pending || faresPending()) && !state.saving) {
        clearTimeout(validateTimer);
        validate().then(function () { if (canSave()) save(); });
      }
      return;
    }
    state.saving = true;
    updateChrome();
    var chain = Promise.resolve(), saved = [];
    // Edits made while the save is on its way are kept: the saved state moves, the page keeps them.
    if (networkDirty()) {
      var sentNetwork = texts().doc;
      chain = chain.then(function () {
        return request('PUT', '/api/network', { base: state.net.version, document: JSON.parse(sentNetwork) }).then(function (payload) {
          applyNetwork(payload, texts().doc === sentNetwork);
          saved.push('network.yml');
        });
      });
    }
    if (faresDirty()) {
      var sentFares = texts().fares;
      chain = chain.then(function () {
        return request('PUT', '/api/fares', { base: state.fares.version, document: JSON.parse(sentFares) }).then(function (payload) {
          applyFares(payload, texts().fares === sentFares);
          saved.push('fares.yml');
        });
      });
    }
    chain.then(function () {
      state.saving = false;
      state.savedAt = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      toast('Saved ' + saved.join(' and ') + '. It is live in game now.');
      renderPane(true);
      updateChrome();
      relayout();
    }).catch(function (error) {
      state.saving = false;
      updateChrome();
      if (error.status === 409 && error.detail && error.detail.document) conflict(error);
      else if (error.status === 400 && error.detail && error.detail.errors) { toast(error.message, 'bad'); validate(); }
      else if (error.status !== 401) toast(error.message, 'bad');
    });
  }

  function discard() {
    modal({
      title: 'Discard your changes?', narrow: true,
      body: '<p>' + esc(changeSummary().join(', ')) + ' go back to how they are saved. Ctrl+Z can still bring them back while this page is open.</p>',
      buttons: [{ label: 'Keep editing', kind: 'secondary' }, {
        label: 'Discard', kind: 'danger', action: function () {
          snapshot();
          state.doc = JSON.parse(state.saved);
          if (state.faresDoc) state.faresDoc = JSON.parse(state.faresSaved);
          changed({ render: true });
        }
      }]
    });
  }

  function conflict(error) {
    var theirs = error.detail;
    var mine = state.check && state.check.yaml;
    var show = function (mineYaml) {
      modal({
        title: 'The network changed on the server',
        body: '<p>' + esc(error.message) + '</p><p>Below, <b>red</b> is what is on the server now and <b>green</b> is your version. Saving yours replaces theirs.</p>'
          + diffHtml(theirs.yaml, mineYaml || ''),
        buttons: [
          { label: 'Keep editing', kind: 'secondary' },
          { label: 'Load theirs, drop mine', kind: 'secondary', action: function () { snapshot(); applyNetwork(theirs, true); changed({ render: true }); } },
          { label: 'Save mine over theirs', kind: 'danger', action: function () { applyNetwork(theirs, false); changed({ render: true }); clearTimeout(validateTimer); validate().then(save); } }
        ]
      });
    };
    if (mine) show(mine);
    else request('POST', '/api/validate', { document: state.doc }).then(function (r) { show(r.yaml); }).catch(function () { show(''); });
  }

  function loadTheirs() {
    Promise.all([request('GET', '/api/network'), state.fares ? request('GET', '/api/fares').catch(function () { return null; }) : Promise.resolve(null)])
      .then(function (results) {
        snapshot();
        if (state.remote.network) applyNetwork(results[0], true);
        if (results[1] && state.remote.fares) applyFares(results[1], true);
        changed({ render: true });
        toast('Loaded the version on the server. Ctrl+Z brings yours back.');
      }).catch(function (error) { toast(error.message, 'bad'); });
  }

  function review() {
    var go = function () {
      var parts = '';
      if (networkDirty()) parts += '<div class="group-head"><span>network.yml</span></div>' + diffHtml(state.net.yaml, state.check.yaml);
      if (faresDirty() && state.faresCheck) parts += '<div class="group-head"><span>fares.yml</span></div>' + diffHtml(state.fares.yaml, state.faresCheck.yaml);
      modal({
        title: 'Your changes, as they will be written',
        body: parts || '<p>Nothing to save.</p>',
        buttons: [{ label: 'Close', kind: 'secondary' }].concat(canSave() ? [{ label: 'Save', kind: 'primary', action: save }] : [])
      });
    };
    var f = findings();
    if ((networkDirty() && (!state.check || f.pending)) || faresPending()) {
      clearTimeout(validateTimer);
      validate().then(go);
    } else {
      go();
    }
  }

  function restore() {
    modal({
      title: 'Restore the previous version?', narrow: true,
      body: '<p>network.yml goes back to how it was before its last saved edit, made here or by a command in game. The current file is kept in <code>history/</code>.</p>'
        + (dirty() ? '<p><b>Your unsaved changes here are dropped.</b></p>' : ''),
      buttons: [{ label: 'Cancel', kind: 'secondary' }, {
        label: 'Restore', kind: 'danger', action: function () {
          request('POST', '/api/undo').then(function (payload) {
            snapshot();
            applyNetwork(payload, true);
            changed({ render: true });
            toast('Restored the previous version of network.yml.');
          }).catch(function (error) { toast(error.message, 'bad'); });
        }
      }]
    });
  }

  // ---- Diff ------------------------------------------------------------------------------------

  function diffLines(a, b) {
    var x = String(a || '').split('\n'), y = String(b || '').split('\n');
    var start = 0;
    while (start < x.length && start < y.length && x[start] === y[start]) start++;
    var endX = x.length - 1, endY = y.length - 1;
    while (endX >= start && endY >= start && x[endX] === y[endY]) { endX--; endY--; }
    var mx = x.slice(start, endX + 1), my = y.slice(start, endY + 1);
    var n = mx.length, m = my.length, table = [];
    for (var i = 0; i <= n; i++) table.push(new Int32Array(m + 1));
    for (i = n - 1; i >= 0; i--) for (var j = m - 1; j >= 0; j--) table[i][j] = mx[i] === my[j] ? table[i + 1][j + 1] + 1 : Math.max(table[i + 1][j], table[i][j + 1]);
    var ops = [];
    for (i = 0; i < start; i++) ops.push(['same', x[i]]);
    i = 0; j = 0;
    while (i < n && j < m) {
      if (mx[i] === my[j]) { ops.push(['same', mx[i]]); i++; j++; }
      else if (table[i + 1][j] >= table[i][j + 1]) { ops.push(['del', mx[i]]); i++; }
      else { ops.push(['add', my[j]]); j++; }
    }
    while (i < n) ops.push(['del', mx[i++]]);
    while (j < m) ops.push(['add', my[j++]]);
    for (i = endX + 1; i < x.length; i++) ops.push(['same', x[i]]);
    return ops;
  }

  function diffHtml(a, b) {
    var ops = diffLines(a, b), added = 0, removed = 0;
    ops.forEach(function (op) { if (op[0] === 'add') added++; if (op[0] === 'del') removed++; });
    if (!added && !removed) return '<p class="empty">No difference.</p>';
    var keep = ops.map(function () { return false; });
    ops.forEach(function (op, i) {
      if (op[0] === 'same') return;
      for (var k = Math.max(0, i - 3); k <= Math.min(ops.length - 1, i + 3); k++) keep[k] = true;
    });
    var html = '<div class="diff-stats"><span class="plus">+' + added + '</span><span class="minus">&minus;' + removed + '</span></div><div class="diff">', skipped = 0;
    ops.forEach(function (op, i) {
      if (!keep[i]) { skipped++; return; }
      if (skipped) { html += '<div class="gap">' + plural(skipped, 'unchanged line') + '</div>'; skipped = 0; }
      html += '<div class="' + (op[0] === 'same' ? '' : op[0]) + '">' + esc(op[1]) + '</div>';
    });
    if (skipped) html += '<div class="gap">' + plural(skipped, 'unchanged line') + '</div>';
    return html + '</div>';
  }

  // ---- Modal and toast -----------------------------------------------------------------------

  var closeModal = null;

  function modal(options) {
    if (closeModal) closeModal();
    var root = $('modal-root');
    root.innerHTML = '<div class="modal-back"><div class="modal' + (options.narrow ? ' narrow' : '') + '" role="dialog" aria-modal="true" aria-labelledby="modal-title">'
      + '<header><h2 id="modal-title">' + esc(options.title) + '</h2><button type="button" class="close" aria-label="Close">&times;</button></header>'
      + '<div class="modal-body">' + options.body + '</div><footer>' + options.buttons.map(function (b, i) {
        return '<button type="button" class="button ' + (b.kind === 'primary' ? '' : b.kind || '') + '" data-modal="' + i + '">' + esc(b.label) + '</button>';
      }).join('') + '</footer></div></div>';
    var back = root.firstChild;
    var close = function () { root.innerHTML = ''; closeModal = null; document.removeEventListener('keydown', onKey, true); };
    var onKey = function (event) { if (event.key === 'Escape') { event.stopPropagation(); close(); } };
    document.addEventListener('keydown', onKey, true);
    back.addEventListener('click', function (event) {
      if (event.target === back || event.target.classList.contains('close')) { close(); return; }
      var button = event.target.closest('[data-modal]');
      if (!button) return;
      var chosen = options.buttons[+button.getAttribute('data-modal')];
      var values = {};
      Array.prototype.forEach.call(back.querySelectorAll('input, select'), function (input) {
        if (input.id) values[input.id] = input.type === 'checkbox' ? input.checked : input.value;
      });
      close();
      if (chosen.action) chosen.action(values);
    });
    var last = back.querySelector('footer .button:last-child');
    if (last) last.focus();
    closeModal = close;
    return close;
  }

  var toastTimer = null;
  function toast(message, kind, action) {
    var root = $('toast-root');
    if (!root) return;
    clearTimeout(toastTimer);
    root.innerHTML = '<div class="toast' + (kind === 'bad' ? ' bad' : '') + '" role="status"><span>' + esc(message) + '</span>'
      + (action ? '<button type="button" data-toast>' + esc(action.label) + '</button>' : '') + '</div>';
    if (action) root.querySelector('[data-toast]').addEventListener('click', function () { root.innerHTML = ''; action.run(); });
    toastTimer = setTimeout(function () { root.innerHTML = ''; }, kind === 'bad' ? 9000 : 5000);
  }

  // ---- Actions ---------------------------------------------------------------------------------

  function data(node, name) { return node.getAttribute('data-' + name); }

  function openSection(section) {
    state.section = section;
    state.open = null;
    state.creating = null;
    renderPane();
    if (section !== 'dispatch') clearTimeout(dispatch.timer);
  }

  function openStation(id) {
    state.section = 'stations';
    state.open = { kind: 'station', id: id };
    state.creating = null;
    renderPane();
  }

  function requireId(id, taken, kind) {
    if (!id) { toast('Give the ' + kind + ' an id', 'bad'); return false; }
    if (!ID.test(id)) { toast('Ids use lowercase letters, digits, - and _ (at most 32): "' + id + '" does not', 'bad'); return false; }
    if (taken) { toast('There is already a ' + kind + ' with the id ' + id, 'bad'); return false; }
    return true;
  }

  function stopsOf(node) {
    var list = $('stops');
    var lineId = list ? list.getAttribute('data-line') : data(node, 'line');
    var sid = list ? list.getAttribute('data-service') : data(node, 'service');
    return { lineId: lineId, sid: sid, service: services(lineId)[sid] };
  }

  function mapStation(id) { return ((doc().map && doc().map.stations) || {})[id] || {}; }

  function setMapStation(id, entry) {
    var clean = {};
    ['column', 'row', 'label', 'hidden'].forEach(function (k) { if (entry[k] !== undefined && entry[k] !== null && entry[k] !== false && entry[k] !== '') clean[k] = entry[k]; });
    if (!keys(clean).length) { setPath(doc(), ['map', 'stations', id], undefined); prune(doc(), ['map', 'stations', id]); return; }
    if (!doc().map) doc().map = {};
    if (!doc().map.stations) doc().map.stations = {};
    doc().map.stations[id] = clean;
  }

  function references(stationId) {
    var refs = [];
    allServices().forEach(function (e) {
      var n = (e.service.stops || []).filter(function (t) { return parseStop(t).station === stationId; }).length;
      if (n) refs.push(plural(n, 'stop') + ' in ' + e.key);
    });
    keys(doc().boards).forEach(function (b) { if (doc().boards[b].station === stationId) refs.push('board ' + b); });
    (doc().transfers || []).forEach(function (t) { if ((t.between || []).indexOf(stationId) >= 0) refs.push('the walk to ' + stationName(t.between[0] === stationId ? t.between[1] : t.between[0])); });
    return refs;
  }

  var actions = {
    section: function (node) { openSection(data(node, 'section')); },
    close: function () { state.open = null; state.creating = null; renderPane(); },
    'open-station': function (node) { openStation(data(node, 'id')); },
    'open-line': function (node) { state.section = 'lines'; state.open = { kind: 'line', id: data(node, 'id') }; state.creating = null; renderPane(); },
    'open-service': function (node) { state.section = 'lines'; state.open = { kind: 'service', line: data(node, 'line'), id: data(node, 'service') }; state.creating = null; renderPane(); },
    'open-board': function (node) { state.section = 'boards'; state.open = { kind: 'board', id: data(node, 'id') }; renderPane(); },
    'new': function (node) {
      state.creating = data(node, 'kind');
      renderPane(true);
      var first = $('new-name') || $('new-id');
      if (first) first.focus();
    },
    'cancel-new': function () { state.creating = null; renderPane(true); },

    'create-station': function () {
      var name = ($('new-name').value || '').trim(), id = ($('new-id').value || '').trim() || slugify(name);
      if (!name) { toast('Give the station a name', 'bad'); return; }
      if (!requireId(id, !!stations()[id], 'station')) return;
      edit(function () {
        if (!doc().stations) doc().stations = {};
        doc().stations[id] = { name: name, platforms: { '1': { triggers: [] } } };
      });
      state.creating = null;
      openStation(id);
      toast('Created ' + name + '. Give platform 1 its sign, then add the station to a service.');
    },
    'delete-station': function (node) {
      var id = data(node, 'id');
      var displays = keys(doc().displays).filter(function (d) { return doc().displays[d].station === id; });
      if (displays.length) {
        modal({ title: 'Take its displays down first', narrow: true, body: '<p>' + esc(stationName(id)) + ' has displays in the world. Remove them in game first, so their frames are emptied:</p><ul class="refs">'
          + displays.map(function (d) { return '<li><code>/atcs display remove ' + esc(d) + '</code></li>'; }).join('') + '</ul>', buttons: [{ label: 'OK', kind: 'primary' }] });
        return;
      }
      var refs = references(id);
      modal({
        title: 'Delete ' + stationName(id) + '?', narrow: true,
        body: refs.length ? '<p>These go with it:</p><ul class="refs">' + refs.map(function (r) { return '<li>' + esc(r) + '</li>'; }).join('') + '</ul>' : '<p>Nothing else uses it.</p>',
        buttons: [{ label: 'Cancel', kind: 'secondary' }, {
          label: 'Delete station', kind: 'danger', action: function () {
            edit(function () {
              allServices().forEach(function (e) { e.service.stops = (e.service.stops || []).filter(function (t) { return parseStop(t).station !== id; }); });
              keys(doc().boards).forEach(function (b) { if (doc().boards[b].station === id) delete doc().boards[b]; });
              if (doc().transfers) doc().transfers = doc().transfers.filter(function (t) { return (t.between || []).indexOf(id) < 0; });
              setMapStation(id, {});
              delete doc().stations[id];
              if (state.faresDoc) {
                if (state.faresDoc.zones) delete state.faresDoc.zones[id];
                keys(state.faresDoc.pairs).forEach(function (k) { if (k.split('|').indexOf(id) >= 0) delete state.faresDoc.pairs[k]; });
              }
            });
            state.open = null;
            renderPane();
            toast('Deleted. Ctrl+Z brings it back until you leave the page.', '', { label: 'Undo', run: function () { localUndo(false); } });
          }
        }]
      });
    },
    'add-platform': function (node) {
      var id = data(node, 'id'), s = stations()[id];
      var n = 1;
      while (s.platforms && s.platforms[String(n)]) n++;
      edit(function () { if (!s.platforms) s.platforms = {}; s.platforms[String(n)] = { triggers: [] }; }, { render: true, noMap: true });
    },
    'remove-platform': function (node) {
      var id = data(node, 'id'), pid = data(node, 'platform');
      var used = allServices().filter(function (e) { return (e.service.stops || []).some(function (t) { var s = parseStop(t); return s.station === id && s.platform === pid; }); });
      var go = function () {
        edit(function () {
          used.forEach(function (e) { e.service.stops = e.service.stops.map(function (t) { var s = parseStop(t); if (s.station === id && s.platform === pid) s.platform = null; return formatStop(s); }); });
          keys(doc().boards).forEach(function (b) {
            var board = doc().boards[b];
            if (board.station === id && board.platforms) { board.platforms = board.platforms.filter(function (p) { return p !== pid; }); if (!board.platforms.length) delete board.platforms; }
          });
          delete stations()[id].platforms[pid];
        }, { render: true });
      };
      if (!used.length) { go(); return; }
      modal({ title: 'Remove platform ' + pid + '?', narrow: true,
        body: '<p>' + esc(used.map(function (e) { return e.key; }).join(', ')) + ' plan to use it; they will use any platform instead.</p>',
        buttons: [{ label: 'Cancel', kind: 'secondary' }, { label: 'Remove platform', kind: 'danger', action: go }] });
    },
    'add-trigger': function (node) {
      var id = data(node, 'id'), pid = data(node, 'platform');
      var input = $('trigger-' + pid), text = (input.value || '').trim().replace(/\s+/g, '');
      if (!parsePosition(text)) { input.classList.add('invalid'); toast('Write a sign position as world,x,y,z', 'bad'); return; }
      edit(function () { var p = stations()[id].platforms[pid]; if (!p.triggers) p.triggers = []; p.triggers.push(text); }, { render: true });
    },
    'remove-trigger': function (node) {
      var id = data(node, 'id'), pid = data(node, 'platform'), i = +data(node, 'index');
      edit(function () { stations()[id].platforms[pid].triggers.splice(i, 1); }, { render: true });
    },
    'clear-footprint': function (node) { var id = data(node, 'id'); edit(function () { delete stations()[id].footprint; }, { render: true, noMap: true }); },
    'station-label': function (node) {
      var id = data(node, 'id'), value = data(node, 'value');
      edit(function () { var e = clone(mapStation(id)); e.label = value || undefined; setMapStation(id, e); }, { render: true });
    },
    'station-hidden': function (node) {
      var id = data(node, 'id'), hide = data(node, 'value') === 'hidden';
      edit(function () { var e = clone(mapStation(id)); e.hidden = hide || undefined; setMapStation(id, e); }, { render: true });
    },
    unpin: function (node) {
      var id = data(node, 'id');
      edit(function () { var e = clone(mapStation(id)); delete e.column; delete e.row; setMapStation(id, e); }, { render: true });
    },
    'clear-map': function (node) { var id = data(node, 'id'); edit(function () { setMapStation(id, {}); }, { render: true }); },
    'save-layout': function () {
      if (!state.layout) return;
      var cells = {};
      keys(state.layout.seed.stations).forEach(function (id) { var c = state.layout.seed.stations[id]; cells[id] = [Math.round(c.col), Math.round(c.row)]; });
      request('POST', '/api/layout', { stations: cells, arrangement: state.layout.seed.arrangement }).then(function (r) {
        if (state.net.context) state.net.context.layout = { stations: cells, arrangement: state.layout.seed.arrangement };
        toast('Saved the layout of ' + plural(r.saved, 'station') + '. The website starts from it.');
        if (state.section === 'diagram') renderPane(true);
      }).catch(function (error) { toast(error.message, 'bad'); });
    },
    'fresh-layout': function () { state.seed = null; relayout(); if (state.section === 'diagram') renderPane(true); toast('Laid out from scratch. Save it for everyone if you like it.'); },

    'create-line': function () {
      var name = ($('new-name').value || '').trim(), id = ($('new-id').value || '').trim().toLowerCase();
      var badge = ($('new-badge').value || '').trim(), color = ($('new-color').value || '#1E88E5').toUpperCase();
      if (!name) { toast('Give the line a name', 'bad'); return; }
      if (!id) id = slugify(badge || name);
      if (!requireId(id, !!lines()[id], 'line')) return;
      edit(function () {
        if (!doc().lines) doc().lines = {};
        doc().lines[id] = { name: name, 'short-name': badge || id.toUpperCase(), color: color, services: {} };
      });
      state.creating = null;
      state.open = { kind: 'line', id: id };
      renderPane();
      toast('Created ' + name + '. Add a service for each direction it runs.');
    },
    'delete-line': function (node) {
      var id = data(node, 'id');
      var shown = keys(doc().displays).filter(function (d) { var s = doc().displays[d].service; return s && String(s).split('/')[0] === id; });
      if (shown.length) {
        modal({ title: 'Take its roadmaps down first', narrow: true, body: '<p>Roadmap displays show its services. Remove them in game first:</p><ul class="refs">'
          + shown.map(function (d) { return '<li><code>/atcs display remove ' + esc(d) + '</code></li>'; }).join('') + '</ul>', buttons: [{ label: 'OK', kind: 'primary' }] });
        return;
      }
      modal({
        title: 'Delete ' + lineView(id).name + '?', narrow: true,
        body: '<p>Its ' + plural(keys(services(id)).length, 'service') + ' go with it. Services elsewhere that continue as one of them stop doing so.</p>',
        buttons: [{ label: 'Cancel', kind: 'secondary' }, {
          label: 'Delete line', kind: 'danger', action: function () {
            edit(function () {
              allServices().forEach(function (e) {
                var to = e.service['continues-as'];
                if (!to || e.line === id) return;
                if (String(to).split('/')[0] === id && String(to).indexOf('/') >= 0) delete e.service['continues-as'];
              });
              delete doc().lines[id];
              setPath(doc(), ['map', 'lines', id], undefined);
              prune(doc(), ['map', 'lines', id]);
            });
            state.open = null;
            renderPane();
            toast('Deleted the line.', '', { label: 'Undo', run: function () { localUndo(false); } });
          }
        }]
      });
    },
    'line-status': function (node) {
      var id = data(node, 'id'), suspended = data(node, 'value') === 'suspended';
      edit(function () { if (suspended) lines()[id].suspended = lines()[id].suspended || 'Service suspended'; else delete lines()[id].suspended; }, { render: true });
    },
    'create-service': function (node) {
      var lineId = data(node, 'line') || (state.open && state.open.kind === 'line' ? state.open.id : null), name = ($('new-name').value || '').trim(), id = ($('new-id').value || '').trim().toLowerCase() || slugify(name);
      if (!requireId(id, !!services(lineId)[id], 'service')) return;
      edit(function () { var l = lines()[lineId]; if (!l.services) l.services = {}; l.services[id] = { name: name || id, stops: [] }; });
      state.creating = null;
      state.open = { kind: 'service', line: lineId, id: id };
      renderPane();
    },
    'delete-service': function (node) {
      var lineId = data(node, 'line'), sid = data(node, 'service'), key = lineId + '/' + sid;
      var shown = keys(doc().displays).filter(function (d) { return doc().displays[d].service === key; });
      if (shown.length) {
        modal({ title: 'Take its roadmaps down first', narrow: true, body: '<p>Remove them in game first:</p><ul class="refs">'
          + shown.map(function (d) { return '<li><code>/atcs display remove ' + esc(d) + '</code></li>'; }).join('') + '</ul>', buttons: [{ label: 'OK', kind: 'primary' }] });
        return;
      }
      modal({
        title: 'Delete ' + key + '?', narrow: true, body: '<p>Trains running it lose their service until another one matches them.</p>',
        buttons: [{ label: 'Cancel', kind: 'secondary' }, {
          label: 'Delete service', kind: 'danger', action: function () {
            edit(function () {
              allServices().forEach(function (e) {
                var to = e.service['continues-as'];
                if (!to) return;
                var full = String(to).indexOf('/') >= 0 ? to : e.line + '/' + to;
                if (full === key) delete e.service['continues-as'];
              });
              delete lines()[lineId].services[sid];
            });
            state.open = { kind: 'line', id: lineId };
            renderPane();
          }
        }]
      });
    },
    'service-circular': function (node) {
      var s = stopsOf(node);
      edit(function () { if (data(node, 'value') === 'loop') s.service.circular = true; else delete s.service.circular; }, { render: true });
    },
    'service-fleet': function (node) {
      var s = stopsOf(node), on = data(node, 'value') === 'on';
      edit(function () {
        if (!on) { delete s.service.fleet; return; }
        var first = s.service.stops && s.service.stops.length ? parseStop(s.service.stops[0]).station : null;
        var at = first ? stationPosition(first) : null;
        s.service.fleet = { trains: 2, every: '02:00', spawn: at ? at.world + ',' + at.x + ',' + (at.y || 64) + ',' + at.z : 'world,0,64,0', direction: 'north', train: '1m' };
      }, { render: true, noMap: true });
      if (on) toast('Set where trains spawn and the way they face before saving.');
    },
    'remove-spawn': function (node) {
      var s = stopsOf(node), i = +data(node, 'index');
      edit(function () { s.service['spawn-signs'].splice(i, 1); if (!s.service['spawn-signs'].length) delete s.service['spawn-signs']; }, { render: true, noMap: true });
    },
    'stop-pass': function (node) {
      var s = stopsOf(node), i = +data(node, 'index');
      edit(function () { var stop = parseStop(s.service.stops[i]); stop.pass = !stop.pass; if (stop.pass) stop.platform = null; s.service.stops[i] = formatStop(stop); }, { render: true });
    },
    'stop-remove': function (node) {
      var s = stopsOf(node), i = +data(node, 'index');
      edit(function () { s.service.stops.splice(i, 1); }, { render: true });
    },
    'stop-insert': function (node) {
      var s = stopsOf(node), i = +data(node, 'index');
      var used = s.service.stops.map(function (t) { return parseStop(t).station; });
      var candidate = keys(stations()).filter(function (id) { return used.indexOf(id) < 0; })[0] || keys(stations())[0];
      if (!candidate) return;
      edit(function () { s.service.stops.splice(i + 1, 0, candidate); }, { render: true });
      var select = document.querySelector('.stop[data-index="' + (i + 1) + '"] select.station');
      if (select) select.focus();
    },
    'stop-add': function (node) {
      var s = stopsOf(node), id = $('add-stop').value;
      if (!id) { $('add-stop').focus(); return; }
      edit(function () { if (!s.service.stops) s.service.stops = []; s.service.stops.push(id); }, { render: true });
      var select = $('add-stop');
      if (select) select.focus();
    },
    'reverse-stops': function (node) {
      var s = stopsOf(node);
      edit(function () { s.service.stops = s.service.stops.slice().reverse(); }, { render: true });
    },
    'make-return': function (node) {
      var s = stopsOf(node);
      var base = s.sid, guess = base.replace(/north/, 'south').replace(/east/, 'west').replace(/^to-/, 'from-');
      if (guess === base) guess = base + '-back';
      var lastStop = parseStop(s.service.stops[0]);
      modal({
        title: 'Make the return service', narrow: true,
        body: '<p>A new service on ' + esc(lineView(s.lineId).name) + ' with the stops of ' + esc(s.lineId + '/' + s.sid) + ' the other way round, to ' + esc(stationName(lastStop.station)) + '.</p>'
          + '<div class="grid"><label class="field"><span>Id</span><input type="text" id="return-id" class="mono" value="' + esc(guess) + '" spellcheck="false"></label>'
          + '<label class="field"><span>Name</span><input type="text" id="return-name" value="' + esc(s.service.name || '') + '"></label></div>'
          + '<label class="check"><input type="checkbox" id="return-turn" checked> Trains turn round: each continues as the other at its last stop</label>',
        buttons: [{ label: 'Cancel', kind: 'secondary' }, {
          label: 'Make it', kind: 'primary', action: function (values) {
            var id = String(values['return-id'] != null ? values['return-id'] : guess).trim().toLowerCase();
            var name = String(values['return-name'] != null ? values['return-name'] : s.service.name || '').trim();
            var turn = values['return-turn'] !== false;
            if (!requireId(id, !!services(s.lineId)[id], 'service')) return;
            edit(function () {
              var fresh = { name: name || id, stops: s.service.stops.slice().reverse() };
              if (s.service.circular) fresh.circular = true;
              if (turn && !s.service.circular) { fresh['continues-as'] = s.sid; s.service['continues-as'] = id; }
              lines()[s.lineId].services[id] = fresh;
            });
            state.open = { kind: 'service', line: s.lineId, id: id };
            renderPane();
            toast('Made ' + s.lineId + '/' + id + '.');
          }
        }]
      });
      setTimeout(function () { var f = $('return-id'); if (f) f.focus(); }, 30);
    },

    'delete-board': function (node) {
      var id = data(node, 'id');
      modal({ title: 'Delete board ' + id + '?', narrow: true, body: '<p>Its signs keep their last text.</p>',
        buttons: [{ label: 'Cancel', kind: 'secondary' }, { label: 'Delete board', kind: 'danger', action: function () {
          edit(function () { delete doc().boards[id]; }, { noMap: true }); state.open = null; renderPane();
        } }] });
    },
    'create-board': function () {
      var id = ($('new-id').value || '').trim().toLowerCase(), station = $('new-station').value;
      if (!requireId(id, !!(doc().boards && doc().boards[id]), 'board')) return;
      if (!station) { toast('Choose the station whose departures it shows', 'bad'); return; }
      edit(function () { if (!doc().boards) doc().boards = {}; doc().boards[id] = { station: station, signs: [] }; }, { noMap: true });
      state.creating = null;
      state.open = { kind: 'board', id: id };
      renderPane();
      toast('Add its signs: look at each in game and run /atcs board add ' + id + '.');
    },
    'sign-up': function (node) { moveSign(node, -1); },
    'sign-down': function (node) { moveSign(node, 1); },
    'remove-sign': function (node) { var id = data(node, 'id'), i = +data(node, 'index'); edit(function () { doc().boards[id].signs.splice(i, 1); }, { render: true, noMap: true }); },
    'add-sign': function (node) {
      var id = data(node, 'id'), input = $('board-sign'), text = (input.value || '').trim().replace(/\s+/g, '');
      if (!parsePosition(text)) { input.classList.add('invalid'); toast('Write a sign position as world,x,y,z', 'bad'); return; }
      edit(function () { var b = doc().boards[id]; if (!b.signs) b.signs = []; b.signs.push(text); }, { render: true, noMap: true });
    },

    'add-transfer': function (node) {
      var from = data(node, 'from') || keys(stations())[0], other = keys(stations()).filter(function (id) { return id !== from; })[0];
      if (!from || !other) { toast('Walks need two stations', 'bad'); return; }
      edit(function () { if (!doc().transfers) doc().transfers = []; doc().transfers.push({ between: [from, other], outside: true }); });
      openSection('transfers');
      var pane = $('pane');
      pane.scrollTop = pane.scrollHeight;
    },
    'remove-transfer': function (node) {
      var i = +data(node, 'index');
      edit(function () { doc().transfers.splice(i, 1); if (!doc().transfers.length) delete doc().transfers; }, { render: true });
    },
    'transfer-outside': function (node) {
      var i = +data(node, 'index'), outside = data(node, 'value') === 'outside';
      edit(function () { doc().transfers[i].outside = outside; }, { render: true });
    },

    'add-band': function () { edit(function () { var f = state.faresDoc; if (!f.formula) f.formula = {}; if (!f.formula['distance-bands']) f.formula['distance-bands'] = []; f.formula['distance-bands'].push({ fare: 0 }); }, { render: true, noMap: true }); },
    'remove-band': function (node) { var i = +data(node, 'index'); edit(function () { state.faresDoc.formula['distance-bands'].splice(i, 1); }, { render: true, noMap: true }); },
    'add-zone-fare': function () {
      var a = ($('zone-a').value || '').trim().toLowerCase(), b = ($('zone-b').value || '').trim().toLowerCase(), fare = Number(($('zone-fare').value || '').replace(',', '.'));
      if (!a || !b || isNaN(fare)) { toast('Give both zones and a fare', 'bad'); return; }
      var key = a <= b ? a + '-' + b : b + '-' + a;
      edit(function () { var f = state.faresDoc; if (!f['zone-fares']) f['zone-fares'] = {}; f['zone-fares'][key] = fare; }, { render: true, noMap: true });
    },
    'remove-zone-fare': function (node) { var k = data(node, 'key'); edit(function () { delete state.faresDoc['zone-fares'][k]; }, { render: true, noMap: true }); },
    'add-pair': function () {
      var a = $('pair-a').value, b = $('pair-b').value, fare = Number(($('pair-fare').value || '').replace(',', '.'));
      if (!a || !b || a === b || isNaN(fare) || $('pair-fare').value === '') { toast('Choose two different stations and a fare', 'bad'); return; }
      var key = a <= b ? a + '|' + b : b + '|' + a;
      edit(function () { var f = state.faresDoc; if (!f.pairs) f.pairs = {}; f.pairs[key] = { fare: fare, keep: true }; }, { render: true, noMap: true });
    },
    'remove-pair': function (node) { var k = data(node, 'key'); edit(function () { delete state.faresDoc.pairs[k]; }, { render: true, noMap: true }); },

    'config-file': function (node) {
      var name = data(node, 'value');
      if (name === state.config.name) return;
      var go = function () { state.config = { name: name, text: '', saved: null, version: null, problems: null, loading: false }; renderPane(); };
      if (configDirty()) modal({ title: 'Leave ' + state.config.name + '?', narrow: true, body: '<p>Your edits to it are not saved.</p>', buttons: [{ label: 'Stay', kind: 'secondary' }, { label: 'Drop my edits', kind: 'danger', action: go }] });
      else go();
    },
    'save-config': function () { saveConfig(); },
    'revert-config': function () { state.config.text = state.config.saved; state.config.problems = null; renderPane(true); },

    copy: function (node) {
      var text = data(node, 'text');
      var done = function () { toast('Copied: ' + text); };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(text).then(done, function () { toast(text); });
      else toast(text);
    },
    save: save,
    discard: discard,
    review: review,
    restore: restore,
    'load-theirs': loadTheirs,
    'local-undo': function () { localUndo(false); },
    'local-redo': function () { localUndo(true); },
    'dispatch-hold': function (node) {
      var train = data(node, 'train');
      modal({ title: 'Hold ' + train, narrow: true, body: '<label class="field"><span>Reason shown to riders and in the log</span><input type="text" id="hold-reason" placeholder="Track inspection ahead"></label>',
        buttons: [{ label: 'Cancel', kind: 'secondary' }, { label: 'Hold', kind: 'primary', action: function (values) { order({ action: 'hold', train: train, reason: values['hold-reason'] || '' }, 'Holding ' + train); } }] });
      setTimeout(function () { var f = $('hold-reason'); if (f) f.focus(); }, 30);
    },
    'dispatch-release': function (node) { order({ action: 'release', train: data(node, 'train') }, 'Released ' + data(node, 'train')); },
    'dispatch-suspend': function (node) {
      var line = data(node, 'line');
      modal({ title: 'Suspend ' + lineView(line).name, narrow: true, body: '<p>Boards show Suspended and riders of its trains see the notice. The line keeps running.</p><label class="field"><span>Notice</span><input type="text" id="suspend-notice" placeholder="Please use alternative routes."></label>',
        buttons: [{ label: 'Cancel', kind: 'secondary' }, { label: 'Suspend', kind: 'danger', action: function (values) { order({ action: 'suspend', line: line, notice: values['suspend-notice'] || '' }, 'Suspended ' + lineView(line).name); } }] });
      setTimeout(function () { var f = $('suspend-notice'); if (f) f.focus(); }, 30);
    },
    'dispatch-resume': function (node) { order({ action: 'resume', line: data(node, 'line') }, 'Resumed ' + lineView(data(node, 'line')).name); },
    'dispatch-clear-switch': function (node) { order({ action: 'clear-switch', sign: data(node, 'sign') }, 'The switch follows its rules again'); }
  };

  function moveSign(node, step) {
    var id = data(node, 'id'), i = +data(node, 'index'), signs = doc().boards[id].signs, j = i + step;
    if (j < 0 || j >= signs.length) return;
    edit(function () { var t = signs[i]; signs[i] = signs[j]; signs[j] = t; }, { render: true, noMap: true });
  }

  var changeActions = {
    'stop-station': function (node) {
      var s = stopsOf(node), i = +data(node, 'index'), id = node.value;
      edit(function () { var stop = parseStop(s.service.stops[i]); stop.station = id; stop.platform = null; s.service.stops[i] = formatStop(stop); }, { render: true });
    },
    'stop-platform': function (node) {
      var s = stopsOf(node), i = +data(node, 'index');
      edit(function () { var stop = parseStop(s.service.stops[i]); stop.platform = node.value || null; s.service.stops[i] = formatStop(stop); }, { noMap: true });
    },
    continues: function (node) {
      var lineId = data(node, 'line'), sid = data(node, 'service'), svc = services(lineId)[sid], value = node.value;
      edit(function () {
        if (!value) delete svc['continues-as'];
        else svc['continues-as'] = value.split('/')[0] === lineId ? value.split('/')[1] : value;
      }, { noMap: true });
    },
    'diagram-color': function (node) {
      var id = data(node, 'id');
      edit(function () {
        if (node.checked) {
          if (!doc().map) doc().map = {};
          if (!doc().map.lines) doc().map.lines = {};
          doc().map.lines[id] = lineView(id).color;
        } else {
          setPath(doc(), ['map', 'lines', id], undefined);
          prune(doc(), ['map', 'lines', id]);
        }
      }, { render: true });
    },
    'board-station': function (node) {
      var id = data(node, 'id');
      edit(function () { var b = doc().boards[id]; b.station = node.value; delete b.platforms; }, { render: true, noMap: true });
    },
    'board-all': function (node) {
      var id = data(node, 'id');
      if (!node.checked) { node.checked = true; return; }
      edit(function () { delete doc().boards[id].platforms; }, { render: true, noMap: true });
    },
    'board-platform': function (node) {
      var id = data(node, 'id'), pid = data(node, 'platform');
      edit(function () {
        var b = doc().boards[id], list = (b.platforms || []).filter(function (p) { return p !== pid; });
        if (node.checked) list.push(pid);
        if (list.length) b.platforms = list; else delete b.platforms;
      }, { render: true, noMap: true });
    },
    'dispatch-skip': function (node) {
      var train = data(node, 'train'), station = node.value;
      if (!station) return;
      node.value = '';
      order({ action: 'skip', train: train, station: station }, train + ' will run through ' + stationName(station));
    },
    'dispatch-switch': function (node) {
      var sign = data(node, 'sign'), heading = node.value;
      if (!heading) return;
      node.value = '';
      order({ action: 'switch', sign: sign, heading: heading, minutes: 10 }, 'Set ' + heading + ' at ' + sign + ' for 10 minutes');
    },
    'pair-keep': function (node) {
      var k = data(node, 'key');
      edit(function () {
        var v = state.faresDoc.pairs[k], fare = typeof v === 'object' ? v.fare : v;
        state.faresDoc.pairs[k] = node.checked ? { fare: fare, keep: true } : fare;
      }, { render: true, noMap: true });
    }
  };

  function saveConfig() {
    var c = state.config;
    request('PUT', '/api/config?name=' + encodeURIComponent(c.name), { base: c.version, text: c.text }).then(function (result) {
      state.config = { name: c.name, text: result.text, saved: result.text, version: result.version, problems: result.problems || [], loading: false };
      renderPane(true);
      toast('Saved ' + c.name + ' and reloaded ATCS.');
      return request('GET', '/api/network').then(function (payload) {
        if (!networkDirty()) { applyNetwork(payload, true); changed({ render: false }); }
      });
    }).catch(function (error) {
      if (error.status === 409 && error.detail) {
        modal({ title: c.name + ' changed on disk', body: '<p>' + esc(error.message) + '</p>' + diffHtml(error.detail.text, c.text),
          buttons: [{ label: 'Keep editing', kind: 'secondary' }, { label: 'Load the file on disk', kind: 'danger', action: function () {
            state.config = { name: c.name, text: error.detail.text, saved: error.detail.text, version: error.detail.version, problems: null, loading: false }; renderPane();
          } }] });
      } else {
        toast(error.message, 'bad');
      }
    });
  }

  // ---- Reordering stops ------------------------------------------------------------------------

  var drag = null;

  function startStopDrag(event, handle) {
    var row = handle.closest('.stop'), list = $('stops');
    if (!row || !list) return;
    event.preventDefault();
    var rows = Array.prototype.slice.call(list.querySelectorAll('.stop'));
    drag = { from: +row.getAttribute('data-index'), row: row, rows: rows, startY: event.clientY, to: +row.getAttribute('data-index'), pointer: event.pointerId };
    try { handle.setPointerCapture(event.pointerId); } catch (ignored) { /* not supported */ }
    row.classList.add('dragging');
  }

  function moveStopDrag(event) {
    if (!drag) return;
    var dy = event.clientY - drag.startY;
    drag.row.style.transform = 'translateY(' + dy + 'px)';
    var target = drag.from;
    drag.rows.forEach(function (r, i) {
      r.classList.remove('drop-before', 'drop-after');
      var rect = r.getBoundingClientRect(), mid = rect.top + rect.height / 2;
      if (i < drag.from && event.clientY < mid) target = Math.min(target, i);
      if (i > drag.from && event.clientY > mid) target = Math.max(target, i);
    });
    drag.to = target;
    if (target !== drag.from) drag.rows[target].classList.add(target < drag.from ? 'drop-before' : 'drop-after');
  }

  function endStopDrag() {
    if (!drag) return;
    var d = drag;
    drag = null;
    d.row.style.transform = '';
    d.row.classList.remove('dragging');
    d.rows.forEach(function (r) { r.classList.remove('drop-before', 'drop-after'); });
    if (d.to !== d.from) moveStop(d.from, d.to);
  }

  function moveStop(from, to) {
    var s = stopsOf($('stops'));
    if (!s.service || to < 0 || to >= s.service.stops.length) return;
    edit(function () { var item = s.service.stops.splice(from, 1)[0]; s.service.stops.splice(to, 0, item); }, { render: true });
    var handle = document.querySelector('.stop[data-index="' + to + '"] .handle');
    if (handle) handle.focus();
  }

  // ---- Session and polling ---------------------------------------------------------------------

  function gate(title, html) {
    var existing = document.querySelector('.gate');
    if (existing) existing.remove();
    var node = document.createElement('div');
    node.className = 'gate';
    node.innerHTML = '<div class="gate-card"><header><h1>' + esc(title) + '</h1></header><div class="body">' + html + '</div></div>';
    document.body.appendChild(node);
  }

  var signedOutShown = false;
  function signedOut() {
    if (signedOutShown) return;
    signedOutShown = true;
    try { sessionStorage.removeItem(SESSION_KEY); } catch (ignored) { /* private mode */ }
    session = null;
    gate('Signed out', '<p>' + (dirty() ? '<b>Your unsaved changes could not be saved.</b> ' : '') + 'The editor session has ended: it was unused for a while, or the server restarted.</p>'
      + '<p>Run <code>/atcs editor</code> in game for a new link.</p>');
  }

  function offline(isOffline) {
    if (state.offline === isOffline) return;
    state.offline = isOffline;
    updateChrome();
  }

  var pollTimer = null;
  function poll() {
    clearTimeout(pollTimer);
    pollTimer = setTimeout(poll, 5000);
    if (state.saving || document.hidden || !session) return;
    request('GET', '/api/version').then(function (v) {
      var reloads = [];
      if (v.network !== state.net.version || v.writable !== state.net.writable) {
        if (!networkDirty()) {
          reloads.push(request('GET', '/api/network').then(function (payload) { applyNetwork(payload, true); }));
        } else if (!state.remote.network) {
          state.remote.network = true;
          renderPane(true);
        }
      }
      if (state.fares && v.fares && v.fares !== state.fares.version) {
        if (!faresDirty()) reloads.push(request('GET', '/api/fares').then(function (payload) { applyFares(payload, true); }));
        else if (!state.remote.fares) { state.remote.fares = true; renderPane(true); }
      }
      if (reloads.length) {
        Promise.all(reloads).then(function () {
          changed({ render: !document.activeElement || !document.activeElement.closest || !document.activeElement.closest('#pane') });
          toast('Updated: the network was changed in game or on disk.');
        });
      }
    }).catch(function () { /* offline is shown by the chip */ });
  }

  // ---- Boot ------------------------------------------------------------------------------------

  function bindEvents() {
    document.addEventListener('click', function (event) {
      var node = event.target.closest('[data-act]');
      if (!node || node.closest('.modal-back')) return;
      var act = node.getAttribute('data-act');
      if (actions[act]) { event.preventDefault(); actions[act](node, event); }
    });
    var pane = $('pane');
    pane.addEventListener('input', function (event) {
      var filter = event.target.getAttribute('data-filter');
      if (filter) {
        state.filter[filter] = event.target.value;
        var list = $('list');
        if (!list) return;
        if (filter === 'stations') list.innerHTML = stationRows();
        else if (filter === 'lines') list.innerHTML = lineRows();
        else { var at = event.target.selectionStart; renderPane(true); var again = document.querySelector('[data-filter="' + filter + '"]'); if (again) { again.focus(); again.setSelectionRange(at, at); } }
        return;
      }
      if (event.target.hasAttribute('data-path') && event.target.tagName !== 'SELECT' && event.target.type !== 'checkbox') applyBinding(event.target);
      if (event.target.id === 'new-name' && $('new-id') && !$('new-id').dataset.touched) {
        $('new-id').placeholder = slugify(event.target.value) || 'from the name';
      }
      if (event.target.id === 'new-id') event.target.dataset.touched = '1';
    });
    pane.addEventListener('change', function (event) {
      var node = event.target;
      var act = node.getAttribute('data-act-change');
      if (act && changeActions[act]) { changeActions[act](node); return; }
      if (node.hasAttribute('data-path')) {
        applyBinding(node);
        if (node.tagName === 'SELECT' && node.closest('.walk-card')) renderPane(true);
      }
    });
    pane.addEventListener('keydown', function (event) {
      var enter = event.target.getAttribute && event.target.getAttribute('data-enter');
      if (enter && event.key === 'Enter') { event.preventDefault(); actions[enter](event.target); return; }
      if (event.target.classList && event.target.classList.contains('handle') && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) {
        event.preventDefault();
        var i = +event.target.closest('.stop').getAttribute('data-index');
        moveStop(i, i + (event.key === 'ArrowUp' ? -1 : 1));
      }
    });
    pane.addEventListener('pointerdown', function (event) {
      var handle = event.target.closest && event.target.closest('.stop .handle');
      if (handle && (event.pointerType !== 'mouse' || event.button === 0)) startStopDrag(event, handle);
    });
    document.addEventListener('pointermove', moveStopDrag);
    document.addEventListener('pointerup', endStopDrag);
    document.addEventListener('pointercancel', endStopDrag);
    document.addEventListener('keydown', function (event) {
      var mod = event.ctrlKey || event.metaKey;
      if (!mod) return;
      var key = event.key.toLowerCase();
      if (key === 's') {
        event.preventDefault();
        if (state.section === 'settings' && document.activeElement && document.activeElement.id === 'code') saveConfig();
        else save();
      } else if ((key === 'z' || key === 'y') && !(document.activeElement && document.activeElement.id === 'code')) {
        event.preventDefault();
        localUndo(key === 'y' || event.shiftKey);
      }
    });
    window.addEventListener('beforeunload', function (event) {
      if (dirty() || configDirty()) { event.preventDefault(); event.returnValue = ''; }
    });
    $('zoom-in').addEventListener('click', function () { map.zoomBy(1.4); });
    $('zoom-out').addEventListener('click', function () { map.zoomBy(1 / 1.4); });
    $('zoom-fit').addEventListener('click', function () { map.fit(); });
  }

  function start() {
    $('app').innerHTML = PAGE;
    $('pane').innerHTML = '<div class="pane-inner"><p class="empty">Loading the network&hellip;</p></div>';
    bindEvents();
    map = ATCSMap.create($('map'), $('map-wrap'), {
      onStation: function (id) { openStation(id); },
      onBackground: function () {},
      onMoveStation: function (id, column, row) {
        var before = mapStation(id);
        edit(function () { var e = clone(before); e.column = column; e.row = row; setMapStation(id, e); });
        openStation(id);
        toast('Pinned ' + stationName(id) + ' to column ' + column + ', row ' + row + '.', '', { label: 'Undo', run: function () { localUndo(false); } });
      }
    });
    Promise.all([request('GET', '/api/network'), request('GET', '/api/fares').catch(function () { return null; })]).then(function (results) {
      applyNetwork(results[0], true);
      applyFares(results[1], true);
      updateChrome();
      renderPane();
      relayout();
      poll();
      if (!state.net.writable) openSection('check');
    }).catch(function (error) {
      if (error.status !== 401) gate('Cannot load the network', '<p>' + esc(error.message) + '</p><p>Reload the page to try again.</p>');
    });
  }

  function boot() {
    var hash = location.hash.replace(/^#/, '');
    var token = null;
    hash.split('&').forEach(function (part) { if (part.indexOf('token=') === 0) token = decodeURIComponent(part.slice(6)); });
    try { session = sessionStorage.getItem(SESSION_KEY); } catch (ignored) { session = null; }
    if (token) {
      history.replaceState(null, '', location.pathname + location.search);
      request('POST', '/api/session', { token: token }).then(function (result) {
        session = result.session;
        try { sessionStorage.setItem(SESSION_KEY, session); } catch (ignored) { /* private mode: the session lasts this page */ }
        start();
      }).catch(function (error) {
        if (session) { start(); return; }
        gate('This link has been used', '<p>' + esc(error.message) + '.</p><p>Run <code>/atcs editor</code> in game for a new one; each link works once.</p>');
      });
      return;
    }
    if (session) { start(); return; }
    gate('Railway editor', '<p>Open the editor from the game: run <code>/atcs editor</code> and click the link it gives you. Each link works once, for ten minutes, and signs this browser in.</p>');
  }

  // Exposed for checks in tests.
  window.ATCSEditor = { state: state, actions: actions, relayout: relayout, render: renderPane, save: save };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
