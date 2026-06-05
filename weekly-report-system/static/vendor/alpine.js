/**
 * Alpine.js v3 placeholder — closed-network deployment stub.
 *
 * REPLACE THIS FILE with the real Alpine.js before production use:
 *   1. Download: https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js
 *   2. Save as: static/vendor/alpine.js
 *
 * This stub provides minimal Alpine.js-compatible behavior so the page
 * degrades gracefully (forms still submit via normal HTML).
 */
(function () {
  'use strict';

  // Minimal reactive system: walk DOM on DOMContentLoaded and wire directives.
  function processElement(el, data) {
    // x-show
    var show = el.getAttribute('x-show');
    if (show !== null) {
      try {
        var visible = Function(...Object.keys(data), 'return (' + show + ')')(...Object.values(data));
        el.style.display = visible ? '' : 'none';
      } catch (e) { /* ignore */ }
    }

    // x-text
    var xtext = el.getAttribute('x-text');
    if (xtext !== null) {
      try {
        el.textContent = Function(...Object.keys(data), 'return (' + xtext + ')')(...Object.values(data));
      } catch (e) { /* ignore */ }
    }

    // @click
    var click = el.getAttribute('@click');
    if (click !== null) {
      el.addEventListener('click', function () {
        try { Function(...Object.keys(data), click)(...Object.values(data)); } catch (e) { /* ignore */ }
      });
    }
  }

  function initComponent(el) {
    var expr = el.getAttribute('x-data');
    if (!expr) return;
    var data = {};
    try { data = eval('(' + expr + ')'); } catch (e) { return; }

    // x-init
    var xinit = el.getAttribute('x-init');
    if (xinit) {
      try { Function(...Object.keys(data), xinit)(...Object.values(data)); } catch (e) { /* ignore */ }
    }

    el.querySelectorAll('[x-show],[x-text],[@click]').forEach(function (child) {
      processElement(child, data);
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[x-data]').forEach(initComponent);
    // Show elements that were hidden by [x-cloak]
    document.querySelectorAll('[x-cloak]').forEach(function (el) {
      el.removeAttribute('x-cloak');
    });
  });

  // Minimal window.Alpine shim
  window.Alpine = { start: function () {}, data: function () {} };
})();
