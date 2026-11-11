'use strict';
// Headless JavaScript/DOM fixture, not a visual browser or WebGL-driver check.
const assert = require('assert'), fs = require('fs'), vm = require('vm'), path = require('path');
const root = path.resolve(__dirname, '..'), dataContext = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(root, 'editor/pit-map.js'), 'utf8'), dataContext);
const map = dataContext.window.PIT_MAP, graph = require('../editor/graph.js').create(map), suggested = graph.suggested();
let checks = 0; const expect = (value, why) => { checks++; assert.ok(value, why); };
expect(map.nodes.length > 1000 && map.edges.length > 10000, 'captured surfaces and directed graph are present');
expect(suggested.areas.length === 10 && suggested.links.length === 6, 'all map-backed suggestions are available');
map.edges.forEach(e => expect(map.nodes[e[0]] && map.nodes[e[1]] && e[0] !== e[1] && e[2] >= 0 && e[2] <= 5 && Number.isFinite(e[3]) && e[3] > 0, 'exported graph edge has valid endpoints, kind and cost'));
map.momentumChains.forEach(c => expect(map.edges.some(e => e[0] === c.from && e[1] === c.to && e[2] === 4) && c.points.length > 20 && c.points.flat().every(Number.isFinite), 'momentum connections include their checked two-hop trajectories'));
map.precisionJumps.forEach(c => expect(map.edges.some(e => e[0] === c.from && e[1] === c.to && e[2] === 5) && c.points.length > 8 && c.points.flat().every(Number.isFinite), 'precision connections include their checked step and jump trajectories'));
suggested.links.forEach(l => {
  expect(l.route.visited < map.nodes.length, 'suggested connections search a bounded subgraph');
  l.route.ids.slice(1).forEach((id, i) => expect(map.edges.some(e => e[0] === l.route.ids[i] && e[1] === id), 'every preview segment uses an exported checked edge'));
});
const low = suggested.areas.find(a => a.id === 'east-low'), high = suggested.areas.find(a => a.id === 'east-high');
expect(graph.path(low, high).points.some(p => p[1] === 65 && p[0] >= 12 && p[0] <= 15 && p[2] >= -80 && p[2] <= -77), 'east high route uses the middle platform');
const westRoof = suggested.areas.find(a => a.id === 'west-end');
const westWall = { id: 'west-wall', name: 'west wall', x: -21.5, y: 66.5, z: -106.5, radius: 1.1 };
expect(graph.members(westWall).length === 3, 'reported wall endpoint resolves its three supported grid cells');
const wallRoute = graph.path(westWall, westRoof, true);
expect(wallRoute && wallRoute.points.some(p => p[1] === 63) && wallRoute.points.some(p => p[1] === 65), 'west wall reaches the roof using the floor and staircase');
const roofToWall = graph.path(westRoof, westWall, true);
expect(roofToWall && roofToWall.kinds.includes(4) && roofToWall.points.length > 20 && roofToWall.points.some(p => p[1] > westRoof.y + 1), 'requested roof-to-wall connection previews the explicit momentum chain and both flight arcs');
expect(!graph.path(westRoof, westWall, false), 'momentum chains still require jumping to be enabled');
const precisionAreas = [
  { id: 'east-platform-1', name: 'east platform 1', x: 21.5, y: 65, z: -111.5, radius: .8 },
  { id: 'east-jump-1', name: 'east jump 1', x: 23.5, y: 66, z: -107.5, radius: .8 },
  { id: 'top-main-platform', name: 'top main platform', x: 2.5, y: 69, z: -106.5, radius: 1.1 },
  { id: 'middle-jump-from-top-main', name: 'middle jump from top main', x: 5.5, y: 70, z: -102.5, radius: 1.1 }
];
const precisionPairs = [[0, 1], [2, 3], [3, 2]];
precisionPairs.forEach(([a, b]) => {
  const route = graph.path(precisionAreas[a], precisionAreas[b]);
  expect(route && route.ids.length === 2 && route.kinds.includes(5), 'reported jump gets a direct precision connection');
  expect(route.points.every(p => p[1] >= Math.min(precisionAreas[a].y, precisionAreas[b].y) - .01), 'reported connection stays above the pit floor');
  expect(!graph.path(precisionAreas[a], precisionAreas[b], false), 'reported precision jump is excluded when jumping is disabled');
});
const roundTrip = graph.normalise(JSON.parse(JSON.stringify(graph.serialise(suggested))));
expect(roundTrip.areas.length === 10 && roundTrip.links.length === 6, 'draft graph survives export/import');
for (const bad of [null, {}, { ...graph.serialise(suggested), mapHash: 'other' }, { ...graph.serialise(suggested), areas: [low, low] }, { ...graph.serialise(suggested), areas: [{ ...low, radius: Infinity }] }]) {
  checks++; assert.throws(() => graph.normalise(bad));
}
const tiny = require('../editor/graph.js').create({ nodes: [[0, 1, 0], [1, 2, 0], [2, 1, 0]], edges: [[0, 1, 1, 2], [1, 2, 3, 2]], mapHash: 'tiny' });
checks++; assert.throws(() => require('../editor/graph.js').create({ nodes: [[0, 1, 0], [6, 0, 0]], edges: [[0, 1, 4, 12]] }), /trajectory/);
checks++; assert.throws(() => require('../editor/graph.js').create({ nodes: [[0, 1, 0], [2, 2, 4]], edges: [[0, 1, 5, 9]] }), /trajectory/);
const a = { x: 0, y: 1, z: 0, radius: .4 }, b = { x: 2, y: 1, z: 0, radius: .4 };
expect(tiny.path(a, b, true) && !tiny.path(a, b, false) && !tiny.path(b, a, true), 'jump filtering and one-way connections preserve graph direction');

class Element {
  constructor(tag, id) { this.tag = tag; this.id = id; this.children = []; this.value = ''; this.checked = false; this.disabled = false; this.style = {}; this.classList = { toggle() {} }; this.clientWidth = 1200; this.clientHeight = 800; this.width = 0; this.height = 0; this.textContent = ''; }
  append(...elements) { this.children.push(...elements); }
  replaceChildren(...elements) { this.children = elements; }
  remove() {}
  click() { if (this.onclick) return this.onclick(); }
  setPointerCapture() {}
  getBoundingClientRect() { return { left: 0, top: 0, width: this.clientWidth, height: this.clientHeight }; }
}
const elements = new Map();
const html = fs.readFileSync(path.join(root, 'editor/index.html'), 'utf8');
for (const match of html.matchAll(/<([\w-]+)[^>]*\bid="([^"]+)"[^>]*>/g)) elements.set(match[2], new Element(match[1], match[2]));
const get = id => elements.get(id); get('area-radius').value = '1.1'; get('height').value = '99'; get('parkour').checked = true;
let drawCount = 0, terrainVertices = 0, matrixCount = 0;
const graphics = new Proxy({}, { get(target, key) {
  if (/^[A-Z_]+$/.test(key)) return key;
  if (key === 'getShaderParameter' || key === 'getProgramParameter') return () => true;
  if (key.startsWith('create')) return () => ({});
  if (key === 'getAttribLocation') return () => 1;
  if (key === 'uniformMatrix4fv') return (location, transpose, matrix) => { expect(matrix.length === 16 && Array.from(matrix).every(Number.isFinite), 'camera matrices remain finite'); matrixCount++; };
  if (key === 'bufferData') return (type, values, usage) => { expect(values instanceof Float32Array && values.length % 6 === 0, 'renderer buffers contain position/color tuples'); if (usage === 'STATIC_DRAW') { terrainVertices = values.length / 6; for (let i = 0; i < values.length; i += 131) expect(Number.isFinite(values[i]), 'terrain vertices are finite'); } };
  if (key === 'drawArrays') return () => drawCount++;
  return () => {};
} });
const drawing = new Proxy({}, { get(target, key) { if (key === 'measureText') return text => ({ width: text.length * 6 }); return () => {}; }, set() { return true; } });
get('map').getContext = () => graphics; get('labels').getContext = () => drawing;
const blobs = new Map(); let exported;
const document = { getElementById: get, createElement(tag) { const e = new Element(tag); if (tag === 'a') e.click = () => { exported = blobs.get(e.href); }; return e; }, body: new Element('body') };
const localDrafts = new Map(), localStorage = { getItem(key) { return localDrafts.has(key) ? localDrafts.get(key) : null; }, setItem(key, value) { localDrafts.set(key, value); } };
const editorWindow = { PIT_MAP: map, localStorage, location: { hash: '', pathname: '/editor/index.html', search: '' }, history: { replaceState() { editorWindow.location.hash = ''; } } };
const context = { window: editorWindow, PitGraph: require('../editor/graph.js'), document, devicePixelRatio: 1, Float32Array, Blob,
  ResizeObserver: class { observe() {} }, URL: { createObjectURL(blob) { const url = 'blob:' + blobs.size; blobs.set(url, blob); return url; }, revokeObjectURL() {} }, setTimeout() {} };
vm.runInNewContext(fs.readFileSync(path.join(root, 'editor/app.js'), 'utf8'), context);
expect(get('area-count').textContent === '10 saved' && get('link-count').textContent === '6 directed', 'editor opens with suggested areas and connections');
expect(terrainVertices > 100000 && drawCount > 0 && matrixCount > 0, 'renderer receives the captured scene and draws the initial map');
get('height').value = '63'; get('height').onchange(); get('top').click(); get('add').click();
get('map').onpointerdown({ pointerId: 1, clientX: 607, clientY: 422, button: 0, shiftKey: false });
get('map').onpointerup({ clientX: 607, clientY: 422 });
expect(get('area-height').value === 63 && !get('save-area').disabled, 'top-view picking snaps to the supported floor at the correct height');
get('area-name').value = 'QA staging'; get('save-area').click();
expect(get('area-count').textContent === '11 saved', 'a picked area is saved');
get('from').value = 'qa-staging'; get('to').value = 'stair-foot'; get('both').checked = true; get('preview').click();
expect(!get('save-link').disabled && get('route-detail').textContent.includes('graph nodes searched'), 'bidirectional route preview computes separate directed routes');
get('save-link').click(); expect(get('link-count').textContent === '8 directed', 'both checked directions are saved');
get('undo').click(); expect(get('link-count').textContent === '6 directed' && get('area-count').textContent === '11 saved', 'undo restores the previous graph');
get('export').click(); expect(exported instanceof Blob, 'export creates a local JSON download');
(async () => {
  const text = await exported.text(), decoded = JSON.parse(text);
  expect(decoded.status === 'draft' && decoded.mapHash === map.mapHash && decoded.areas.length === 11, 'export identifies draft status and the exact map');
  get('file').files = [{ size: text.length, text: async () => text }]; await get('file').onchange();
  expect(get('area-count').textContent === '11 saved' && get('status').textContent.startsWith('Draft imported'), 'UI import round-trips the draft');
  const invalid = JSON.stringify({ ...decoded, mapHash: 'wrong' }); get('file').files = [{ size: invalid.length, text: async () => invalid }]; await get('file').onchange();
  expect(get('area-count').textContent === '11 saved' && get('status').textContent.includes('different map'), 'invalid imports leave the existing draft intact');
  get('clear').click(); expect(get('area-count').textContent === '0 saved', 'start-empty removes the draft areas');
  get('undo').click(); expect(get('area-count').textContent === '11 saved', 'clearing a draft is reversible');
  const island = { id: 'high-island', name: 'High island', x: -16.5, y: 71.5, z: -94.5, radius: .4 };
  const wallDraft = JSON.stringify(graph.serialise({ areas: [...suggested.areas, westWall, island], links: suggested.links }));
  get('file').files = [{ size: wallDraft.length, text: async () => wallDraft }]; await get('file').onchange();
  get('parkour').checked = true; get('both').checked = false; get('connect').click();
  get('areas').children.find(button => button.children[0].textContent === 'west wall').click();
  get('areas').children.find(button => button.children[0].textContent === 'West · lower roof').click();
  expect(get('from').value === 'west-wall' && get('to').value === 'west-end' && !get('save-link').disabled, 'Connect clicks preserve the wall-to-roof direction across refreshed area lists');
  expect(!get('save-link').disabled && get('route-detail').textContent.includes('west wall (Y 66.5) → West · lower roof (Y 67.5)'), 'reported wall-to-roof direction previews a route and shows its endpoints');
  get('swap').click();
  expect(!get('save-link').disabled && get('route-detail').textContent.includes('West · lower roof (Y 67.5) → west wall (Y 66.5)') && get('route-detail').textContent.includes('momentum chain'), 'requested roof-to-wall direction is saveable and explains its advanced two-hop movement');
  get('save-link').click(); get('export').click();
  const chainExport = JSON.parse(await exported.text());
  expect(chainExport.links.some(l => l.from === 'west-end' && l.to === 'west-wall' && l.requirements.includes('momentum-chain') && l.modelPath.length > 20), 'requested directed link exports its movement requirement and complete checked corridor');
  get('both').checked = true; get('preview').click();
  expect(!get('save-link').disabled, 'wall and roof can each have separately checked connections');
  get('both').checked = false; get('parkour').checked = false; get('preview').click();
  expect(get('save-link').disabled && get('route-detail').textContent.includes('Enable'), 'walk-only failure gives the jump option only when enabling it finds a route');
  get('parkour').checked = true; get('from').value = 'pit-floor'; get('to').value = 'high-island'; get('preview').click();
  expect(get('save-link').disabled && get('route-detail').textContent.includes('reverse direction'), 'unavailable high-island direction still explains its available reverse connection');
  get('swap').click(); get('both').checked = true; get('preview').click();
  expect(get('save-link').disabled && get('route-detail').textContent.includes('return route is unavailable'), 'one-way islands still report an unavailable return route');
  const precisionDraft = JSON.stringify(graph.serialise({ areas: [...suggested.areas, ...precisionAreas], links: suggested.links }));
  get('file').files = [{ size: precisionDraft.length, text: async () => precisionDraft }]; await get('file').onchange();
  get('both').checked = false;
  for (const [a, b] of precisionPairs) {
    get('from').value = precisionAreas[a].id; get('to').value = precisionAreas[b].id; get('preview').click();
    expect(!get('save-link').disabled && get('route-detail').textContent.includes('precision jump'), 'reported route can be previewed and its precision requirement is visible');
    get('save-link').click();
  }
  get('export').click(); const precisionExport = JSON.parse(await exported.text());
  expect(precisionExport.links.filter(l => l.requirements.includes('precision-jump')).length === 3, 'all three reported directed links save and export their precision requirement');
  const roundTripText = JSON.stringify(precisionExport); get('file').files = [{ size: roundTripText.length, text: async () => roundTripText }]; await get('file').onchange();
  expect(get('link-count').textContent === '9 directed', 'new precision links survive editor export/import');
  if (process.argv[2]) {
    const suppliedText = fs.readFileSync(process.argv[2], 'utf8'), supplied = JSON.parse(suppliedText);
    get('file').files = [{ size: suppliedText.length, text: async () => suppliedText }]; await get('file').onchange();
    expect(get('area-count').textContent === supplied.areas.length + ' saved' && get('link-count').textContent === supplied.links.length + ' directed', 'supplied working draft imports with all of its areas and connections');
    for (const [a, b] of precisionPairs) {
      get('from').value = precisionAreas[a].id; get('to').value = precisionAreas[b].id; get('preview').click();
      expect(!get('save-link').disabled && get('route-detail').textContent.includes('precision jump'), 'supplied working draft previews each requested precision connection');
    }
    get('export').click(); const suppliedExport = JSON.parse(await exported.text());
    expect(JSON.stringify(suppliedExport.areas) === JSON.stringify(supplied.areas) && suppliedExport.links.length === supplied.links.length, 'supplied working draft survives UI import/export without losing any areas or links');
  }
  get('links').children[0].click(); get('remove-link').click(); get('export').click();
  const afterDeletion = JSON.parse(await exported.text());
  expect(localDrafts.size === 1, 'edits save to the browser draft store');
  editorWindow.location.hash = '#seed=' + encodeURIComponent(JSON.stringify(precisionExport));
  vm.runInNewContext(fs.readFileSync(path.join(root, 'editor/app.js'), 'utf8'), context);
  get('export').click(); const afterReload = JSON.parse(await exported.text());
  expect(JSON.stringify(afterReload) === JSON.stringify(afterDeletion), 'reload preserves deletions and edits instead of restoring an older seed draft');
  expect(get('editor-version').textContent.includes('r4') && get('editor-version').textContent.includes('autosave on'), 'the current build and persistence state are visible');
  localDrafts.clear(); editorWindow.location.hash = '#seed=' + encodeURIComponent(JSON.stringify(afterDeletion));
  vm.runInNewContext(fs.readFileSync(path.join(root, 'editor/app.js'), 'utf8'), context);
  get('export').click(); const seeded = JSON.parse(await exported.text());
  expect(JSON.stringify(seeded) === JSON.stringify(afterDeletion) && localDrafts.size === 1, 'new working-editor link loads and saves its supplied draft on first use');
  editorWindow.localStorage = { getItem() { throw new Error('Storage disabled'); }, setItem() { throw new Error('Storage disabled'); } };
  editorWindow.location.hash = '#seed=' + encodeURIComponent(JSON.stringify(afterDeletion));
  vm.runInNewContext(fs.readFileSync(path.join(root, 'editor/app.js'), 'utf8'), context);
  expect(get('editor-version').textContent.includes('export to save') && get('status').textContent.includes('Browser saving is unavailable'), 'blocked storage requests an export instead of claiming the draft is saved');
  get('export').click();
  expect(JSON.stringify(JSON.parse(await exported.text())) === JSON.stringify(afterDeletion), 'working draft still loads when browser storage is blocked');
  editorWindow.localStorage = localStorage;
  const storageKey = [...localDrafts.keys()][0]; localDrafts.set(storageKey, '{broken');
  editorWindow.location.hash = '';
  vm.runInNewContext(fs.readFileSync(path.join(root, 'editor/app.js'), 'utf8'), context);
  expect(get('status').textContent.includes('could not be restored') && localDrafts.get(storageKey) === '{broken', 'invalid saved data is reported and left untouched until an edit or import');
  console.log('PASS: ' + checks + ' waypoint graph, import/export, renderer-input and editor action checks (visual browser QA unavailable)');
})().catch(error => { console.error(error); process.exitCode = 1; });