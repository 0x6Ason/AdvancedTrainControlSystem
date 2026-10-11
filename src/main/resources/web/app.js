/* The railway site: loads the network once and the live data every few seconds, draws the map,
 * plans trips in real time and shows departures and running trains.
 *
 * The page markup is built here rather than in index.html, so a browser holding an old copy of
 * index.html still gets the current page. Every time shown is a countdown element carrying the
 * moment it counts to; a one-second tick only rewrites those numbers. */
(function () {
  'use strict';

  var PAGE = ''
    + '<header class="sign">'
    + '  <div class="sign-left"><h1 id="title">Railway</h1><div class="sign-bullets" id="header-bullets" aria-label="Lines"></div></div>'
    + '  <div class="sign-right"><span class="chip" id="status-chip">WAITING</span><span class="sign-note" id="status-text"></span></div>'
    + '</header>'
    + '<main class="layout">'
    + '  <section class="map-wrap" id="map-wrap" aria-label="Network map" tabindex="0">'
    + '    <svg id="map" role="img" aria-label="Schematic network map"></svg>'
    + '    <div class="map-tools">'
    + '      <button type="button" id="zoom-in" aria-label="Zoom in" title="Zoom in">+</button>'
    + '      <button type="button" id="zoom-out" aria-label="Zoom out" title="Zoom out">&minus;</button>'
    + '      <button type="button" id="zoom-fit" aria-label="Show the whole network" title="Show the whole network">'
    + '        <svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M2 7V2h5M13 2h5v5M18 13v5h-5M7 18H2v-5" fill="none" stroke="currentColor" stroke-width="2.5"/></svg>'
    + '      </button>'
    + '    </div>'
    + '    <div id="legend" class="legend"></div>'
    + '    <div id="map-empty" class="map-empty" hidden></div>'
    + '  </section>'
    + '  <aside class="panel">'
    + '    <nav class="tabs" role="tablist">'
    + '      <button type="button" role="tab" data-tab="plan" aria-selected="true">Trip</button>'
    + '      <button type="button" role="tab" data-tab="stations" aria-selected="false">Stations</button>'
    + '      <button type="button" role="tab" data-tab="trains" aria-selected="false">Trains</button>'
    + '    </nav>'
    + '    <section class="tab" id="tab-plan" role="tabpanel">'
    + '      <form id="plan-form" autocomplete="off">'
    + '        <label class="field"><span>From</span><input id="from" list="station-names" placeholder="Station" required></label>'
    + '        <label class="field"><span>To</span><input id="to" list="station-names" placeholder="Station" required></label>'
    + '        <datalist id="station-names"></datalist>'
    + '        <div class="form-row">'
    + '          <button type="button" class="button secondary" id="swap" aria-label="Swap stations">&#8645; Swap</button>'
    + '          <button type="submit" class="button">Plan trip</button>'
    + '        </div>'
    + '      </form>'
    + '      <div id="plan-result" class="plan-result" aria-live="polite"></div>'
    + '    </section>'
    + '    <section class="tab" id="tab-stations" role="tabpanel" hidden>'
    + '      <input id="station-search" type="search" placeholder="Find a station" aria-label="Find a station">'
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
  var selectedStation = null, plan = null, planFrom = null, planTo = null, refreshSeconds = 3;
  var stationIndex = {}, lineIndex = {}, serviceIndex = {};

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
    updateStatus();
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

  function renderHeader() {
    $('header-bullets').innerHTML = network.lines.map(function (line) { return bullet(line); }).join('');
    $('legend').innerHTML = network.lines.map(function (line) {
      return '<div class="legend-item' + (line.suspended ? ' suspended' : '') + '">' + bullet(line, true)
        + '<span class="legend-name">' + escapeHtml(line.name) + (line.suspended ? ' (suspended)' : '') + '</span></div>';
    }).join('');
  }

  function renderStationList(filter) {
    var query = (filter || '').trim().toLowerCase();
    var items = network.stations.slice().sort(function (a, b) { return a.name.localeCompare(b.name); })
      .filter(function (station) { return !query || station.name.toLowerCase().indexOf(query) >= 0 || station.id.indexOf(query) >= 0; });
    $('station-list').innerHTML = items.map(function (station) {
      return '<li data-id="' + escapeHtml(station.id) + '"><span class="name">' + escapeHtml(station.name) + '</span><span class="bullets">'
        + (station.lines || []).map(function (id) { return bullet(lineIndex[id], true); }).join('') + '</span></li>';
    }).join('') || '<li class="empty">No station matches.</li>';
  }

  function fillDatalist() {
    $('station-names').innerHTML = network.stations.map(function (station) {
      return '<option value="' + escapeHtml(station.name) + '"></option>';
    }).join('');
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

  function looseStation(text) {
    var exact = exactStation(text);
    if (exact) return exact;
    var query = (text || '').trim().toLowerCase();
    if (!query) return null;
    var starts = network.stations.filter(function (s) { return s.name.toLowerCase().indexOf(query) === 0; });
    if (starts.length === 1) return starts[0].id;
    var contains = network.stations.filter(function (s) { return s.name.toLowerCase().indexOf(query) >= 0; });
    return contains.length === 1 ? contains[0].id : null;
  }

  // ---- Stations and departures ---------------------------------------------------------------

  function renderDepartures(stationId) {
    var list = live && live.departures ? live.departures[stationId] || [] : [];
    if (!list.length) return '<p class="empty">' + (live ? 'No trains on their way.' : 'Waiting for live data.') + '</p>';
    return '<ul class="departures">' + list.map(function (d) {
      var line = lineIndex[d.line];
      var eta = d.boarding ? '<span class="now">Boarding</span>'
        : typeof d.eta === 'number' ? countdown(d.eta, 'Due') : 'Expected';
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
    detail.innerHTML = '<header><span class="bullets">' + (station.lines || []).map(function (l) { return bullet(lineIndex[l], true); }).join('') + '</span>'
      + '<h2>' + escapeHtml(station.name) + '</h2><button type="button" class="close" aria-label="Close">&times;</button></header>'
      + '<div class="body"><div id="station-departures">' + renderDepartures(id) + '</div>'
      + '<div class="detail-actions"><button type="button" data-plan="from">Trip from here</button><button type="button" data-plan="to">Trip to here</button></div></div>';
    detail.querySelector('.close').addEventListener('click', clearStation);
    detail.querySelectorAll('[data-plan]').forEach(function (button) {
      button.addEventListener('click', function () {
        $(button.getAttribute('data-plan')).value = station.name;
        showTab('plan');
        onPlanInput();
      });
    });
    if (map) map.select(id);
    if (!keepTab) showTab('stations');
    setHash();
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
      return typeof train.eta === 'number' ? 'Next ' + next + ' ' + countdown(train.eta, 'now') : 'Next ' + next;
    }
    return '';
  }

  function renderTrains() {
    var trains = live && live.trains ? live.trains.slice() : [];
    trains.sort(function (a, b) { return (a.line + a.name).localeCompare(b.line + b.name); });
    $('train-summary').textContent = trains.length ? plural(trains.length, 'train') + ' in service' : (live ? 'No trains in service' : 'Waiting for live data');
    $('train-list').innerHTML = trains.map(function (train) {
      var entry = serviceIndex[train.key];
      return '<li>' + bullet(lineIndex[train.line], true) + '<div class="name">' + escapeHtml(train.name) + '<div class="dest">'
        + escapeHtml((entry ? entry.service.name + ' to ' : 'To ') + train.destination) + '</div></div>'
        + '<span class="eta">' + trainWhere(train) + '</span></li>';
    }).join('');
  }

  // ---- Trip planner ---------------------------------------------------------------------------

  /* Plans as soon as both fields name a station exactly, for example after picking from the list. */
  function onPlanInput() {
    var fromText = $('from').value.trim(), toText = $('to').value.trim();
    if (!fromText || !toText) {
      if (planFrom || planTo) clearPlan();
      return;
    }
    var from = exactStation(fromText), to = exactStation(toText);
    if (from && to && from !== to && (from !== planFrom || to !== planTo)) {
      planFrom = from;
      planTo = to;
      runPlan();
    }
  }

  function submitPlan() {
    var from = looseStation($('from').value), to = looseStation($('to').value);
    var result = $('plan-result');
    if (!from || !to) {
      result.innerHTML = '<p class="plan-error">' + (!from ? 'Pick the starting station from the list.' : 'Pick the destination from the list.') + '</p>';
      return;
    }
    if (from === to) {
      result.innerHTML = '<p class="plan-error">Those are the same station.</p>';
      return;
    }
    $('from').value = stationName(from);
    $('to').value = stationName(to);
    planFrom = from;
    planTo = to;
    runPlan();
  }

  function clearPlan() {
    plan = null;
    planFrom = null;
    planTo = null;
    $('plan-result').innerHTML = '';
    if (map) map.highlight(null);
    setHash();
  }

  /* Plans again from the latest live data; called on every update while a trip is shown. */
  function runPlan() {
    if (!planFrom || !planTo || !network) return;
    plan = ATCSPlanner.plan(network, planFrom, planTo, live, now());
    renderPlan();
    setHash();
  }

  function legWhen(leg, index) {
    if (leg.estimated) {
      var ready = index === 0 ? now() : plan.legs[index - 1].arrival;
      return '<p class="est">No train on this line can be seen yet. Assuming one in about '
        + escapeHtml(ATCSPlanner.formatDuration(leg.departs - ready)) + '.</p>';
    }
    if (leg.arrives <= now() + 0.5) {
      return '<p class="live"><b>' + escapeHtml(leg.train) + '</b> is at the platform, leaving in ' + countdown(leg.departs, 'now') + '</p>';
    }
    return '<p class="live"><b>' + escapeHtml(leg.train) + '</b> arrives in ' + countdown(leg.arrives, 'now') + '</p>';
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
    var stops = plan.legs.reduce(function (n, leg) { return n + leg.calls; }, 0);
    var legs = plan.legs.map(function (leg, i) {
      var toward = leg.service.destination ? ' toward ' + escapeHtml(leg.service.destination) : '';
      var board = '<p class="where">' + (i === 0 ? 'Board at ' : 'Change at ') + escapeHtml(stationName(leg.from))
        + (leg.platform ? ' <span class="platform">' + escapeHtml(leg.platform) + '</span>' : '') + '</p>';
      var ride = '<p>' + plural(leg.calls, 'stop') + ' to ' + escapeHtml(stationName(leg.to)) + ', arriving in ' + countdown(leg.arrival, 'now') + '</p>';
      var becomes = leg.becomes ? '<p class="note">At ' + escapeHtml(stationName(leg.becomes.at)) + ' the train continues as '
        + escapeHtml(leg.becomes.line.name + ' ' + leg.becomes.service.name) + '. Stay on board.</p>' : '';
      var list = leg.stops.length > 1 ? '<details data-leg="' + i + '"' + (open[i] ? ' open' : '') + '><summary>Stops</summary><ol>'
        + leg.stops.map(function (stop) { return '<li><span>' + escapeHtml(stationName(stop.station)) + '</span>' + countdown(stop.arr, 'now') + '</li>'; }).join('')
        + '</ol></details>' : '';
      return '<li class="leg" style="--leg-color:' + escapeHtml(leg.line.color) + '">' + bullet(leg.line)
        + '<div><h3>' + escapeHtml(leg.line.name + ' ' + leg.service.name) + toward + '</h3>' + board + legWhen(leg, i) + ride + becomes + list + '</div></li>';
    }).join('');
    var finish = '<li class="leg end"><span class="end-mark" aria-hidden="true"></span><div><h3>' + escapeHtml(stationName(planTo))
      + '</h3><p>Arrive ' + clockTime(plan.arrival) + '</p></div></li>';
    result.innerHTML = '<div class="plan-summary"><div class="eyebrow">' + (live ? 'Live · arrive in' : 'Arrive in') + '</div>'
      + '<div class="time">' + countdown(plan.arrival, '0:00') + '</div>'
      + '<div class="meta">' + escapeHtml(stationName(planFrom)) + ' &rarr; ' + escapeHtml(stationName(planTo)) + '</div>'
      + '<div class="sub">Arrive ' + clockTime(plan.arrival) + ' · ' + (plan.transfers ? plural(plan.transfers, 'change') : 'No changes') + ' · ' + plural(stops, 'stop') + '</div></div>'
      + '<ol class="legs">' + legs + finish + '</ol>'
      + (live ? '' : '<p class="note">No live data, so waiting times are assumed.</p>');
    if (map) map.highlight(plan.legs);
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
        planFrom = ids[0];
        planTo = ids[1];
        runPlan();
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
      refreshStationDetail();
      if (planFrom && planTo) runPlan();
      updateStatus();
    }).catch(function () { updateStatus(); });
  }

  function signature(data) {
    return JSON.stringify([data.title, data.stations, data.lines.map(function (l) {
      return [l.id, l.color, l.name, l.short, l.suspended, l.services.map(function (s) { return [s.key, s.name, s.circular, s.stops]; })];
    })]);
  }

  function applyNetwork(data) {
    network = data;
    index();
    refreshSeconds = network.refreshSeconds || 3;
    document.title = network.title || 'Railway';
    $('title').textContent = network.title || 'Railway';
    var layout = ATCSLayout.build(network);
    var hasMap = Object.keys(layout.stations).length > 0;
    $('map-empty').hidden = hasMap;
    if (!hasMap) {
      $('map-empty').innerHTML = 'No station has a position yet. Stand at each station and run <code>/atcs station locate &lt;id&gt;</code>, or let trains call at them.';
    }
    map.setLayout(layout);
    renderHeader();
    fillDatalist();
    renderStationList($('station-search').value);
    if (selectedStation && stationIndex[selectedStation]) showStation(selectedStation, true);
    if (planFrom && planTo) runPlan();
  }

  function boot() {
    var root = $('app') || document.body;
    root.innerHTML = PAGE;
    map = ATCSMap.create($('map'), $('map-wrap'), { onStation: function (id) { showStation(id); } });

    document.querySelectorAll('.tabs [role="tab"]').forEach(function (tab) {
      tab.addEventListener('click', function () { showTab(tab.getAttribute('data-tab')); });
    });
    $('plan-form').addEventListener('submit', function (event) { event.preventDefault(); submitPlan(); });
    $('from').addEventListener('input', onPlanInput);
    $('to').addEventListener('input', onPlanInput);
    $('swap').addEventListener('click', function () {
      var from = $('from').value;
      $('from').value = $('to').value;
      $('to').value = from;
      planFrom = null;
      planTo = null;
      onPlanInput();
    });
    $('station-search').addEventListener('input', function () { renderStationList($('station-search').value); });
    $('station-list').addEventListener('click', function (event) {
      var item = event.target.closest('li[data-id]');
      if (item) showStation(item.getAttribute('data-id'), true);
    });
    $('zoom-in').addEventListener('click', function () { map.zoomBy(1.4); });
    $('zoom-out').addEventListener('click', function () { map.zoomBy(1 / 1.4); });
    $('zoom-fit').addEventListener('click', function () { map.fit(); });
    $('map-wrap').addEventListener('keydown', function (event) {
      if (event.target !== $('map-wrap')) return;
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
