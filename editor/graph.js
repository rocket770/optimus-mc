(function (root) {
  'use strict';
  function create(map) {
    const adjacency = map.nodes.map(() => []);
    const chains = new Map(
      (map.momentumChains || []).map((c) => [c.from + ':' + c.to, c])
    );
    const precision = new Map(
      (map.precisionJumps || []).map((c) => [c.from + ':' + c.to, c])
    );
    map.edges.forEach((e) => {
      if (e[2] === 4 && !chains.has(e[0] + ':' + e[1]))
        throw new Error(
          'Momentum connection is missing its checked trajectory.'
        );
      if (e[2] === 5 && !precision.has(e[0] + ':' + e[1]))
        throw new Error(
          'Precision connection is missing its checked trajectory.'
        );
      adjacency[e[0]].push({ to: e[1], kind: e[2], cost: e[3] });
    });
    function members(area) {
      return map.nodes.flatMap((p, i) =>
        Math.abs(p[1] - area.y) <= 0.26 &&
        Math.hypot(p[0] - area.x, p[2] - area.z) <= area.radius
          ? [i]
          : []
      );
    }
    function nearest(point, maximum = 1.5) {
      let id = -1,
        best = maximum * maximum;
      map.nodes.forEach((p, i) => {
        if (Math.abs(p[1] - point[1]) > 0.26) return;
        const d = (p[0] - point[0]) ** 2 + (p[2] - point[2]) ** 2;
        if (d < best) {
          best = d;
          id = i;
        }
      });
      return id;
    }
    function path(from, to, parkour = true) {
      const starts = members(from),
        goals = new Set(members(to));
      if (!starts.length || !goals.size) return null;
      const costs = new Float64Array(map.nodes.length).fill(Infinity),
        parent = new Int32Array(map.nodes.length).fill(-1);
      const heap = [];
      function push(value) {
        let i = heap.length;
        heap.push(value);
        while (i) {
          const p = (i - 1) >> 1;
          if (heap[p].f <= value.f) break;
          heap[i] = heap[p];
          i = p;
        }
        heap[i] = value;
      }
      function pop() {
        const first = heap[0],
          tail = heap.pop();
        if (heap.length) {
          let i = 0;
          while (i * 2 + 1 < heap.length) {
            let c = i * 2 + 1;
            if (c + 1 < heap.length && heap[c + 1].f < heap[c].f) c++;
            if (tail.f <= heap[c].f) break;
            heap[i] = heap[c];
            i = c;
          }
          heap[i] = tail;
        }
        return first;
      }
      const heuristic = (id) =>
        Math.max(
          0,
          Math.hypot(map.nodes[id][0] - to.x, map.nodes[id][2] - to.z) -
            to.radius
        );
      starts.forEach((id) => {
        costs[id] = 0;
        push({ id, g: 0, f: heuristic(id) });
      });
      let visited = 0;
      while (heap.length) {
        const current = pop();
        if (current.g !== costs[current.id]) continue;
        visited++;
        if (goals.has(current.id)) {
          const ids = [];
          for (let id = current.id; id !== -1; id = parent[id]) ids.push(id);
          ids.reverse();
          const kinds = ids
              .slice(1)
              .map((id, i) => adjacency[ids[i]].find((e) => e.to === id).kind),
            points = [map.nodes[ids[0]]];
          ids.slice(1).forEach((id, i) => {
            const key = ids[i] + ':' + id,
              trajectory =
                kinds[i] === 4
                  ? chains.get(key)
                  : kinds[i] === 5
                    ? precision.get(key)
                    : null;
            if (trajectory) points.push(...trajectory.points.slice(1));
            else points.push(map.nodes[id]);
          });
          return { ids, cost: current.g, visited, points, kinds };
        }
        adjacency[current.id].forEach((edge) => {
          if (
            !parkour &&
            (edge.kind === 1 ||
              edge.kind === 2 ||
              edge.kind === 4 ||
              edge.kind === 5)
          )
            return;
          const next = current.g + edge.cost;
          if (next >= costs[edge.to]) return;
          costs[edge.to] = next;
          parent[edge.to] = current.id;
          push({ id: edge.to, g: next, f: next + heuristic(edge.to) });
        });
      }
      return null;
    }
    function normalise(input) {
      if (
        !input ||
        input.format !== 'optimus-route-hints' ||
        input.schema !== 1 ||
        input.mapHash !== map.mapHash
      )
        throw new Error('This file belongs to a different map or format.');
      if (
        !Array.isArray(input.areas) ||
        input.areas.length > 128 ||
        !Array.isArray(input.links) ||
        input.links.length > 512
      )
        throw new Error('Use at most 128 areas and 512 directed connections.');
      const ids = new Set();
      const areas = input.areas.map((a) => {
        if (
          !a ||
          !/^[a-zA-Z][\w-]{0,63}$/.test(a.id) ||
          ids.has(a.id) ||
          typeof a.name !== 'string' ||
          !a.name.trim() ||
          a.name.length > 60 ||
          ![a.x, a.y, a.z, a.radius].every(Number.isFinite) ||
          a.radius < 0.4 ||
          a.radius > 3.5
        )
          throw new Error('Invalid or duplicate area.');
        const area = {
          id: a.id,
          name: a.name.trim(),
          x: a.x,
          y: a.y,
          z: a.z,
          radius: a.radius
        };
        if (!members(area).length)
          throw new Error('Area has no supported surface: ' + area.name);
        ids.add(area.id);
        return area;
      });
      const links = input.links.map((l, index) => {
        const from = areas.find((a) => a.id === l.from),
          to = areas.find((a) => a.id === l.to);
        if (!from || !to || from === to)
          throw new Error(
            'Connection endpoints must be different saved areas.'
          );
        const route = path(from, to, l.parkour === true);
        if (!route)
          throw new Error(
            'No model-checked connection: ' + from.name + ' → ' + to.name
          );
        return {
          id: 'link-' + index,
          from: from.id,
          to: to.id,
          parkour: l.parkour === true,
          route
        };
      });
      return { areas, links };
    }
    function serialise(state) {
      return {
        format: 'optimus-route-hints',
        schema: 1,
        status: 'draft',
        mapHash: map.mapHash,
        physicsVersion: '0.4.33',
        editorBuild: 'workshop-r4',
        editorGraphVersion: 'precision-jump-1',
        areas: state.areas.map((a) => ({
          id: a.id,
          name: a.name,
          x: a.x,
          y: a.y,
          z: a.z,
          radius: a.radius
        })),
        links: state.links.map((l) => ({
          from: l.from,
          to: l.to,
          parkour: l.parkour,
          validation: 'collision-model',
          requirements: [
            ...(l.route.kinds.includes(4) ? ['momentum-chain'] : []),
            ...(l.route.kinds.includes(5) ? ['precision-jump'] : [])
          ],
          modelPath: l.route.points
        }))
      };
    }
    function suggested() {
      if (
        map.mapHash !==
        'b84d5626434409549056b173a614e12965b8f3d12fd7d622641b7214502f1c94'
      )
        return { areas: [], links: [] };
      const hints = [
        ['east-low', 'East · lower platform', 16.5, 64, -81.5],
        ['east-step', 'East · middle platform', 13.5, 65, -79.5],
        ['east-high', 'East · upper platform', 8.5, 66, -80.5],
        ['west-start', 'West · roof approach', -14, 68.5, -105],
        ['west-end', 'West · lower roof', -19, 67.5, -100],
        ['north-start', 'North · launch platform', -1.5, 69, -81.5],
        ['north-end', 'North · slab roof', -1.5, 68.5, -87.5],
        ['stair-foot', 'West · stair foot', -14.5, 63, -116.5],
        ['stair-top', 'West · stair top', -13.5, 65, -115.5],
        ['pit-floor', 'Pit · central floor', 0.5, 63, -100.5]
      ];
      const areas = hints.flatMap((h) => {
        const id = nearest(h.slice(2));
        if (id < 0) return [];
        const p = map.nodes[id];
        return [
          { id: h[0], name: h[1], x: p[0], y: p[1], z: p[2], radius: 1.1 }
        ];
      });
      const links = [];
      [
        ['east-low', 'east-step'],
        ['east-step', 'east-high'],
        ['west-start', 'west-end'],
        ['north-start', 'north-end'],
        ['pit-floor', 'stair-foot'],
        ['stair-foot', 'stair-top']
      ].forEach((pair) => {
        const from = areas.find((a) => a.id === pair[0]),
          to = areas.find((a) => a.id === pair[1]);
        if (!from || !to) return;
        const route = path(from, to, true);
        if (route)
          links.push({
            id: 'link-' + links.length,
            from: from.id,
            to: to.id,
            parkour: true,
            route
          });
      });
      return { areas, links };
    }
    return { members, nearest, path, normalise, serialise, suggested };
  }
  const api = { create, build: 'workshop-r4' };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.PitGraph = api;
})(typeof window === 'undefined' ? globalThis : window);