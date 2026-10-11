/* Added to BlueMap's page by ATCS: a sign-style plaque that opens the railway site, showing the
 * network's route bullets when the data is available. */
(function () {
  'use strict';
  if (document.getElementById('atcs-railway-link')) return;

  function textColor(hex) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#fff';
    var n = parseInt(m[1], 16), r = n >> 16, g = (n >> 8) & 255, b = n & 255;
    return (0.299 * r + 0.587 * g + 0.114 * b) > 170 ? '#000' : '#fff';
  }

  var link = document.createElement('a');
  link.id = 'atcs-railway-link';
  link.href = 'atcs/';
  link.title = 'Railway map, departures and trip planner';
  link.setAttribute('style', [
    'position:fixed', 'left:16px', 'bottom:16px', 'z-index:9999',
    'display:flex', 'align-items:center', 'gap:10px',
    'padding:8px 14px 8px 12px', 'background:#000', 'color:#fff',
    'border:2px solid #fff', 'box-shadow:0 0 0 2px #000',
    'font:700 15px/1 "Helvetica Neue",Helvetica,Arial,sans-serif', 'text-decoration:none', 'letter-spacing:-.01em'
  ].join(';'));
  var bullets = document.createElement('span');
  bullets.setAttribute('style', 'display:flex;gap:4px');
  var text = document.createElement('span');
  text.textContent = 'Railway';
  link.appendChild(bullets);
  link.appendChild(text);
  document.body.appendChild(link);

  try {
    fetch('atcs/data/network.json?t=' + Date.now(), { cache: 'no-store' }).then(function (r) { return r.json(); }).then(function (network) {
      if (network && network.title) { text.textContent = network.title; link.title = network.title + ': map, departures and trip planner'; }
      (network.lines || []).slice(0, 8).forEach(function (line) {
        var label = line.short || line.id;
        var b = document.createElement('span');
        b.textContent = label;
        b.setAttribute('style', 'display:inline-flex;align-items:center;justify-content:center;min-width:22px;height:22px;padding:0 ' + (label.length > 2 ? '7px' : '0')
          + ';border-radius:11px;background:' + line.color + ';color:' + textColor(line.color) + ';font-size:12px;font-weight:700');
        bullets.appendChild(b);
      });
    }).catch(function () {});
  } catch (ignored) {}
})();
