'use strict';
// Loader checks in a DOM fixture; this does not run a browser/WebGL driver.
const fs = require('fs'), vm = require('vm'), assert = require('assert'), path = require('path');
const root = path.resolve(__dirname, '..'), source = fs.readFileSync(path.join(root, 'editor/bootstrap.js'), 'utf8');
const mapContext = { window: {} }; vm.runInNewContext(fs.readFileSync(path.join(root, 'editor/pit-map.js'), 'utf8'), mapContext);
async function scenario(failure) {
  const loaded = [], controls = [{ disabled: false }, { disabled: false }], status = { textContent: '', style: {} }, version = { textContent: '' }, window = {};
  const document = { getElementById(id) { return id === 'status' ? status : version; }, querySelectorAll() { return controls; }, createElement() { return {}; }, head: { append(script) {
    loaded.push(script.src); const name = script.src.split('?')[0];
    Promise.resolve().then(() => {
      if (failure === 'missing-file' && name === 'graph.js') { script.onerror(); return; }
      if (name === 'pit-map.js') window.PIT_MAP = { ...mapContext.window.PIT_MAP, ...(failure === 'old-map' ? { editorBuild: 'old' } : {}) };
      if (name === 'graph.js') window.PitGraph = { build: failure === 'old-graph' ? 'old' : 'workshop-r4' };
      if (name === 'app.js') window.OPTIMUS_APP_BUILD = failure === 'old-app' ? 'old' : 'workshop-r4';
      script.onload();
    });
  } } };
  vm.runInNewContext(source, { window, document, Date, Promise });
  await new Promise(setImmediate);
  return { loaded, controls, status, version };
}
(async () => {
  const good = await scenario();
  assert.equal(good.loaded.length, 7); assert(good.loaded.every(url => url.includes('?build=workshop-r4&load=')));
  assert.equal(good.version.textContent, 'Editor r4'); assert(good.controls.every(c => !c.disabled));
  for (const failure of ['old-map', 'old-graph', 'old-app', 'missing-file']) {
    const result = await scenario(failure);
    assert(result.controls.every(c => c.disabled)); assert(/different update|different updates|Could not load/.test(result.status.textContent));
    if (failure === 'old-map') assert.equal(result.loaded.length, 1);
    if (failure === 'old-graph' || failure === 'missing-file') assert.equal(result.loaded.length, 2);
  }
  console.log('PASS: cache-busting loader, matching-build guards and missing-file checks');
})().catch(error => { console.error(error); process.exitCode = 1; });