/* Loaded by BlueMap's page, which injects registered scripts as plain script tags: browsers serve
 * them from their cache for a day (BlueMap's Cache-Control), even across a hard reload. So this
 * file never changes: it asks for the current version number, which is never cached, and loads
 * the plaque stamped with it. Its name must never be reused for different content. */
(function () {
  'use strict';
  if (window.ATCS_PLAQUE_LOADED) return;
  window.ATCS_PLAQUE_LOADED = true;
  function load(version) {
    var script = document.createElement('script');
    script.src = 'atcs/plaque.js?v=' + encodeURIComponent(version);
    document.head.appendChild(script);
  }
  try {
    fetch('atcs/version.json?t=' + Date.now(), { cache: 'no-store' })
      .then(function (response) { return response.json(); })
      .then(function (data) { load(data.version || String(Date.now())); })
      .catch(function () { load(String(Date.now())); });
  } catch (ignored) {
    load(String(Date.now()));
  }
})();
