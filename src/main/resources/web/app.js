/* The railway site: loads the network once and the live data every few seconds, draws the map,
 * answers trip searches and shows departures and running trains. */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var network = null, layout = null, map = null, live = null, liveReceived = 0;
  var selectedStation = null, currentPlan = null, refreshSeconds = 3;
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

  function bullet(line, small) {
    if (!line) return '';
    var text = line.short || line.id;
    var classes = 'bullet' + (small ? ' small' : '') + (text.length > 2 ? ' wide' : '') + (ATCSMap.textOn(line.color) === '#000' ? ' dark' : '');
    return '<span class="' + classes + '" style="background:' + escapeHtml(line.color) + '" title="' + escapeHtml(line.name) + '">' + escapeHtml(text) + '</span>';
  }

  function duration(seconds) { return ATCSPlanner.formatDuration(seconds); }
  function liveAge() { return liveReceived ? (Date.now() - liveReceived) / 1000 : Infinity; }
  function etaNow(eta) { return eta === null || eta === undefined ? null : Math.max(0, eta - liveAge()); }

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
      return '<span class="' + (line.suspended ? 'suspended' : '') + '">' + bullet(line, true) + escapeHtml(line.name) + (line.suspended ? ' (suspended)' : '') + '</span>';
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

  function resolveStation(text) {
    var query = (text || '').trim().toLowerCase();
    if (!query) return null;
    var exact = network.stations.filter(function (s) { return s.name.toLowerCase() === query || s.id === query; });
    if (exact.length) return exact[0].id;
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
      var remaining = etaNow(d.eta);
      var eta = d.boarding ? 'Boarding' : (remaining === null ? 'Expected' : duration(remaining));
      return '<li>' + bullet(line) + '<div><div class="dest">' + escapeHtml(d.destination)
        + (d.platform ? '<span class="platform">' + escapeHtml(d.platform) + '</span>' : '') + '</div><div class="svc">'
        + escapeHtml((line ? line.name + ' ' : '') + d.service + ' · ' + d.train) + '</div></div>'
        + '<div class="eta' + (d.boarding || (remaining !== null && remaining < 20) ? ' now' : '') + '">' + escapeHtml(eta) + '</div></li>';
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
        if ($('from').value && $('to').value) planTrip();
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

  // ---- Trains --------------------------------------------------------------------------------

  function trainWhere(train) {
    var entry = serviceIndex[train.key];
    if (train.at) return 'At ' + stationName(train.at);
    if (entry && train.next >= 0 && train.next < entry.service.stops.length) {
      var eta = etaNow(train.eta);
      return 'Next ' + stationName(entry.service.stops[train.next].station) + (eta === null ? '' : ' in ' + duration(eta));
    }
    return '';
  }

  function renderTrains() {
    var trains = live && live.trains ? live.trains.slice() : [];
    trains.sort(function (a, b) { return (a.line + a.name).localeCompare(b.line + b.name); });
    $('train-summary').textContent = trains.length ? trains.length + ' train' + (trains.length === 1 ? '' : 's') + ' in service' : (live ? 'No trains in service' : 'Waiting for live data');
    $('train-list').innerHTML = trains.map(function (train) {
      var entry = serviceIndex[train.key];
      return '<li>' + bullet(lineIndex[train.line], true) + '<div class="name">' + escapeHtml(train.name) + '<div class="dest">' + escapeHtml((entry ? entry.service.name + ' to ' : 'To ') + train.destination) + '</div></div>'
        + '<span class="eta">' + escapeHtml(trainWhere(train)) + '</span></li>';
    }).join('');
  }

  var tooltip = $('tooltip');
  function trainTooltip(train, event) {
    if (!train) { tooltip.hidden = true; return; }
    var entry = serviceIndex[train.key];
    tooltip.innerHTML = '<b>' + escapeHtml(train.name) + '</b>' + escapeHtml((entry ? entry.line.name + ' ' + entry.service.name : '') + ' to ' + train.destination)
      + '<br><small>' + escapeHtml(trainWhere(train)) + '</small>';
    tooltip.hidden = false;
    var wrap = $('map').parentNode.getBoundingClientRect();
    var x = event.clientX - wrap.left + 14, y = event.clientY - wrap.top + 14;
    if (x + 280 > wrap.width) x = Math.max(8, event.clientX - wrap.left - 290);
    tooltip.style.left = x + 'px';
    tooltip.style.top = y + 'px';
  }

  // ---- Planner --------------------------------------------------------------------------------

  function planTrip() {
    var from = resolveStation($('from').value), to = resolveStation($('to').value);
    var result = $('plan-result');
    if (!from || !to) {
      result.innerHTML = '<p class="plan-error">' + (!from ? 'Pick the starting station from the list.' : 'Pick the destination from the list.') + '</p>';
      return;
    }
    if (from === to) {
      result.innerHTML = '<p class="plan-error">Those are the same station.</p>';
      return;
    }
    currentPlan = ATCSPlanner.plan(network, from, to, live);
    if (!currentPlan) {
      result.innerHTML = '<p class="plan-error">No connection between ' + escapeHtml(stationName(from)) + ' and ' + escapeHtml(stationName(to)) + '.</p>';
      if (map) map.highlight(null);
      return;
    }
    var legs = currentPlan.legs.map(function (leg, i) {
      var wait = leg.wait === null || leg.wait === undefined ? null : etaNow(leg.wait);
      var destination = leg.service.destination || leg.service.name;
      var head = leg.sameTrain
        ? 'Stay on board. The train continues as ' + escapeHtml(leg.line.name + ' ' + leg.service.name)
        : escapeHtml(leg.line.name + ' ' + leg.service.name) + ' toward ' + escapeHtml(destination);
      var board = leg.sameTrain ? '' : '<p class="where">' + (i === 0 ? 'Board at ' : 'Change at ') + escapeHtml(stationName(leg.from)) + '</p>';
      return '<li class="leg" style="--leg-color:' + escapeHtml(leg.line.color) + '">' + bullet(leg.line)
        + '<div><h3>' + head + '</h3>' + board
        + '<p>' + leg.calls + ' stop' + (leg.calls === 1 ? '' : 's') + ' to ' + escapeHtml(stationName(leg.to)) + ' · ' + duration(leg.seconds) + '</p>'
        + (wait !== null && !leg.sameTrain ? '<p class="live">Next train in ' + duration(wait) + '</p>' : '')
        + (leg.stops.length > 2 ? '<details><summary>Stops</summary><ol>' + leg.stops.slice(1).map(function (s) { return '<li>' + escapeHtml(stationName(s)) + '</li>'; }).join('') + '</ol></details>' : '')
        + '</div></li>';
    }).join('');
    var changes = currentPlan.transfers;
    result.innerHTML = '<div class="plan-summary"><div class="time">' + duration(currentPlan.totalSeconds) + '</div>'
      + '<div class="meta">' + escapeHtml(stationName(from)) + ' &rarr; ' + escapeHtml(stationName(to)) + '</div>'
      + '<div class="sub">' + (changes ? changes + ' change' + (changes === 1 ? '' : 's') : 'No changes') + ' · ' + currentPlan.legs.reduce(function (n, l) { return n + l.calls; }, 0) + ' stops</div></div>'
      + '<ol class="legs">' + legs + '</ol>'
      + (currentPlan.estimated ? '<p class="note">Some stretches have not been timed by a train yet, so part of this is an estimate.</p>' : '');
    if (map) map.highlight(currentPlan.legs);
    setHash();
  }

  // ---- Tabs, hash, status ---------------------------------------------------------------------

  function showTab(name) {
    document.querySelectorAll('.tabs [role="tab"]').forEach(function (tab) {
      var active = tab.getAttribute('data-tab') === name;
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
      $('tab-' + tab.getAttribute('data-tab')).hidden = !active;
    });
  }

  function setHash() {
    var parts = [];
    if (currentPlan) parts.push('plan=' + currentPlan.legs[0].from + ',' + currentPlan.legs[currentPlan.legs.length - 1].to);
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
      if (stationIndex[ids[0]] && stationIndex[ids[1]]) {
        $('from').value = stationIndex[ids[0]].name;
        $('to').value = stationIndex[ids[1]].name;
        planTrip();
      }
    }
    if (params.station && stationIndex[params.station]) showStation(params.station, !!params.plan);
  }

  function updateStatus() {
    var chip = $('status-chip');
    var age = liveAge();
    if (!live) {
      chip.className = 'chip';
      chip.textContent = 'WAITING';
      $('status-text').textContent = 'No live data yet';
    } else if (age < refreshSeconds * 3 + 2) {
      chip.className = 'chip live';
      chip.textContent = 'LIVE';
      $('status-text').textContent = (live.trains ? live.trains.length : 0) + ' trains in service';
    } else {
      chip.className = 'chip stale';
      chip.textContent = 'NO DATA';
      $('status-text').textContent = 'Last update ' + duration(age) + ' ago';
    }
  }

  // ---- Loading ----------------------------------------------------------------------------------

  function loadLive() {
    return fetchJson('live').then(function (data) {
      live = data;
      liveReceived = Date.now();
      if (map) map.setTrains(live.trains, network);
      renderTrains();
      refreshStationDetail();
      updateStatus();
    }).catch(function () { updateStatus(); });
  }

  function signature(data) {
    return JSON.stringify([data.title, data.stations, data.lines.map(function (l) {
      return [l.id, l.color, l.name, l.short, l.suspended, l.services.map(function (s) { return [s.key, s.name, s.circular, s.stops]; })];
    })]);
  }

  function loadNetwork() {
    return fetchJson('network').then(function (data) {
      network = data;
      index();
      refreshSeconds = network.refreshSeconds || 3;
      document.title = network.title || 'Railway';
      $('title').textContent = network.title || 'Railway';
      layout = ATCSLayout.build(network);
      var hasMap = Object.keys(layout.stations).length > 0;
      $('map-empty').hidden = hasMap;
      if (!hasMap) $('map-empty').innerHTML = 'No station has a position yet. Stand at each station and run <code>/atcs station locate &lt;id&gt;</code>, or let trains call at them.';
      map = ATCSMap.create($('map'), layout, { onStation: function (id) { showStation(id); }, onTrainHover: trainTooltip });
      renderHeader();
      fillDatalist();
      renderStationList($('station-search').value);
      if (live && map) map.setTrains(live.trains, network);
      if (selectedStation) showStation(selectedStation, true);
      if (currentPlan && map) map.highlight(currentPlan.legs);
    });
  }

  function boot() {
    document.querySelectorAll('.tabs [role="tab"]').forEach(function (tab) {
      tab.addEventListener('click', function () { showTab(tab.getAttribute('data-tab')); });
    });
    $('plan-form').addEventListener('submit', function (event) { event.preventDefault(); planTrip(); });
    $('swap').addEventListener('click', function () {
      var from = $('from').value; $('from').value = $('to').value; $('to').value = from;
      if ($('from').value && $('to').value) planTrip();
    });
    $('station-search').addEventListener('input', function () { renderStationList($('station-search').value); });
    $('station-list').addEventListener('click', function (event) {
      var item = event.target.closest('li[data-id]');
      if (item) showStation(item.getAttribute('data-id'), true);
    });
    $('zoom-in').addEventListener('click', function () { if (map) map.zoomBy(1.3); });
    $('zoom-out').addEventListener('click', function () { if (map) map.zoomBy(1 / 1.3); });
    $('zoom-fit').addEventListener('click', function () { if (map) map.fit(); });

    loadNetwork().then(function () {
      readHash();
      loadLive();
      setInterval(loadLive, refreshSeconds * 1000);
      setInterval(function () { refreshStationDetail(); renderTrains(); updateStatus(); }, 1000);
      setInterval(function () {
        fetchJson('network').then(function (data) {
          if (signature(data) !== signature(network)) loadNetwork(); else { network = data; index(); }
        }).catch(function () {});
      }, 60000);
    }).catch(function (error) {
      $('status-chip').className = 'chip stale';
      $('status-chip').textContent = 'NO DATA';
      $('status-text').textContent = error.message;
      $('map-empty').hidden = false;
      $('map-empty').textContent = 'The railway data has not been written yet. Is ATCS running with the website enabled?';
    });
  }

  boot();
})();
