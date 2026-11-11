'use strict';
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const compression = require('../editor/vendor/fflate.js');
const importer = require('../editor/map/import.js');
const nbt = require('../editor/map/nbt.js');
const geometry = require('../editor/map/geometry.js');
const root = path.resolve(__dirname, '..');
let checks = 0;
const expect = (value, why) => {
  checks++;
  assert.ok(value, why);
};
async function rejects(work, why) {
  checks++;
  await assert.rejects(work, undefined, why);
}

(async () => {
  const fixture = new Uint8Array(
    fs.readFileSync(path.join(root, 'build/map-export-fixture.zip'))
  );
  const files = compression.unzipSync(fixture);
  const native = await importer.read(fixture, 'export.zip');
  expect(
    native.model === 'native-collision' &&
      native.nodes.length >= 36 &&
      native.edges.length > 0,
    'the Java export opens with its exact native graph'
  );
  const roundTrip = await importer.read(
    new TextEncoder().encode(JSON.stringify(native)),
    'map.json'
  );
  expect(
    JSON.stringify(roundTrip.nodes) === JSON.stringify(native.nodes) &&
      roundTrip.mapHash === native.mapHash,
    'downloaded map JSON restores the same graph and identity'
  );
  const schematic = importer.inflate(files['negative-origin.schematic'], 1);
  const terrain = importer.schematic(schematic, 'schematic', { radius: 32 });
  expect(
    terrain.get(4, 1, 2).id === 300 && terrain.get(4, 1, 2).meta === 7,
    'classic schematic AddBlocks and metadata reconstruct extended block IDs'
  );
  expect(
    nbt.read(schematic).value.TileEntities[0].Text1 === 'Unicode: \u2192',
    'the NBT reader preserves Java modified UTF-8 strings'
  );
  const imported = await importer.read(
    files['negative-origin.schematic'],
    'test.schematic'
  );
  expect(
    imported.model === 'conservative-walking' &&
      imported.nodes.length > 0 &&
      imported.edges.length > 0,
    'classic schematics build conservative walking previews'
  );
  expect(
    imported.unresolvedBlocks.includes(300) &&
      imported.nodes.every((point) => !(point[0] === 4.5 && point[2] === 2.5)),
    'unresolved shapes never become invented walkable surfaces'
  );
  expect(
    imported.bounds[0] === -3 && imported.bounds[2] === -5,
    'schematic world origin is preserved'
  );
  const regions = Object.keys(files).filter((name) => name.endsWith('.mca'));
  let chunkCount = 0;
  for (const region of regions)
    chunkCount += importer.chunks(files[region]).length;
  expect(
    chunkCount === 4,
    'all four negative/positive Anvil chunks are decoded'
  );
  const fromRegions = importer.world(
    regions.map((name) => files[name]),
    'world',
    { radius: 32 }
  );
  expect(
    fromRegions.get(4, 1, 2).id === 300 && fromRegions.get(4, 1, 2).meta === 7,
    'Anvil Blocks/Add/Data reconstruct the same extended state'
  );
  const foreignZip = compression.zipSync(
    Object.fromEntries(regions.map((name) => ['save/' + name, files[name]]))
  );
  const foreign = await importer.read(foreignZip, 'world.zip');
  expect(
    foreign.nodes.length > 0 && foreign.model === 'conservative-walking',
    'a standard world ZIP without Optimus metadata imports through Anvil'
  );
  const regionOnly = await importer.read(
    files[regions[0]],
    path.basename(regions[0])
  );
  expect(
    regionOnly.boxes.length > 0,
    'an individual region file can be opened'
  );
  checks++;
  assert.throws(
    () =>
      importer.schematic(schematic, 'cropped', {
        centerX: 1000,
        centerZ: 1000,
        radius: 8
      }),
    /outside/
  );
  const sample = {
    format: 'optimus-map',
    schema: 1,
    editorBuild: 'workshop-r4',
    mapHash: 'a'.repeat(64),
    bounds: [0, 0, 0, 2, 4, 2],
    boxes: [],
    nodes: [],
    edges: []
  };
  for (const bad of [
    null,
    { ...sample, bounds: [0, 0, 0, 0, 1, 1] },
    { ...sample, nodes: [[NaN, 0, 0]] },
    { ...sample, boxes: [[0, 0, 0, 1, 1, 1, 5000]] },
    { ...sample, edges: [[0, 1, 0, 1]] },
    { ...sample, mapHash: 'wrong' }
  ]) {
    checks++;
    assert.throws(() => importer.validate(bad));
  }
  await rejects(
    () =>
      importer.read(
        new TextEncoder().encode('globalThis.importRan=true'),
        'unsafe.js'
      ),
    'script maps are not executed'
  );
  expect(globalThis.importRan === undefined, 'no import evaluates source code');
  checks++;
  assert.throws(
    () =>
      nbt.read(Uint8Array.from([10, 0, 0, 7, 0, 1, 65, 127, 255, 255, 255, 0])),
    /too large|Truncated/
  );
  checks++;
  assert.throws(() => importer.chunks(new Uint8Array(8193)), /length/);
  const invalidRegion = new Uint8Array(8192);
  invalidRegion.set([0, 0, 2, 1]);
  checks++;
  assert.throws(() => importer.chunks(invalidRegion), /location/);
  checks++;
  assert.throws(
    () => importer.inflate(compression.gzipSync(new Uint8Array(4096)), 1, 128),
    /limit/
  );
  const region = files[regions[0]];
  const dimensions = compression.zipSync({
    'region/r.0.0.mca': region,
    'DIM-1/region/r.0.0.mca': region
  });
  await rejects(
    () => importer.read(dimensions, 'dimensions.zip'),
    'overlapping dimensions require an explicit single-dimension import'
  );
  expect(
    geometry.shape({ id: 44, meta: 0 }).high === 0.5 &&
      geometry.shape({ id: 44, meta: 8 }).low === 0.5,
    'slab collision heights preserve bottom/top metadata'
  );
  expect(
    geometry.shape({ id: 53, meta: 0 }).hazard &&
      !geometry.shape({ id: 53, meta: 0 }).floor,
    'connected shapes are conservative obstacles rather than fabricated full-cube floors'
  );
  console.log(
    'PASS: ' +
      checks +
      ' map JSON, schematic, Anvil, ZIP, bounds and malformed-input checks'
  );
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});