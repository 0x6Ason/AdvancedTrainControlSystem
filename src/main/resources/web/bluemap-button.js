/* Loaded by BlueMap's page. BlueMap's web server lets browsers keep files for a day, so this file
 * never changes: it asks for the current version number, which is never cached, and loads the
 * plaque stamped with it. */
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
