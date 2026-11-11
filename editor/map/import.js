(function (root) {
  'use strict';
  const node = typeof module !== 'undefined' && module.exports;
  const zip = node ? require('../vendor/fflate.js') : root.fflate;
  const nbt = node ? require('./nbt.js') : root.MapNbt;
  const geometry = node ? require('./geometry.js') : root.MapGeometry;
  const MAX_BYTES = 128 * 1024 * 1024;
  const nibble = (bytes, index) =>
    bytes ? (bytes[index >> 1] >> ((index & 1) * 4)) & 15 : 0;
  const array = (value, length, label) => {
    if (!(value instanceof Uint8Array) || value.length !== length)
      throw new Error('Invalid ' + label + ' array.');
    return value;
  };
  function inflate(bytes, kind, maximum = MAX_BYTES) {
    let size = 0;
    const pieces = [];
    const Constructor = kind === 1 ? zip.Gunzip : zip.Unzlib;
    const stream = new Constructor((chunk) => {
      size += chunk.length;
      if (size > maximum)
        throw new Error('Decompressed map exceeds the import limit.');
      pieces.push(chunk);
    });
    for (let at = 0; at < bytes.length; at += 4096)
      stream.push(bytes.subarray(at, at + 4096), at + 4096 >= bytes.length);
    const result = new Uint8Array(size);
    let offset = 0;
    for (const piece of pieces) {
      result.set(piece, offset);
      offset += piece.length;
    }
    return result;
  }
  function validate(map) {
    const finite = (values) =>
      Array.isArray(values) &&
      values.every(
        (value) => Number.isFinite(value) && Math.abs(value) <= 30_000_000
      );
    if (
      !map ||
      map.schema !== 1 ||
      map.editorBuild !== 'workshop-r4' ||
      !/^[a-f0-9]{64}$/.test(map.mapHash || '') ||
      !finite(map.bounds) ||
      map.bounds.length !== 6 ||
      map.bounds.some((n, i) => i < 3 && n >= map.bounds[i + 3])
    )
      throw new Error('Invalid Optimus map header.');
    if (
      !Array.isArray(map.boxes) ||
      map.boxes.length > 200000 ||
      !Array.isArray(map.nodes) ||
      map.nodes.length > 25000 ||
      !Array.isArray(map.edges) ||
      map.edges.length > 1_000_000
    )
      throw new Error('Map exceeds the editor geometry/graph limits.');
    for (const box of map.boxes)
      if (
        !finite(box) ||
        box.length !== 7 ||
        box[0] >= box[3] ||
        box[1] >= box[4] ||
        box[2] >= box[5] ||
        !Number.isInteger(box[6]) ||
        box[6] < 0 ||
        box[6] > 4095
      )
        throw new Error('Invalid map collision box.');
    for (const point of map.nodes)
      if (!finite(point) || point.length !== 3)
        throw new Error('Invalid map standing surface.');
    for (const edge of map.edges)
      if (
        !Array.isArray(edge) ||
        edge.length !== 4 ||
        !edge.every(Number.isFinite) ||
        !Number.isInteger(edge[0]) ||
        !Number.isInteger(edge[1]) ||
        edge[0] === edge[1] ||
        !map.nodes[edge[0]] ||
        !map.nodes[edge[1]] ||
        !Number.isInteger(edge[2]) ||
        edge[2] < 0 ||
        edge[2] > 5 ||
        edge[3] <= 0
      )
        throw new Error('Invalid map connection.');
    const result = {
      ...map,
      name: String(map.name || 'Imported map').slice(0, 100),
      momentumChains: map.momentumChains || [],
      precisionJumps: map.precisionJumps || []
    };
    for (const family of [result.momentumChains, result.precisionJumps]) {
      if (!Array.isArray(family) || family.length > 25000)
        throw new Error('Invalid advanced connection data.');
      for (const link of family)
        if (
          !map.nodes[link.from] ||
          !map.nodes[link.to] ||
          !Number.isFinite(link.yaw) ||
          !Array.isArray(link.points) ||
          link.points.length > 512 ||
          link.points.some((p) => !finite(p) || p.length !== 3)
        )
          throw new Error('Invalid advanced trajectory.');
    }
    return result;
  }
  function crop(bounds, options) {
    const radius = Number(options.radius ?? 32);
    if (!Number.isInteger(radius) || radius < 8 || radius > 128)
      throw new Error('Import radius must be an integer from 8 to 128 blocks.');
    const centerX =
      options.centerX == null
        ? Math.floor((bounds[0] + bounds[3]) / 2)
        : Number(options.centerX);
    const centerZ =
      options.centerZ == null
        ? Math.floor((bounds[2] + bounds[5]) / 2)
        : Number(options.centerZ);
    if (
      !Number.isInteger(centerX) ||
      !Number.isInteger(centerZ) ||
      Math.abs(centerX) > 30_000_000 ||
      Math.abs(centerZ) > 30_000_000
    )
      throw new Error('Choose valid whole-block import coordinates.');
    const result = [
      Math.max(bounds[0], centerX - radius),
      bounds[1],
      Math.max(bounds[2], centerZ - radius),
      Math.min(bounds[3], centerX + radius + 1),
      bounds[4],
      Math.min(bounds[5], centerZ + radius + 1)
    ];
    if (result[0] >= result[3] || result[2] >= result[5])
      throw new Error('The selected coordinates are outside this map.');
    return result;
  }
  function schematic(bytes, name, options) {
    const data = nbt.read(bytes).value,
      width = data.Width,
      height = data.Height,
      length = data.Length;
    if (
      ![width, height, length].every((n) => Number.isInteger(n) && n > 0) ||
      height > 256 ||
      width * height * length > 8_000_000 ||
      data.Materials !== 'Alpha'
    )
      throw new Error(
        'Choose a classic numeric-ID .schematic for Minecraft 1.8.x (up to 8 million cells).'
      );
    const volume = width * height * length,
      ids = array(data.Blocks, volume, 'schematic Blocks'),
      metadata = array(data.Data, volume, 'schematic Data');
    if (data.AddBlocks)
      array(data.AddBlocks, Math.ceil(volume / 2), 'schematic AddBlocks');
    const x0 = (data.WEOriginX || 0) + (data.WEOffsetX || 0),
      y0 = (data.WEOriginY || 0) + (data.WEOffsetY || 0),
      z0 = (data.WEOriginZ || 0) + (data.WEOffsetZ || 0);
    if (![x0, y0, z0].every(Number.isSafeInteger))
      throw new Error('Invalid schematic origin.');
    const bounds = crop(
      [x0, y0, z0, x0 + width, y0 + height, z0 + length],
      options
    );
    return {
      name,
      bounds,
      get(x, y, z) {
        if (
          x < bounds[0] ||
          x >= bounds[3] ||
          z < bounds[2] ||
          z >= bounds[5] ||
          y < y0
        )
          return null;
        if (y >= y0 + height) return { id: 0, meta: 0 };
        const index = (y - y0) * width * length + (z - z0) * width + x - x0;
        return {
          id: ids[index] | (nibble(data.AddBlocks, index) << 8),
          meta: metadata[index] & 15
        };
      },
      *blocks() {
        for (let y = y0; y < y0 + height; y++)
          for (let z = bounds[2]; z < bounds[5]; z++)
            for (let x = bounds[0]; x < bounds[3]; x++) {
              const block = this.get(x, y, z);
              if (block.id) yield { ...block, x, y, z };
            }
      }
    };
  }
  function chunks(region) {
    if (region.length < 8192 || region.length % 4096)
      throw new Error('Invalid Anvil region length.');
    const view = new DataView(
        region.buffer,
        region.byteOffset,
        region.byteLength
      ),
      result = [];
    for (let i = 0; i < 1024; i++) {
      const location = view.getUint32(i * 4, false),
        sector = location >>> 8,
        allocated = location & 255;
      if (!sector && !allocated) continue;
      const offset = sector * 4096;
      if (sector < 2 || !allocated || offset + allocated * 4096 > region.length)
        throw new Error('Invalid Anvil chunk location.');
      const length = view.getUint32(offset, false),
        compression = region[offset + 4];
      if (
        length < 2 ||
        length + 4 > allocated * 4096 ||
        ![1, 2, 3].includes(compression)
      )
        throw new Error('Unsupported or truncated Anvil chunk.');
      const packed = region.subarray(offset + 5, offset + 4 + length);
      const data = nbt.read(
        compression === 3
          ? packed
          : inflate(packed, compression, 4 * 1024 * 1024)
      ).value;
      const level = data.Level;
      if (!level || !Array.isArray(level.Sections))
        throw new Error(
          'World imports require Minecraft 1.8.x Anvil chunks with numeric block IDs.'
        );
      if (
        !Number.isInteger(level.xPos) ||
        !Number.isInteger(level.zPos) ||
        Math.abs(level.xPos) > 1_875_000 ||
        Math.abs(level.zPos) > 1_875_000
      )
        throw new Error('Invalid Anvil chunk coordinates.');
      const sections = new Map();
      for (const section of level.Sections) {
        if (
          !Number.isInteger(section.Y) ||
          section.Y < 0 ||
          section.Y > 15 ||
          sections.has(section.Y)
        )
          throw new Error('Invalid Anvil section.');
        array(section.Blocks, 4096, 'section Blocks');
        array(section.Data, 2048, 'section Data');
        if (section.Add) array(section.Add, 2048, 'section Add');
        sections.set(section.Y, section);
      }
      result.push({ x: level.xPos, z: level.zPos, sections });
    }
    return result;
  }
  function world(regions, name, options) {
    const parts = regions.flatMap(chunks);
    if (!parts.length)
      throw new Error('This world contains no saved Anvil chunks.');
    let x0 = Infinity,
      z0 = Infinity,
      x1 = -Infinity,
      z1 = -Infinity;
    const columns = new Map();
    for (const part of parts) {
      const key = part.x + ',' + part.z;
      if (columns.has(key))
        throw new Error(
          'Archive contains overlapping worlds or dimensions. Import one dimension at a time.'
        );
      columns.set(key, part);
      x0 = Math.min(x0, part.x * 16);
      z0 = Math.min(z0, part.z * 16);
      x1 = Math.max(x1, part.x * 16 + 16);
      z1 = Math.max(z1, part.z * 16 + 16);
    }
    const bounds = crop([x0, 0, z0, x1, 256, z1], options);
    return {
      name,
      bounds,
      get(x, y, z) {
        if (
          x < bounds[0] ||
          x >= bounds[3] ||
          z < bounds[2] ||
          z >= bounds[5] ||
          y < 0
        )
          return null;
        const part = columns.get(Math.floor(x / 16) + ',' + Math.floor(z / 16));
        if (!part) return null;
        if (y >= 256) return { id: 0, meta: 0 };
        const section = part.sections.get(y >> 4);
        if (!section) return { id: 0, meta: 0 };
        const index = (y & 15) * 256 + (z & 15) * 16 + (x & 15);
        return {
          id: section.Blocks[index] | (nibble(section.Add, index) << 8),
          meta: nibble(section.Data, index)
        };
      },
      *blocks() {
        for (const part of parts)
          for (const [sectionY, section] of part.sections) {
            if (
              part.x * 16 >= bounds[3] ||
              part.x * 16 + 16 <= bounds[0] ||
              part.z * 16 >= bounds[5] ||
              part.z * 16 + 16 <= bounds[2]
            )
              continue;
            for (let index = 0; index < 4096; index++) {
              const id =
                section.Blocks[index] | (nibble(section.Add, index) << 8);
              if (!id) continue;
              const x = part.x * 16 + (index & 15),
                y = sectionY * 16 + (index >> 8),
                z = part.z * 16 + ((index >> 4) & 15);
              if (
                x >= bounds[0] &&
                x < bounds[3] &&
                z >= bounds[2] &&
                z < bounds[5]
              )
                yield { x, y, z, id, meta: nibble(section.Data, index) };
            }
          }
      }
    };
  }
  async function unzip(bytes, mapsOnly) {
    let total = 0;
    return new Promise((resolve, reject) => {
      zip.unzip(
        bytes,
        {
          filter(entry) {
            const selected = mapsOnly
              ? /(?:^|\/)map\.json$/i.test(entry.name)
              : /(?:^|\/)r\.-?\d+\.-?\d+\.mca$/i.test(entry.name);
            if (!selected) return false;
            total += entry.originalSize;
            if (entry.originalSize > MAX_BYTES || total > MAX_BYTES)
              throw new Error(
                'Selected world data exceeds 128 MB. Import a region file or a smaller export.'
              );
            return true;
          }
        },
        (error, files) => (error ? reject(error) : resolve(files))
      );
    });
  }
  async function read(bytes, filename, options = {}, progress = () => {}) {
    if (
      !(bytes instanceof Uint8Array) ||
      bytes.length > MAX_BYTES ||
      bytes.length < 2
    )
      throw new Error('Choose a map file smaller than 128 MB.');
    const name = filename.replace(/\.[^.]+$/, '').slice(0, 100);
    let terrain;
    if (/\.json$/i.test(filename))
      return validate(JSON.parse(new TextDecoder().decode(bytes)));
    if (bytes[0] === 80 && bytes[1] === 75) {
      progress('Opening world archive…');
      let files = await unzip(bytes, true);
      const maps = Object.keys(files)
        .filter((name) => /(?:^|\/)map\.json$/i.test(name))
        .sort((a, b) => a.length - b.length);
      if (maps.length)
        return validate(JSON.parse(new TextDecoder().decode(files[maps[0]])));
      files = await unzip(bytes, false);
      const names = Object.keys(files).filter((name) => /\.mca$/i.test(name));
      if (!names.length)
        throw new Error(
          'No Anvil region files or Optimus map.json found in this archive.'
        );
      const dimensions = new Set(
        names.map((path) => path.replace(/r\.-?\d+\.-?\d+\.mca$/i, ''))
      );
      if (dimensions.size > 1)
        throw new Error(
          'Archive contains several dimension folders. Import one region file or ZIP a single dimension’s region folder.'
        );
      terrain = world(
        names.sort().map((name) => files[name]),
        name,
        options
      );
    } else if (/\.mca$/i.test(filename))
      terrain = world([bytes], name, options);
    else if (/\.schematic$/i.test(filename))
      terrain = schematic(
        bytes[0] === 31 && bytes[1] === 139 ? inflate(bytes, 1) : bytes,
        name,
        options
      );
    else
      throw new Error(
        'Choose an Optimus map JSON, classic .schematic, Anvil .mca or world .zip.'
      );
    const map = await geometry.build(terrain, progress);
    const crypto = root.crypto || (node ? require('crypto').webcrypto : null);
    if (!crypto?.subtle)
      throw new Error(
        'This browser cannot hash map data. Use a current browser.'
      );
    const hash = new Uint8Array(
      await crypto.subtle.digest(
        'SHA-256',
        new TextEncoder().encode(
          JSON.stringify({
            bounds: map.bounds,
            boxes: map.boxes,
            nodes: map.nodes,
            edges: map.edges
          })
        )
      )
    );
    map.mapHash = [...hash]
      .map((n) => n.toString(16).padStart(2, '0'))
      .join('');
    return validate(map);
  }
  const api = { read, validate, chunks, schematic, world, inflate };
  if (node) module.exports = api;
  else root.MapImport = api;
})(typeof window === 'undefined' ? globalThis : window);