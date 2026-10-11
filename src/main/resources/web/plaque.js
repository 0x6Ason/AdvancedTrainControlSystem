/* The railway plaque on BlueMap's page, drawn like a subway entrance sign: a black panel with a
 * thin white rule across the top, the network's name, and its route bullets. */
(function () {
  'use strict';
  if (document.getElementById('atcs-railway-link')) return;

  var SVG = 'http://www.w3.org/2000/svg';
  var FONT = '"Helvetica Neue",Helvetica,Arial,sans-serif';

  function textColor(hex) {
    var m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
    if (!m) return '#fff';
    var n = parseInt(m[1], 16);
    return (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) > 170 ? '#000' : '#fff';
  }

  function svg(name, attributes, parent) {
    var node = document.createElementNS(SVG, name);
    Object.keys(attributes).forEach(function (key) { node.setAttribute(key, attributes[key]); });
    if (parent) parent.appendChild(node);
    return node;
  }

  /* The same bullet as on the railway site: the letter is centred on its capitals. */
  function bullet(line) {
    var size = 24, label = String(line.short || line.id);
    var font = Math.round(size * (label.length > 2 ? 0.43 : label.length > 1 ? 0.5 : 0.56));
    var width = label.length <= 2 ? size : Math.round(size + (label.length - 2) * font * 0.62);
    var root = svg('svg', { width: width, height: size, viewBox: '0 0 ' + width + ' ' + size, 'aria-hidden': 'true' });
    root.style.display = 'block';
    root.style.flex = 'none';
    svg('rect', { width: width, height: size, rx: size / 2, fill: line.color }, root);
    var text = svg('text', {
      x: width / 2, y: size / 2, dy: '0.36em', 'text-anchor': 'middle',
      'font-size': font, 'font-weight': '700', 'font-family': FONT, fill: textColor(line.color)
    }, root);
    text.textContent = label;
    return root;
  }

  var link = document.createElement('a');
  link.id = 'atcs-railway-link';
  link.href = 'atcs/';
  link.title = 'Railway map, departures and trip planner';
  link.setAttribute('style', [
    'position:fixed', 'left:16px', 'bottom:16px', 'z-index:9999',
    'display:flex', 'align-items:center', 'gap:14px',
    'min-height:52px', 'padding:16px 14px 10px 14px', 'box-sizing:border-box',
    'background:#000', 'color:#fff', 'text-decoration:none',
    'font:700 18px/1 ' + FONT, 'letter-spacing:-.01em', 'outline-offset:2px'
  ].join(';'));

  var rule = document.createElement('span');
  rule.setAttribute('aria-hidden', 'true');
  rule.setAttribute('style', 'position:absolute;left:0;right:0;top:6px;height:2px;background:#fff');

  var name = document.createElement('span');
  name.setAttribute('style', 'display:block;white-space:nowrap');
  name.textContent = 'Railway';

  var bullets = document.createElement('span');
  bullets.setAttribute('style', 'display:flex;align-items:center;gap:4px');

  link.appendChild(rule);
  link.appendChild(name);
  link.appendChild(bullets);
  link.addEventListener('mouseenter', function () { link.style.background = '#262626'; });
  link.addEventListener('mouseleave', function () { link.style.background = '#000'; });
  link.addEventListener('focus', function () { link.style.outline = '3px solid #fccc0a'; });
  link.addEventListener('blur', function () { link.style.outline = 'none'; });
  document.body.appendChild(link);

  try {
    fetch('atcs/data/network.json?t=' + Date.now(), { cache: 'no-store' })
      .then(function (response) { return response.json(); })
      .then(function (network) {
        if (network && network.title) {
          name.textContent = network.title;
          link.title = network.title + ': map, departures and trip planner';
        }
        (network.lines || []).slice(0, 10).forEach(function (line) { bullets.appendChild(bullet(line)); });
        if (!bullets.childNodes.length) link.removeChild(bullets);
      })
      .catch(function () { link.removeChild(bullets); });
  } catch (ignored) {
    link.removeChild(bullets);
  }
})();
