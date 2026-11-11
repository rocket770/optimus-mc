/* Conservative walking model for numeric-ID 1.8.x worlds. Native captures carry their own graph. */
(function (root) {
  'use strict';
  const solid = new Set([
    1, 2, 3, 4, 5, 7, 12, 13, 14, 15, 16, 17, 19, 20, 21, 22, 23, 24, 25, 35,
    41, 42, 43, 45, 46, 47, 48, 49, 52, 56, 57, 58, 61, 62, 73, 74, 79, 80, 82,
    84, 86, 87, 89, 91, 95, 97, 98, 99, 100, 103, 110, 112, 121, 123, 124, 125,
    129, 133, 137, 138, 152, 153, 155, 158, 159, 161, 162, 165, 166, 168, 169,
    170, 172, 173, 174, 179, 181
  ]);
  const decoration = new Set([
    0, 6, 27, 28, 31, 32, 37, 38, 39, 40, 50, 55, 59, 63, 66, 68, 69, 70, 72,
    75, 76, 77, 83, 93, 94, 104, 105, 115, 127, 131, 132, 140, 141, 142, 143,
    147, 148, 149, 150, 157, 175, 176, 177
  ]);
  const hazardous = new Set([8, 9, 10, 11, 30, 51, 65, 81, 90, 106, 119]);
  const pause = () => new Promise((resolve) => setTimeout(resolve, 0));
  function shape(block) {
    if (!block || hazardous.has(block.id))
      return { hazard: true, floor: false, low: 0, high: 1 };
    if (decoration.has(block.id)) return null;
    if (solid.has(block.id)) return { floor: true, low: 0, high: 1 };
    if ([44, 126, 182].includes(block.id))
      return {
        floor: true,
        low: block.meta & 8 ? 0.5 : 0,
        high: block.meta & 8 ? 1 : 0.5
      };
    if (block.id === 171) return { floor: true, low: 0, high: 0.0625 };
    if (block.id === 60) return { floor: true, low: 0, high: 0.9375 };
    if (block.id === 88) return { floor: true, low: 0, high: 0.875 };
    // Unresolved connected shapes are obstacles, never invented standing surfaces.
    return { hazard: true, floor: false, low: 0, high: 1, unresolved: true };
  }
  async function build(terrain, progress) {
    const boxes = [],
      nodes = [],
      edges = [],
      columns = new Map(),
      unresolved = new Set(),
      bounds = terrain.bounds;
    function free(x, y, z) {
      for (let cx = Math.floor(x - 0.3); cx <= Math.floor(x + 0.3); cx++)
        for (let cz = Math.floor(z - 0.3); cz <= Math.floor(z + 0.3); cz++)
          for (let cy = Math.floor(y + 0.01); cy <= Math.floor(y + 1.8); cy++) {
            const block = terrain.get(cx, cy, cz),
              cell = shape(block);
            if (!cell) continue;
            if (
              cell.hazard ||
              (cy + cell.high > y + 0.01 && cy + cell.low < y + 1.8)
            )
              return false;
          }
      return true;
    }
    function supported(x, y, z) {
      const cy = Math.floor(y - 0.001),
        cell = shape(terrain.get(Math.floor(x), cy, Math.floor(z)));
      return cell?.floor && Math.abs(cy + cell.high - y) < 0.001;
    }
    function full(x, y, z) {
      const cell = shape(terrain.get(x, y, z));
      return cell?.floor && cell.low === 0 && cell.high === 1;
    }
    let visited = 0;
    for (const block of terrain.blocks()) {
      if (++visited > 600000)
        throw new Error(
          'Selected area contains over 600,000 non-air blocks. Choose a smaller import radius.'
        );
      if (visited % 4000 === 0) {
        progress('Reading map surfaces…');
        await pause();
      }
      const cell = shape(block);
      if (!cell) continue;
      const { x, y, z, id } = block;
      if (cell.unresolved) unresolved.add(id);
      const interior =
        full(x, y, z) &&
        full(x - 1, y, z) &&
        full(x + 1, y, z) &&
        full(x, y - 1, z) &&
        full(x, y + 1, z) &&
        full(x, y, z - 1) &&
        full(x, y, z + 1);
      if (!interior)
        boxes.push([x, y + cell.low, z, x + 1, y + cell.high, z + 1, id]);
      if (boxes.length > 200000)
        throw new Error(
          'Selected area has too many visible surfaces. Choose a smaller import radius.'
        );
      const top = y + cell.high;
      if (cell.floor && free(x + 0.5, top, z + 0.5)) {
        if (nodes.length >= 25000)
          throw new Error(
            'Selected area has more than 25,000 walkable surfaces. Choose a smaller import radius.'
          );
        const key = x + ',' + z;
        if (!columns.has(key)) columns.set(key, []);
        columns.get(key).push(nodes.length);
        nodes.push([x + 0.5, top, z + 0.5]);
      }
    }
    for (let i = 0; i < nodes.length; i++) {
      if (i % 250 === 0) {
        progress('Checking walking connections…');
        await pause();
      }
      const from = nodes[i];
      for (let dx = -1; dx <= 1; dx++)
        for (let dz = -1; dz <= 1; dz++) {
          if (!dx && !dz) continue;
          for (const j of columns.get(
            Math.floor(from[0]) + dx + ',' + (Math.floor(from[2]) + dz)
          ) || []) {
            const to = nodes[j];
            if (Math.abs(to[1] - from[1]) > 0.6) continue;
            let clear = true;
            for (let sample = 0; sample <= 8 && clear; sample++) {
              const fraction = sample / 8,
                x = from[0] + dx * fraction,
                z = from[2] + dz * fraction;
              // Vanilla auto-step can lift the body onto a half block; interpolation through it cannot.
              const low = Math.min(from[1], to[1]),
                high = Math.max(from[1], to[1]);
              clear =
                (supported(x, low, z) && free(x, low, z)) ||
                (supported(x, high, z) && free(x, high, z));
            }
            if (clear)
              edges.push([
                i,
                j,
                0,
                Math.hypot(dx, dz) + Math.abs(to[1] - from[1]) * 0.2
              ]);
          }
        }
    }
    return {
      format: 'optimus-map',
      schema: 1,
      editorBuild: 'workshop-r4',
      name: terrain.name,
      model: 'conservative-walking',
      bounds,
      boxes,
      nodes,
      edges,
      momentumChains: [],
      precisionJumps: [],
      unresolvedBlocks: [...unresolved],
      importNote:
        'Walking previews use conservative vanilla shapes. Capture with /o map export for native collision routes.'
    };
  }
  const api = { build, shape };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.MapGeometry = api;
})(typeof window === 'undefined' ? globalThis : window);