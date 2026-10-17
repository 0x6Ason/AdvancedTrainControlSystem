/* The railway site: loads the network once and the live data every few seconds, draws the map,
 * offers trip options in real time and shows departures and running trains.
 *
 * The page markup is built here rather than in index.html, so a browser holding an old copy of
 * index.html still gets the current page. Every time shown is a countdown element carrying the
 * moment it counts to; a one-second tick only rewrites those numbers.
 *
 * On phones the panel is a bottom sheet laid over the map (peek, half, full). It never changes
 * the map's size, so the map does not move when the sheet's content changes; the part it covers
 * is passed to the map, which fits and reveals within what can be seen. */
(function () {
  'use strict';

  var PAGE = ''
    + '<header class="sign">'
    + '  <div class="sign-left"><h1 id="title">Railway</h1><div class="sign-bullets" id="header-bullets" aria-label="Lines"></div></div>'
    + '  <div class="sign-right"><span class="chip" id="status-chip">WAITING</span><span class="sign-note" id="status-text"></span></div>'
    + '</header>'
    + '<main class="layout" id="layout">'
    + '  <section class="map-wrap" id="map-wrap" aria-label="Network map">'
    + '    <svg id="map" role="img" aria-label="Schematic network map"></svg>'
    + '    <div class="map-tools">'
    + '      <button type="button" id="zoom-in" aria-label="Zoom in" title="Zoom in">+</button>'
    + '      <button type="button" id="zoom-out" aria-label="Zoom out" title="Zoom out">&minus;</button>'
    + '      <button type="button" id="zoom-fit" aria-label="Show the whole network" title="Show the whole network">'
    + '        <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M2 7V2h5M13 2h5v5M18 13v5h-5M7 18H2v-5" fill="none" stroke="currentColor" stroke-width="2.5"/></svg>'
    + '      </button>'
    + '      <button type="button" id="toggle-trains" aria-label="Show trains on the map" title="Show trains on the map" aria-pressed="true">'
    + '        <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><rect x="4" y="2.5" width="12" height="12" rx="3" fill="none" stroke="currentColor" stroke-width="2.2"/><path d="M4 9h12M7 12.5h.01M13 12.5h.01M6.5 17.5l1.5-3M13.5 17.5l-1.5-3" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>'
    + '      </button>'
    + '    </div>'
    + '    <div id="legend" class="legend"></div>'
    + '    <div id="map-empty" class="map-empty" hidden></div>'
    + '  </section>'
    + '  <aside class="panel" id="panel">'
    + '    <div class="grip" id="grip" aria-hidden="true"><span></span></div>'
    + '    <nav class="tabs" id="tabs" role="tablist">'
    + '      <button type="button" role="tab" data-tab="plan" aria-selected="true">Trip</button>'
    + '      <button type="button" role="tab" data-tab="stations" aria-selected="false">Stations</button>'
    + '      <button type="button" role="tab" data-tab="trains" aria-selected="false">Trains</button>'
    + '    </nav>'
    + '    <section class="tab" id="tab-plan" role="tabpanel">'
    + '      <form id="plan-form" class="trip-form" autocomplete="off">'
    + '        <div class="trip-fields">'
    + '          <div class="trip-field" data-field="from"><span class="mark from" aria-hidden="true"></span>'
    + '            <input id="from" placeholder="From" aria-label="From" role="combobox" aria-autocomplete="list" aria-expanded="false" aria-controls="suggest" enterkeyhint="next" spellcheck="false" autocapitalize="off" autocorrect="off">'
    + '            <button type="button" class="clear" data-clear="from" aria-label="Clear" hidden>&times;</button></div>'
    + '          <div class="trip-field" data-field="to"><span class="mark to" aria-hidden="true"></span>'
    + '            <input id="to" placeholder="To" aria-label="To" role="combobox" aria-autocomplete="list" aria-expanded="false" aria-controls="suggest" enterkeyhint="go" spellcheck="false" autocapitalize="off" autocorrect="off">'
    + '            <button type="button" class="clear" data-clear="to" aria-label="Clear" hidden>&times;</button></div>'
    + '        </div>'
    + '        <button type="button" class="swap" id="swap" aria-label="Swap stations" title="Swap stations">'
    + '          <svg viewBox="0 0 20 20" width="20" height="20" aria-hidden="true"><path d="M6 3v13M6 16l-3-3M6 16l3-3M14 17V4M14 4l-3 3M14 4l3 3" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="square"/></svg>'
    + '        </button>'
    + '        <ul id="suggest" class="suggest" role="listbox" hidden></ul>'
    + '      </form>'
    + '      <div id="plan-result" class="plan-result" aria-live="polite"></div>'
    + '    </section>'
    + '    <section class="tab" id="tab-stations" role="tabpanel" hidden>'
    + '      <input id="station-search" type="search" placeholder="Find a station" aria-label="Find a station" enterkeyhint="search" spellcheck="false" autocapitalize="off">'
    + '      <div id="station-detail" class="station-detail" hidden></div>'
    + '      <ul id="station-list" class="station-list"></ul>'
    + '    </section>'
    + '    <section class="tab" id="tab-trains" role="tabpanel" hidden>'
    + '      <div id="train-summary" class="section-head"></div>'
    + '      <ul id="train-list" class="train-list"></ul>'
    + '    </section>'
    + '  </aside>'
    + '</main>';

  var $ = function (id) { return document.getElementById(id); };
  var network = null, map = null, live = null, liveBase = Date.now();
  var selectedStation = null, refreshSeconds = 3;
  var planFrom = null, planTo = null, trips = [], plan = null, chosen = null;
  var stationIndex = {}, lineIndex = {}, serviceIndex = {};
  var phone = window.matchMedia ? window.matchMedia('(max-width: 820px)') : { matches: false };

  function fetchJson(name) {
    if (window.ATCS_DATA && window.ATCS_DATA[name]) return Promise.resolve(window.ATCS_DATA[name]);
    return fetch('data/' + name + '.json?t=' + Date.now(), { cache: 'no-store' }).then(function (response) {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return response.json();
    });
  }

  function escapeHtml(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function stationName(id) { return stationIndex[id] ? stationIndex[id].name : id; }
  function bullet(line, small) { return ATCSMap.bullet(line, small ? 22 : 28); }
  function plural(count, word) { return count + ' ' + word + (count === 1 ? '' : 's'); }

  // ---- Time ----------------------------------------------------------------------------------

  /* Seconds since the moment the live data describes. */
  function now() { return (Date.now() - liveBase) / 1000; }

  /* The wall-clock moment of a time in the live data's seconds. */
  function moment(t) { return Math.round(liveBase + t * 1000); }

  function remaining(at, zero) {
    var left = (at - Date.now()) / 1000;
    return left < 0.5 && zero ? zero : ATCSPlanner.formatClock(left);
  }

  function countdown(t, zero) {
    var at = moment(t);
    return '<span class="cd" data-at="' + at + '"' + (zero ? ' data-zero="' + escapeHtml(zero) + '"' : '') + '>' + escapeHtml(remaining(at, zero)) + '</span>';
  }

  function clockTime(t) {
    var date = new Date(moment(t));
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    return pad(date.getHours()) + ':' + pad(date.getMinutes());
  }

  function tick() {
    var nodes = document.querySelectorAll('.cd');
    for (var i = 0; i < nodes.length; i++) {
      var text = remaining(+nodes[i].getAttribute('data-at'), nodes[i].getAttribute('data-zero'));
      if (nodes[i].textContent !== text) nodes[i].textContent = text;
    }
    if (map && !document.hidden) map.placeTrains(false);
    updateStatus();
  }

  // ---- Trains on the map -----------------------------------------------------------------------

  /* Trains on the map are opt-in: the diagram stays static unless the visitor switches them on. */
  function trainsWanted() {
    try { return localStorage.getItem('atcs-trains') === 'on'; } catch (ignored) { return false; }
  }

  function setTrainsWanted(wanted) {
    try { localStorage.setItem('atcs-trains', wanted ? 'on' : 'off'); } catch (ignored) { /* private mode */ }
    var button = $('toggle-trains');
    if (button) button.setAttribute('aria-pressed', wanted ? 'true' : 'false');
    if (map) map.showTrains(wanted);
  }

  /* The markers for the map: a stopped train at its station, a running one along its stretch. */
  function trainMarkers() {
    var markers = [];
    (live && live.trains ? live.trains : []).forEach(function (train) {
      var entry = serviceIndex[train.key], line = lineIndex[train.line];
      if (!entry || !line) return;
      var stops = entry.service.stops;
      var title = train.name + ': ' + line.name + ' ' + entry.service.name + ' to ' + train.destination;
      if (train.at) {
        markers.push({ id: train.name, color: line.color, title: title + ' (at ' + stationName(train.at) + ')', at: train.at, late: false });
        return;
      }
      if (train.last < 0 || train.next < 0 || train.next >= stops.length || train.last >= stops.length) return;
      var chain = [], i = train.last, guard = 0;
      while (guard++ <= stops.length) {
        chain.push(stops[i].station);
        if (i === train.next) break;
        i = i + 1 < stops.length ? i + 1 : (entry.service.circular ? 0 : -1);
        if (i < 0) return;
      }
      var fraction = Math.max(0, Math.min(0.97, train.fraction || 0));
      var moving = typeof train.eta === 'number' && train.eta > 0 && !train.held && !train.late;
      markers.push({
        id: train.name, color: line.color, line: line.id, chain: chain, fraction: fraction,
        rate: moving ? (0.97 - fraction) / train.eta : 0, since: liveBase, late: !!train.late,
        title: title + (train.late ? ' (delayed)' : train.held ? ' (held)' : '')
      });
    });
    return markers;
  }

  // ---- Saved diagram layout --------------------------------------------------------------------

  function seedKey() { return 'atcs-layout:' + (network && network.title ? network.title : ''); }

  /* The layout to start from: this browser's last one, else the one the server saved; none with ?fresh. */
  function loadSeed() {
    if (/(^|[?&])fresh(=|&|$)/.test(location.search)) return null;
    var seed = null;
    try { seed = JSON.parse(localStorage.getItem(seedKey()) || 'null'); } catch (ignored) { seed = null; }
    if (!seed && network.layout && network.layout.stations) {
      var stations = {};
      Object.keys(network.layout.stations).forEach(function (id) {
        var cell = network.layout.stations[id];
        if (cell && cell.length === 2) stations[id] = { col: cell[0], row: cell[1] };
      });
      seed = { arrangement: network.layout.arrangement, stations: stations };
    }
    if (!seed || !seed.stations) return null;
    var known = 0;
    network.stations.forEach(function (s) { if (seed.stations[s.id]) known++; });
    return known >= Math.ceil(network.stations.length * 0.6) ? seed : null;
  }

  function saveSeed(seed) {
    try { localStorage.setItem(seedKey(), JSON.stringify(seed)); } catch (ignored) { /* private mode or full */ }
  }

  // ---- Bottom sheet (phones) -------------------------------------------------------------------

  var sheet = { state: 'half', height: 0, drag: null };

  function sheetHeights() {
    var total = $('layout').clientHeight || window.innerHeight;
    var tabs = $('tabs').offsetHeight + $('grip').offsetHeight;
    return { peek: tabs + 64, half: Math.round(Math.max(tabs + 160, total * 0.5)), full: Math.max(tabs + 160, total - 44) };
  }

  function applySheetHeight(height, animate) {
    var panel = $('panel');
    panel.classList.toggle('animate', !!animate);
    panel.style.height = Math.round(height) + 'px';
    sheet.height = height;
    if (map) map.setInsets(0, height);
  }

  function setSheet(state, animate) {
    if (!phone.matches) return;
    sheet.state = state;
    $('panel').setAttribute('data-sheet', state);
    applySheetHeight(sheetHeights()[state], animate !== false);
  }

  function layoutChanged() {
    var panel = $('panel');
    if (phone.matches) {
      setSheet(sheet.state, false);
    } else {
      panel.style.height = '';
      panel.removeAttribute('data-sheet');
      if (map) map.setInsets(0, 0);
    }
  }

  /* Drag the grip or the tabs to move the sheet; flick to throw it to the next height. */
  function sheetDrag() {
    var handles = [$('grip'), $('tabs')];
    handles.forEach(function (handle) {
      handle.addEventListener('pointerdown', function (event) {
        if (!phone.matches || (event.pointerType === 'mouse' && event.button !== 0)) return;
        sheet.drag = { id: event.pointerId, y: event.clientY, start: sheet.height, moved: false, last: event.clientY, time: event.timeStamp, speed: 0, handle: handle };
      });
    });
    window.addEventListener('pointermove', function (event) {
      var drag = sheet.drag;
      if (!drag || drag.id !== event.pointerId) return;
      var dy = event.clientY - drag.y;
      if (!drag.moved) {
        if (Math.abs(dy) < 8) return;
        drag.moved = true;
        try { drag.handle.setPointerCapture(event.pointerId); } catch (ignored) { /* not supported */ }
      }
      var heights = sheetHeights();
      var dt = Math.max(1, event.timeStamp - drag.time);
      drag.speed = (event.clientY - drag.last) / dt;
      drag.last = event.clientY;
      drag.time = event.timeStamp;
      applySheetHeight(Math.max(heights.peek, Math.min(heights.full, drag.start - dy)), false);
      event.preventDefault();
    }, { passive: false });
    function end(event) {
      var drag = sheet.drag;
      if (!drag || drag.id !== event.pointerId) return;
      sheet.drag = null;
      if (!drag.moved) {
        if (drag.handle === $('grip')) setSheet(sheet.state === 'full' ? 'half' : sheet.state === 'half' ? 'full' : 'half');
        return;
      }
      // The click a drag may end with must not switch tabs; later taps must still work.
      var handle = drag.handle;
      handle.addEventListener('click', swallow, true);
      setTimeout(function () { handle.removeEventListener('click', swallow, true); }, 350);
      var heights = sheetHeights(), order = ['peek', 'half', 'full'];
      var target;
      if (drag.speed < -0.5) {
        target = order.filter(function (s) { return heights[s] > sheet.height + 1; })[0] || 'full';
      } else if (drag.speed > 0.5) {
        target = order.filter(function (s) { return heights[s] < sheet.height - 1; }).pop() || 'peek';
      } else {
        target = order.reduce(function (best, s) { return Math.abs(heights[s] - sheet.height) < Math.abs(heights[best] - sheet.height) ? s : best; }, 'half');
      }
      setSheet(target);
    }
    function swallow(event) { event.stopPropagation(); event.preventDefault(); }
    window.addEventListener('pointerup', end);
    window.addEventListener('pointercancel', end);
  }

  // ---- Network -------------------------------------------------------------------------------

  function index() {
    stationIndex = {}; lineIndex = {}; serviceIndex = {};
    network.stations.forEach(function (station) { stationIndex[station.id] = station; });
    network.lines.forEach(function (line) {
      lineIndex[line.id] = line;
      line.services.forEach(function (service) { serviceIndex[service.key] = { line: line, service: service }; });
    });
  }

  function renderHeader(layout) {
    $('header-bullets').innerHTML = network.lines.map(function (line) { return bullet(line); }).join('');
    var express = layout && Object.keys(layout.stations).some(function (id) { return layout.stations[id].express; });
    $('legend').innerHTML = network.lines.map(function (line) {
      return '<div class="legend-item' + (line.suspended ? ' suspended' : '') + '">' + bullet(line, true)
        + '<span class="legend-name">' + escapeHtml(line.name) + (line.suspended ? ' (suspended)' : '') + '</span></div>';
    }).join('') + (express
      ? '<div class="legend-item"><svg class="legend-stop" width="22" height="16" viewBox="0 0 22 16" aria-hidden="true">'
        + '<circle cx="11" cy="8" r="5.5" fill="var(--interchange-fill)" stroke="var(--station)" stroke-width="3"/></svg>'
        + '<span class="legend-name">Express trains stop</span></div>'
      : '');
  }

  function stationBullets(id) {
    return ((stationIndex[id] && stationIndex[id].lines) || []).map(function (l) { return bullet(lineIndex[l], true); }).join('');
  }

  function renderStationList(filter) {
    var items = matches(filter, 100);
    $('station-list').innerHTML = items.map(function (station) {
      return '<li data-id="' + escapeHtml(station.id) + '"><span class="name">' + escapeHtml(station.name) + '</span><span class="bullets">'
        + stationBullets(station.id) + '</span></li>';
    }).join('') || '<li class="empty">No station matches.</li>';
  }

  function fold(text) { return String(text || '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim(); }

  /* Stations matching what was typed: names starting with it first, then a word starting with
   * it, then containing it. Everything, in name order, when nothing was typed. */
  function matches(query, limit) {
    var q = fold(query);
    var list = network.stations.map(function (station) {
      var name = fold(station.name), rank;
      if (!q) rank = 0;
      else if (name.indexOf(q) === 0) rank = 0;
      else if ((' ' + name).indexOf(' ' + q) >= 0) rank = 1;
      else if (name.indexOf(q) >= 0) rank = 2;
      else if (station.id.indexOf(q.replace(/ /g, '-')) >= 0) rank = 3;
      else return null;
      return { station: station, rank: rank };
    }).filter(Boolean);
    list.sort(function (a, b) { return a.rank - b.rank || a.station.name.localeCompare(b.station.name); });
    return list.slice(0, limit).map(function (item) { return item.station; });
  }

  function exactStation(text) {
    var query = (text || '').trim().toLowerCase();
    if (!query) return null;
    for (var i = 0; i < network.stations.length; i++) {
      var station = network.stations[i];
      if (station.name.toLowerCase() === query || station.id === query) return station.id;
    }
    return null;
  }

  // ---- Station suggestions for the trip fields -------------------------------------------------

  var suggest = { field: null, items: [], active: -1 };

  function openSuggest(field) {
    if (!network) return;
    suggest.field = field;
    suggest.items = matches($(field).value, 8);
    suggest.active = $(field).value.trim() && suggest.items.length ? 0 : -1;
    var list = $('suggest');
    list.innerHTML = suggest.items.map(function (station, i) {
      return '<li role="option" data-id="' + escapeHtml(station.id) + '"' + (i === suggest.active ? ' aria-selected="true"' : '') + '>'
        + '<span class="name">' + escapeHtml(station.name) + '</span><span class="bullets">' + stationBullets(station.id) + '</span></li>';
    }).join('') || '<li class="empty">No station matches.</li>';
    var row = document.querySelector('.trip-field[data-field="' + field + '"]');
    list.style.top = (row.offsetTop + row.offsetHeight) + 'px';
    list.hidden = false;
    $(field).setAttribute('aria-expanded', 'true');
  }

  function closeSuggest() {
    $('suggest').hidden = true;
    ['from', 'to'].forEach(function (field) { $(field).setAttribute('aria-expanded', 'false'); });
    suggest.field = null;
  }

  function moveSuggest(step) {
    if ($('suggest').hidden || !suggest.items.length) return;
    suggest.active = (suggest.active + step + suggest.items.length) % suggest.items.length;
    $('suggest').querySelectorAll('li[data-id]').forEach(function (li, i) {
      if (i === suggest.active) { li.setAttribute('aria-selected', 'true'); li.scrollIntoView({ block: 'nearest' }); } else li.removeAttribute('aria-selected');
    });
  }

  function chooseStation(field, id) {
    $(field).value = stationName(id);
    updateClearButtons();
    closeSuggest();
    var other = field === 'from' ? 'to' : 'from';
    if (!exactStation($(other).value)) {
      $(other).focus();
      openSuggest(other);
    } else {
      $(field).blur();
    }
    onPlanInput();
  }

  function updateClearButtons() {
    ['from', 'to'].forEach(function (field) {
      document.querySelector('[data-clear="' + field + '"]').hidden = !$(field).value;
    });
  }

  // ---- Stations and departures ---------------------------------------------------------------

  function renderDepartures(stationId) {
    var list = live && live.departures ? live.departures[stationId] || [] : [];
    if (!list.length) return '<p class="empty">' + (live ? 'No trains on their way.' : 'Waiting for live data.') + '</p>';
    return '<ul class="departures">' + list.map(function (d) {
      var line = lineIndex[d.line];
      var eta = d.boarding ? '<span class="now">Boarding</span>'
        : d.late ? '<span class="late">Delayed</span>'
        : typeof d.eta === 'number' ? (d.approximate ? '~' : '') + countdown(d.eta, 'Due') : 'Expected';
      return '<li>' + bullet(line) + '<div><div class="dest">' + escapeHtml(d.destination)
        + (d.platform ? '<span class="platform">' + escapeHtml(d.platform) + '</span>' : '') + '</div><div class="svc">'
        + escapeHtml((line ? line.name + ' ' : '') + d.service + ' · ' + d.train) + '</div></div>'
        + '<div class="eta">' + eta + '</div></li>';
    }).join('') + '</ul>';
  }

  function showStation(id, keepTab) {
    if (!stationIndex[id]) return;
    selectedStation = id;
    var station = stationIndex[id];
    var detail = $('station-detail');
    detail.hidden = false;
    detail.innerHTML = '<header><span class="bullets">' + stationBullets(id) + '</span>'
      + '<h2>' + escapeHtml(station.name) + '</h2><button type="button" class="close" aria-label="Close">&times;</button></header>'
      + '<div class="body"><div id="station-departures">' + renderDepartures(id) + '</div>'
      + renderWalks(id)
      + '<div class="detail-actions"><button type="button" data-plan="from">Trip from here</button><button type="button" data-plan="to">Trip to here</button></div></div>';
    detail.querySelector('.close').addEventListener('click', clearStation);
    detail.querySelectorAll('[data-walk]').forEach(function (button) {
      button.addEventListener('click', function () { showStation(button.getAttribute('data-walk'), true); });
    });
    detail.querySelectorAll('[data-plan]').forEach(function (button) {
      button.addEventListener('click', function () {
        var field = button.getAttribute('data-plan'), other = field === 'from' ? 'to' : 'from';
        $(field).value = station.name;
        updateClearButtons();
        showTab('plan');
        if (!exactStation($(other).value)) {
          setSheet('full');
          $(other).focus();
          openSuggest(other);
        }
        onPlanInput();
      });
    });
    if (!keepTab) showTab('stations');
    if (phone.matches && sheet.state === 'peek') setSheet('half');
    if (map) map.select(id);
    $('tab-stations').scrollTop = 0;
    setHash();
  }

  var WALKER = '<svg class="walker" viewBox="0 0 28 28" width="28" height="28" aria-hidden="true"><circle cx="14" cy="14" r="14" fill="currentColor"/>'
    + '<g fill="none" stroke="var(--paper)" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">'
    + '<path d="M14.5 10.5l-1.2 6 3 3.5 1 4M13.3 16.5l-2.8 3.2-2 4M12.8 12.2l-2.6 2.6-.4 3M14.7 11l2.7 2.6 2.6.8"/></g>'
    + '<circle cx="15.6" cy="7.2" r="2" fill="var(--paper)"/></svg>';
  var WALKER_SMALL = WALKER.replace('width="28" height="28"', 'width="22" height="22"');

  function walkTime(seconds) { return ATCSPlanner.formatDuration(seconds) + ' walk'; }

  /* The stations a walking transfer away from a station, each a button that opens it. */
  function renderWalks(stationId) {
    var walks = (network.transfers || []).filter(function (t) { return t.a === stationId || t.b === stationId; });
    if (!walks.length) return '';
    return '<ul class="walks">' + walks.map(function (t) {
      var other = t.a === stationId ? t.b : t.a;
      return '<li><button type="button" data-walk="' + escapeHtml(other) + '">' + WALKER + '<span class="walk-text"><b>' + escapeHtml(stationName(other))
        + '</b><small>' + escapeHtml(walkTime(t.walk) + (t.outside ? ', outside the station' : ', inside the station')) + '</small></span>'
        + '<span class="bullets">' + stationBullets(other) + '</span></button></li>';
    }).join('') + '</ul>';
  }

  function clearStation() {
    selectedStation = null;
    $('station-detail').hidden = true;
    if (map) map.select(null);
    setHash();
  }

  function refreshStationDetail() {
    if (!selectedStation || $('station-detail').hidden) return;
    var holder = $('station-departures');
    if (holder) holder.innerHTML = renderDepartures(selectedStation);
  }

  // ---- Trains list -----------------------------------------------------------------------------

  function trainWhere(train) {
    var entry = serviceIndex[train.key];
    if (train.at) return 'At ' + escapeHtml(stationName(train.at));
    if (entry && train.next >= 0 && train.next < entry.service.stops.length) {
      var next = escapeHtml(stationName(entry.service.stops[train.next].station));
      if (train.late) return 'Next ' + next + ' <span class="late">delayed</span>';
      return typeof train.eta === 'number' ? 'Next ' + next + ' ' + (train.approximate ? '~' : '') + countdown(train.eta, 'now') : 'Next ' + next;
    }
    return '';
  }

  function renderTrains() {
    var list = live && live.trains ? live.trains.slice() : [];
    list.sort(function (a, b) { return (a.line + a.name).localeCompare(b.line + b.name); });
    $('train-summary').textContent = list.length ? plural(list.length, 'train') + ' in service' : (live ? 'No trains in service' : 'Waiting for live data');
    $('train-list').innerHTML = list.map(function (train) {
      var entry = serviceIndex[train.key];
      return '<li>' + bullet(lineIndex[train.line], true) + '<div class="name">' + escapeHtml(train.name) + '<div class="dest">'
        + escapeHtml((entry ? entry.service.name + ' to ' : 'To ') + train.destination) + '</div></div>'
        + '<span class="eta">' + trainWhere(train) + '</span></li>';
    }).join('');
  }

  // ---- Trip options --------------------------------------------------------------------------

  /* Plans as soon as both fields name a station exactly, for example after picking one. */
  function onPlanInput() {
    updateClearButtons();
    var fromText = $('from').value.trim(), toText = $('to').value.trim();
    if (!fromText || !toText) {
      if (planFrom || planTo) clearPlan();
      return;
    }
    var from = exactStation(fromText), to = exactStation(toText);
    if (from && to && from !== to && (from !== planFrom || to !== planTo)) {
      planFrom = from;
      planTo = to;
      chosen = null;
      runPlan(true);
    } else if (from && to && from === to) {
      $('plan-result').innerHTML = '<p class="plan-error">Those are the same station.</p>';
    }
  }

  function clearPlan() {
    trips = [];
    plan = null;
    planFrom = null;
    planTo = null;
    chosen = null;
    $('plan-result').innerHTML = '';
    if (map) map.highlight(null);
    setHash();
  }

  /* Plans again from the latest live data; called on every update while a trip is shown. A trip
   * picked by the visitor stays picked while it is still on offer. */
  function runPlan(fresh) {
    if (!planFrom || !planTo || !network) return;
    trips = ATCSPlanner.options(network, planFrom, planTo, live, now(), 4);
    plan = trips.filter(function (t) { return t.signature === chosen; })[0] || trips[0] || null;
    if (plan && !trips.some(function (t) { return t.signature === chosen; })) chosen = null;
    renderPlan();
    if (fresh && plan && map) {
      if (phone.matches) setSheet('half');
      map.showRoute(plan.legs);
    }
    setHash();
  }

  var TAGS = { 'fastest': 'Fastest', 'leaves-first': 'Leaves first', 'fewest-changes': 'Fewest changes', 'least-walking': 'Less walking' };

  function firstRide(trip) {
    for (var i = 0; i < trip.legs.length; i++) if (trip.legs[i].type === 'ride') return trip.legs[i];
    return null;
  }

  function routeChain(trip) {
    return trip.legs.map(function (leg) {
      return leg.type === 'walk' ? '<span class="link walk">' + WALKER_SMALL + '<small>' + escapeHtml(ATCSPlanner.formatClock(leg.seconds)) + '</small></span>'
        : '<span class="link">' + bullet(leg.line, true) + '<small>' + escapeHtml(leg.service.name) + '</small></span>';
    }).join('<span class="chev" aria-hidden="true"></span>');
  }

  function routeWhen(trip) {
    var ride = firstRide(trip);
    var walkFirst = trip.legs[0].type === 'walk';
    var lead = walkFirst ? 'Walk now, then ' : '';
    if (!ride) return 'Walk now';
    if (ride.estimated) return lead + (walkFirst ? 'no' : 'No') + ' train in sight yet';
    if (ride.arrives <= now() + 0.5) return lead + (walkFirst ? 'train' : 'Train') + ' boarding, leaves in ' + countdown(ride.departs, 'now');
    return lead + (walkFirst ? 'train' : 'Train') + ' in ' + countdown(ride.arrives, 'now')
      + (ride.platform ? ' <span class="platform">' + escapeHtml(ride.platform) + '</span>' : '');
  }

  function routeFacts(trip) {
    var facts = [trip.transfers ? plural(trip.transfers, 'change') : (trip.walks ? 'No changes' : 'Direct')];
    if (trip.walkSeconds) facts.push(walkTime(trip.walkSeconds));
    var stops = trip.legs.reduce(function (n, leg) { return n + leg.calls; }, 0);
    if (stops) facts.push(plural(stops, 'stop'));
    var fare = fareBetween(planFrom, planTo);
    if (fare !== null) facts.push(fare);
    return facts.join(' · ');
  }

  /* The configured fare between two stations as text, or null when fares are not set up. */
  function fareBetween(fromId, toId) {
    var fares = network && network.fares;
    if (!fares || !fares.configured || !fares.matrix || !fares.matrix[fromId]) return null;
    var amount = fares.matrix[fromId][toId];
    if (typeof amount !== 'number') return null;
    var text = amount === Math.round(amount) ? String(amount) : amount.toFixed(2);
    return (fares.currency || '') + text;
  }

  function legWhen(leg, index, trip) {
    if (leg.estimated) {
      var ready = index === 0 ? now() : trip.legs[index - 1].arrival;
      return '<p class="est">No train on this line can be seen yet. Assuming one in about '
        + escapeHtml(ATCSPlanner.formatDuration(leg.departs - ready)) + '.</p>';
    }
    if (leg.arrives <= now() + 0.5) {
      return '<p class="live"><b>' + escapeHtml(leg.train) + '</b> is at the platform, leaving in ' + countdown(leg.departs, 'now') + '</p>';
    }
    return '<p class="live"><b>' + escapeHtml(leg.train) + '</b> arrives in ' + countdown(leg.arrives, 'now') + '</p>';
  }

  function renderLegs(trip, open) {
    var legs = trip.legs.map(function (leg, i) {
      if (leg.type === 'walk') {
        return '<li class="leg walk">' + WALKER + '<div><h3>Walk to ' + escapeHtml(stationName(leg.to)) + '</h3>'
          + '<p class="where">' + escapeHtml(walkTime(leg.seconds)) + (leg.outside ? ', leaving the station through the gates' : ', inside the station') + '</p>'
          + '<p>Arrive in ' + countdown(leg.arrival, 'now') + '</p></div></li>';
      }
      var toward = leg.service.destination ? ' toward ' + escapeHtml(leg.service.destination) : '';
      var walkedHere = i > 0 && trip.legs[i - 1].type === 'walk';
      var board = '<p class="where">' + (i === 0 || walkedHere ? 'Board at ' : 'Change at ') + escapeHtml(stationName(leg.from))
        + (leg.platform ? ' <span class="platform">' + escapeHtml(leg.platform) + '</span>' : '') + '</p>';
      var ride = '<p>' + plural(leg.calls, 'stop') + ' to ' + escapeHtml(stationName(leg.to)) + ', arriving in ' + countdown(leg.arrival, 'now') + '</p>';
      var becomes = leg.becomes ? '<p class="note">At ' + escapeHtml(stationName(leg.becomes.at)) + ' the train continues as '
        + escapeHtml(leg.becomes.line.name + ' ' + leg.becomes.service.name) + '. Stay on board.</p>' : '';
      var next = leg === firstRide(trip) && trip.following ? '<p class="note">Following train in ' + countdown(trip.following, 'now') + '</p>' : '';
      var list = leg.stops.length > 1 ? '<details data-leg="' + i + '"' + (open[i] ? ' open' : '') + '><summary>Stops</summary><ol>'
        + leg.stops.map(function (stop) { return '<li><span>' + escapeHtml(stationName(stop.station)) + '</span>' + countdown(stop.arr, 'now') + '</li>'; }).join('')
        + '</ol></details>' : '';
      return '<li class="leg" style="--leg-color:' + escapeHtml(leg.line.color) + '">' + bullet(leg.line)
        + '<div><h3>' + escapeHtml(leg.line.name + ' ' + leg.service.name) + toward + '</h3>' + board + legWhen(leg, i, trip) + next + ride + becomes + list + '</div></li>';
    }).join('');
    var finish = '<li class="leg end"><span class="end-mark" aria-hidden="true"></span><div><h3>' + escapeHtml(stationName(planTo))
      + '</h3><p>Arrive ' + clockTime(trip.arrival) + '</p></div></li>';
    return '<ol class="legs">' + legs + finish + '</ol>';
  }

  function renderPlan() {
    var result = $('plan-result');
    var open = {};
    result.querySelectorAll('details[data-leg]').forEach(function (details) {
      if (details.open) open[details.getAttribute('data-leg')] = true;
    });
    if (!plan) {
      result.innerHTML = '<p class="plan-error">No connection between ' + escapeHtml(stationName(planFrom)) + ' and ' + escapeHtml(stationName(planTo)) + '.</p>';
      if (map) map.highlight(null);
      return;
    }
    var cards = trips.map(function (trip) {
      var picked = trip === plan;
      var tags = trip.tags.map(function (tag) { return '<span class="tag' + (tag === 'fastest' ? ' strong' : '') + '">' + TAGS[tag] + '</span>'; });
      if (trip.estimated) tags.push('<span class="tag soft">Estimate</span>');
      return '<li class="route' + (picked ? ' picked' : '') + '">'
        + '<button type="button" class="route-card" data-route="' + escapeHtml(trip.signature) + '" aria-expanded="' + picked + '">'
        + '<span class="route-top"><span class="chain">' + routeChain(trip) + '</span>'
        + '<span class="arrive"><b>' + countdown(trip.arrival, '0:00') + '</b><small>arrive ' + clockTime(trip.arrival) + '</small></span></span>'
        + '<span class="route-when">' + routeWhen(trip) + '</span>'
        + '<span class="route-facts">' + escapeHtml(routeFacts(trip)) + (tags.length ? ' ' + tags.join('') : '') + '</span>'
        + '</button>'
        + (picked ? '<div class="route-detail">' + renderLegs(trip, open) + '</div>' : '')
        + '</li>';
    }).join('');
    result.innerHTML = '<div class="routes-head"><span>' + escapeHtml(stationName(planFrom)) + ' &rarr; ' + escapeHtml(stationName(planTo)) + '</span>'
      + '<span>' + plural(trips.length, 'route') + (live ? ' · live' : '') + '</span></div>'
      + '<ol class="routes">' + cards + '</ol>'
      + (live ? '' : '<p class="note">No live data, so waiting times are assumed.</p>');
    if (map) map.highlight(plan.legs);
  }

  function pickRoute(signature) {
    var trip = trips.filter(function (t) { return t.signature === signature; })[0];
    if (!trip) return;
    if (trip === plan && chosen === signature) return;
    chosen = signature;
    plan = trip;
    renderPlan();
    if (map) map.showRoute(plan.legs);
  }

  // ---- Tabs, link, status ---------------------------------------------------------------------

  function showTab(name) {
    document.querySelectorAll('.tabs [role="tab"]').forEach(function (tab) {
      var active = tab.getAttribute('data-tab') === name;
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
      $('tab-' + tab.getAttribute('data-tab')).hidden = !active;
    });
  }

  function setHash() {
    var parts = [];
    if (planFrom && planTo) parts.push('plan=' + planFrom + ',' + planTo);
    if (selectedStation) parts.push('station=' + selectedStation);
    var hash = parts.length ? '#' + parts.join('&') : '';
    if (location.hash !== hash) history.replaceState(null, '', location.pathname + location.search + hash);
  }

  function readHash() {
    var params = {};
    location.hash.replace(/^#/, '').split('&').forEach(function (part) {
      var eq = part.indexOf('=');
      if (eq > 0) params[part.slice(0, eq)] = decodeURIComponent(part.slice(eq + 1));
    });
    if (params.plan) {
      var ids = params.plan.split(',');
      if (stationIndex[ids[0]] && stationIndex[ids[1]] && ids[0] !== ids[1]) {
        $('from').value = stationIndex[ids[0]].name;
        $('to').value = stationIndex[ids[1]].name;
        updateClearButtons();
        planFrom = ids[0];
        planTo = ids[1];
        runPlan(true);
      }
    }
    if (params.station && stationIndex[params.station]) showStation(params.station, !!params.plan);
  }

  function updateStatus() {
    var chip = $('status-chip');
    var age = live ? (Date.now() - liveBase) / 1000 : Infinity;
    if (!live) {
      chip.className = 'chip';
      chip.textContent = 'WAITING';
      $('status-text').textContent = 'No live data yet';
    } else if (age < refreshSeconds * 3 + 2) {
      chip.className = 'chip live';
      chip.textContent = 'LIVE';
      $('status-text').textContent = plural(live.trains ? live.trains.length : 0, 'train') + ' in service';
    } else {
      chip.className = 'chip stale';
      chip.textContent = 'NO DATA';
      $('status-text').textContent = 'Last update ' + ATCSPlanner.formatDuration(age) + ' ago';
    }
  }

  // ---- Loading ----------------------------------------------------------------------------------

  function loadLive() {
    return fetchJson('live').then(function (data) {
      live = data;
      liveBase = Date.now();
      renderTrains();
      if (map) map.setTrains(trainMarkers());
      refreshStationDetail();
      if (planFrom && planTo) runPlan(false);
      updateStatus();
    }).catch(function () { updateStatus(); });
  }

  function signature(data) {
    return JSON.stringify([data.title, data.stations, data.transfers, data.map || null, data.layout || null, data.lines.map(function (l) {
      return [l.id, l.color, l.name, l.short, l.suspended, l.services.map(function (s) { return [s.key, s.name, s.circular, s.stops]; })];
    })]);
  }

  function applyNetwork(data) {
    network = data;
    index();
    refreshSeconds = network.refreshSeconds || 3;
    document.title = network.title || 'Railway';
    $('title').textContent = network.title || 'Railway';
    var layout = ATCSLayout.build(network, { seed: loadSeed() });
    if (layout.seed) saveSeed(layout.seed);
    var hasMap = Object.keys(layout.stations).length > 0;
    $('map-empty').hidden = hasMap;
    if (!hasMap) {
      $('map-empty').innerHTML = 'No station has a position yet. Stand at each station and run <code>/atcs station locate &lt;id&gt;</code>, or let trains call at them.';
    }
    map.setLayout(layout);
    renderHeader(layout);
    renderStationList($('station-search').value);
    if (selectedStation && stationIndex[selectedStation]) showStation(selectedStation, true);
    if (planFrom && planTo) runPlan(false);
  }

  function boot() {
    var root = $('app') || document.body;
    root.innerHTML = PAGE;
    map = ATCSMap.create($('map'), $('map-wrap'), {
      onStation: function (id) {
        closeSuggest();
        if (phone.matches && sheet.state !== 'half') setSheet('half');
        showStation(id);
      },
      onBackground: function () {
        closeSuggest();
        if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
        if (phone.matches && sheet.state !== 'peek') setSheet('peek');
      }
    });
    layoutChanged();
    if (phone.addEventListener) phone.addEventListener('change', layoutChanged);
    window.addEventListener('resize', function () { if (phone.matches && !sheet.drag) setSheet(sheet.state, false); });
    sheetDrag();

    document.querySelectorAll('.tabs [role="tab"]').forEach(function (tab) {
      tab.addEventListener('click', function () {
        showTab(tab.getAttribute('data-tab'));
        if (phone.matches && sheet.state === 'peek') setSheet('half');
      });
    });

    ['from', 'to'].forEach(function (field) {
      var input = $(field);
      input.addEventListener('focus', function () {
        if (phone.matches) setSheet('full');
        openSuggest(field);
      });
      input.addEventListener('input', function () { openSuggest(field); onPlanInput(); });
      input.addEventListener('blur', function () { setTimeout(function () { if (suggest.field === field && document.activeElement !== input) closeSuggest(); }, 180); });
      input.addEventListener('keydown', function (event) {
        if (event.key === 'ArrowDown') { event.preventDefault(); if ($('suggest').hidden) openSuggest(field); else moveSuggest(1); }
        else if (event.key === 'ArrowUp') { event.preventDefault(); moveSuggest(-1); }
        else if (event.key === 'Escape') { closeSuggest(); }
        else if (event.key === 'Enter') {
          event.preventDefault();
          var pick = suggest.field === field && suggest.items[Math.max(0, suggest.active)];
          if (pick && input.value.trim()) chooseStation(field, pick.id);
        }
      });
    });
    var list = $('suggest');
    list.addEventListener('mousedown', function (event) { event.preventDefault(); });
    list.addEventListener('click', function (event) {
      var item = event.target.closest('li[data-id]');
      if (item && suggest.field) chooseStation(suggest.field, item.getAttribute('data-id'));
    });
    document.querySelectorAll('[data-clear]').forEach(function (button) {
      button.addEventListener('click', function () {
        var field = button.getAttribute('data-clear');
        $(field).value = '';
        onPlanInput();
        $(field).focus();
        openSuggest(field);
      });
    });
    $('plan-form').addEventListener('submit', function (event) { event.preventDefault(); });
    $('swap').addEventListener('click', function () {
      var from = $('from').value;
      $('from').value = $('to').value;
      $('to').value = from;
      planFrom = null;
      planTo = null;
      closeSuggest();
      onPlanInput();
    });
    $('plan-result').addEventListener('click', function (event) {
      var card = event.target.closest('[data-route]');
      if (card) pickRoute(card.getAttribute('data-route'));
    });

    $('station-search').addEventListener('focus', function () { if (phone.matches) setSheet('full'); });
    $('station-search').addEventListener('input', function () { renderStationList($('station-search').value); });
    $('station-list').addEventListener('click', function (event) {
      var item = event.target.closest('li[data-id]');
      if (!item) return;
      $('station-search').blur();
      if (phone.matches) setSheet('half');
      showStation(item.getAttribute('data-id'), true);
    });
    $('zoom-in').addEventListener('click', function () { map.zoomBy(1.4); });
    $('zoom-out').addEventListener('click', function () { map.zoomBy(1 / 1.4); });
    $('zoom-fit').addEventListener('click', function () { map.fit(); });
    setTrainsWanted(trainsWanted());
    $('toggle-trains').addEventListener('click', function () { setTrainsWanted(!trainsWanted()); });
    document.addEventListener('keydown', function (event) {
      var target = event.target;
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return;
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      if (event.key === '+' || event.key === '=') { map.zoomBy(1.4); event.preventDefault(); }
      else if (event.key === '-' || event.key === '_') { map.zoomBy(1 / 1.4); event.preventDefault(); }
      else if (event.key === '0') { map.fit(); event.preventDefault(); }
    });

    fetchJson('network').then(function (data) {
      applyNetwork(data);
      readHash();
      loadLive();
      setInterval(loadLive, refreshSeconds * 1000);
      setInterval(tick, 1000);
      setInterval(function () {
        fetchJson('network').then(function (fresh) {
          if (signature(fresh) !== signature(network)) {
            applyNetwork(fresh);
          } else {
            network = fresh;
            index();
          }
        }).catch(function () { /* keep the current network */ });
      }, 60000);
    }).catch(function (error) {
      $('status-chip').className = 'chip stale';
      $('status-chip').textContent = 'NO DATA';
      $('status-text').textContent = error.message;
      $('map-empty').hidden = false;
      $('map-empty').textContent = 'The railway data has not been written yet. Is ATCS running with the website enabled?';
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
