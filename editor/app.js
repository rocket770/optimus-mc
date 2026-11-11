/* Local-only editor. Draft exports are deliberately separate from live bot configuration. */
'use strict';
(() => {
  window.OPTIMUS_APP_BUILD = 'workshop-r4';
  const $ = (id) => document.getElementById(id);
  let map = window.PIT_MAP;
  if (!map) {
    $('status').textContent =
      'Missing pit-map.js. Keep all editor files together.';
    return;
  }
  if (
    map.editorBuild !== window.OPTIMUS_APP_BUILD ||
    PitGraph.build !== window.OPTIMUS_APP_BUILD
  ) {
    $('status').textContent =
      'Editor files are from different updates. Reopen the maintained editor.';
    return;
  }
  let graph = PitGraph.create(map);
  const canvas = $('map'),
    overlay = $('labels'),
    ctx = overlay.getContext('2d');
  const gl = canvas.getContext('webgl', { antialias: true });
  if (!gl) {
    $('status').textContent =
      'WebGL is unavailable. Enable browser hardware acceleration and reopen the editor.';
    return;
  }
  let state = graph.suggested(),
    selectedArea = null,
    selectedLink = null,
    draft = null,
    preview = null,
    mode = 'inspect',
    connectFrom = null;
  let ceiling = Infinity,
    drag = null,
    labelRects = [],
    history = [],
    camera = { target: [0, 64, -102], yaw: 0.65, pitch: 0.92, distance: 66 };
  let storageKey = 'optimus-route-workshop-' + map.mapHash;
  let storage = null,
    saved = null,
    savePending = false,
    storageFailed = false,
    restored = '',
    restoreError = '';
  try {
    storage = window.localStorage;
    if (!storage) storageFailed = true;
    else saved = storage.getItem(storageKey);
  } catch (_) {
    storageFailed = true;
  }
  try {
    if (saved != null) {
      state = graph.normalise(JSON.parse(saved));
      restored = 'Draft restored from this browser.';
    } else if (window.location?.hash.startsWith('#seed=')) {
      const seed = decodeURIComponent(window.location.hash.slice(6));
      if (seed.length > 8 * 1024 * 1024)
        throw new Error('Working draft is too large.');
      state = graph.normalise(JSON.parse(seed));
      restored = 'Your working draft is loaded.';
      savePending = true;
    }
  } catch (error) {
    restoreError =
      'Saved draft could not be restored: ' +
      error.message +
      ' Use Import to load your exported JSON.';
  }
  if (window.location?.hash.startsWith('#seed='))
    try {
      window.history?.replaceState(
        null,
        '',
        window.location.pathname + window.location.search
      );
    } catch (_) {}
  function persist() {
    if (savePending) {
      try {
        if (!storage) throw new Error('Storage unavailable');
        storage.setItem(storageKey, JSON.stringify(graph.serialise(state)));
        storageFailed = false;
      } catch (_) {
        storageFailed = true;
      }
      savePending = false;
    }
    $('editor-version').textContent =
      'Editor r4 · ' + (storageFailed ? 'export to save' : 'autosave on');
  }
  const vec = {
    add: (a, b) => a.map((v, i) => v + b[i]),
    sub: (a, b) => a.map((v, i) => v - b[i]),
    scale: (a, s) => a.map((v) => v * s),
    dot: (a, b) => a.reduce((n, v, i) => n + v * b[i], 0),
    cross: (a, b) => [
      a[1] * b[2] - a[2] * b[1],
      a[2] * b[0] - a[0] * b[2],
      a[0] * b[1] - a[1] * b[0]
    ]
  };
  vec.norm = (a) => vec.scale(a, 1 / Math.max(1e-9, Math.hypot(...a)));
  function multiply(a, b) {
    const m = new Float32Array(16);
    for (let c = 0; c < 4; c++)
      for (let r = 0; r < 4; r++)
        for (let k = 0; k < 4; k++) m[c * 4 + r] += a[k * 4 + r] * b[c * 4 + k];
    return m;
  }
  function view() {
    const d = camera.distance,
      p = camera.pitch,
      y = camera.yaw;
    const eye = vec.add(camera.target, [
      Math.sin(y) * Math.cos(p) * d,
      Math.sin(p) * d,
      Math.cos(y) * Math.cos(p) * d
    ]);
    const z = vec.norm(vec.sub(eye, camera.target)),
      x = vec.norm(vec.cross([0, 1, 0], z)),
      up = vec.cross(z, x);
    const m = new Float32Array([
      x[0],
      up[0],
      z[0],
      0,
      x[1],
      up[1],
      z[1],
      0,
      x[2],
      up[2],
      z[2],
      0,
      -vec.dot(x, eye),
      -vec.dot(up, eye),
      -vec.dot(z, eye),
      1
    ]);
    const f = 1 / Math.tan(Math.PI / 8),
      aspect = canvas.clientWidth / canvas.clientHeight,
      near = 0.1,
      far = Math.max(250, camera.distance * 8);
    const projection = new Float32Array([
      f / aspect,
      0,
      0,
      0,
      0,
      f,
      0,
      0,
      0,
      0,
      (far + near) / (near - far),
      -1,
      0,
      0,
      (2 * far * near) / (near - far),
      0
    ]);
    return {
      eye,
      right: x,
      up,
      forward: vec.scale(z, -1),
      matrix: multiply(projection, m),
      aspect
    };
  }
  function shader(type, source) {
    const s = gl.createShader(type);
    gl.shaderSource(s, source);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS))
      throw new Error(gl.getShaderInfoLog(s));
    return s;
  }
  const program = gl.createProgram();
  gl.attachShader(
    program,
    shader(
      gl.VERTEX_SHADER,
      'attribute vec3 position;attribute vec3 color;uniform mat4 matrix;varying vec3 tint;void main(){gl_Position=matrix*vec4(position,1.0);tint=color;}'
    )
  );
  gl.attachShader(
    program,
    shader(
      gl.FRAGMENT_SHADER,
      'precision mediump float;varying vec3 tint;void main(){gl_FragColor=vec4(tint,1.0);}'
    )
  );
  gl.linkProgram(program);
  if (!gl.getProgramParameter(program, gl.LINK_STATUS))
    throw new Error('Map renderer could not start.');
  gl.useProgram(program);
  const position = gl.getAttribLocation(program, 'position'),
    color = gl.getAttribLocation(program, 'color'),
    matrixLocation = gl.getUniformLocation(program, 'matrix');
  const terrainBuffer = gl.createBuffer(),
    lineBuffer = gl.createBuffer();
  let terrainCount = 0;
  const palette = {
    5: [0.48, 0.31, 0.18],
    17: [0.3, 0.23, 0.15],
    35: [0.5, 0.57, 0.35],
    41: [0.87, 0.69, 0.22],
    43: [0.58, 0.6, 0.57],
    44: [0.65, 0.67, 0.63],
    98: [0.43, 0.48, 0.47],
    112: [0.28, 0.13, 0.14],
    155: [0.8, 0.81, 0.73],
    159: [0.63, 0.4, 0.26],
    162: [0.34, 0.29, 0.2],
    164: [0.45, 0.32, 0.2]
  };
  function terrain() {
    const vertices = [],
      faces = [
        [3, 2, 6, 7, 1],
        [0, 4, 5, 1, 0.45],
        [0, 1, 2, 3, 0.7],
        [4, 7, 6, 5, 0.85],
        [0, 3, 7, 4, 0.65],
        [1, 5, 6, 2, 0.92]
      ];
    map.boxes.forEach((b) => {
      if (b[4] > ceiling + 0.01) return;
      const v = [
        [b[0], b[1], b[2]],
        [b[3], b[1], b[2]],
        [b[3], b[4], b[2]],
        [b[0], b[4], b[2]],
        [b[0], b[1], b[5]],
        [b[3], b[1], b[5]],
        [b[3], b[4], b[5]],
        [b[0], b[4], b[5]]
      ];
      const base = palette[b[6]] || [0.48, 0.51, 0.49];
      faces.forEach((face) =>
        [0, 1, 2, 0, 2, 3].forEach((i) =>
          vertices.push(...v[face[i]], ...base.map((c) => c * face[4]))
        )
      );
    });
    gl.bindBuffer(gl.ARRAY_BUFFER, terrainBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(vertices), gl.STATIC_DRAW);
    terrainCount = vertices.length / 6;
  }
  function bind(buffer) {
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
    gl.enableVertexAttribArray(position);
    gl.vertexAttribPointer(position, 3, gl.FLOAT, false, 24, 0);
    gl.enableVertexAttribArray(color);
    gl.vertexAttribPointer(color, 3, gl.FLOAT, false, 24, 12);
  }
  function project(point, matrix) {
    const q = [0, 0, 0, 0];
    for (let r = 0; r < 4; r++)
      q[r] =
        matrix[r] * point[0] +
        matrix[4 + r] * point[1] +
        matrix[8 + r] * point[2] +
        matrix[12 + r];
    return q[3] <= 0
      ? null
      : [
          ((q[0] / q[3] + 1) * canvas.clientWidth) / 2,
          ((1 - q[1] / q[3]) * canvas.clientHeight) / 2
        ];
  }
  function render() {
    const ratio = Math.min(devicePixelRatio || 1, 2),
      w = canvas.clientWidth,
      h = canvas.clientHeight;
    if (!w || !h) return;
    if (
      canvas.width !== Math.round(w * ratio) ||
      canvas.height !== Math.round(h * ratio)
    ) {
      canvas.width = overlay.width = Math.round(w * ratio);
      canvas.height = overlay.height = Math.round(h * ratio);
    }
    const v = view();
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.enable(gl.DEPTH_TEST);
    gl.clearColor(0.13, 0.18, 0.2, 1);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
    gl.uniformMatrix4fv(matrixLocation, false, v.matrix);
    bind(terrainBuffer);
    gl.drawArrays(gl.TRIANGLES, 0, terrainCount);
    const lines = [],
      green = [0.73, 0.96, 0.49],
      orange = [1, 0.71, 0.43],
      blue = [0.35, 0.72, 0.78];
    function line(a, b, c) {
      lines.push(...a, ...c, ...b, ...c);
    }
    function route(r, c) {
      if (!r) return;
      r.points.slice(1).forEach((p, i) => {
        const a = r.points[i];
        if (p[1] <= ceiling + 0.26 && a[1] <= ceiling + 0.26)
          line([a[0], a[1] + 0.16, a[2]], [p[0], p[1] + 0.16, p[2]], c);
      });
    }
    state.links.forEach((l) =>
      route(l.route, l.id === selectedLink ? orange : blue)
    );
    if (preview) route(preview.forward, orange);
    const visibleAreas = [
      ...state.areas,
      ...(draft && !draft.id ? [draft] : [])
    ].filter((a) => a.y <= ceiling + 0.26);
    visibleAreas.forEach((area) => {
      const a = draft && draft.id === area.id ? draft : area,
        c = a.id === selectedArea || a === draft ? orange : green,
        y = a.y + 0.13,
        r = a.radius;
      const corners = Array.from({ length: 32 }, (_, i) => [
        a.x + Math.cos((i * Math.PI) / 16) * r,
        y,
        a.z + Math.sin((i * Math.PI) / 16) * r
      ]);
      corners.forEach((p, i) => line(p, corners[(i + 1) % corners.length], c));
      line([a.x, y, a.z], [a.x, y + 0.65, a.z], c);
    });
    gl.bindBuffer(gl.ARRAY_BUFFER, lineBuffer);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(lines), gl.DYNAMIC_DRAW);
    bind(lineBuffer);
    gl.drawArrays(gl.LINES, 0, lines.length / 6);
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.clearRect(0, 0, w, h);
    ctx.font = '11px Segoe UI, sans-serif';
    labelRects = [];
    visibleAreas.forEach((a) => {
      const p = project([a.x, a.y + 0.8, a.z], v.matrix);
      if (!p || p[0] < 0 || p[0] > w || p[1] < 0 || p[1] > h) return;
      const text = (a.name || 'New area') + '  ·  Y ' + a.y,
        width = ctx.measureText(text).width + 16;
      let x = Math.max(5, Math.min(w - width - 5, p[0] - width / 2)),
        y = p[1] - 24;
      for (
        let n = 0;
        n < 6 &&
        labelRects.some(
          (r) =>
            x < r.x + r.w && x + width > r.x && y < r.y + 22 && y + 22 > r.y
        );
        n++
      )
        y -= 24;
      ctx.strokeStyle = '#a5c3b455';
      ctx.beginPath();
      ctx.moveTo(p[0], p[1]);
      ctx.lineTo(x + width / 2, y + 22);
      ctx.stroke();
      ctx.fillStyle = '#142023ed';
      ctx.fillRect(x, y, width, 22);
      ctx.strokeStyle = a.id === selectedArea ? '#ffb66e' : '#7ca45c';
      ctx.strokeRect(x, y, width, 22);
      ctx.fillStyle = '#e5efdf';
      ctx.fillText(text, x + 8, y + 15);
      labelRects.push({ id: a.id, x, y, w: width });
    });
  }
  function pick(x, y) {
    const v = view(),
      scale = Math.tan(Math.PI / 8),
      nx = (x / canvas.clientWidth) * 2 - 1,
      ny = 1 - (y / canvas.clientHeight) * 2;
    const direction = vec.norm(
      vec.add(
        v.forward,
        vec.add(
          vec.scale(v.right, nx * v.aspect * scale),
          vec.scale(v.up, ny * scale)
        )
      )
    );
    let best = Infinity,
      hit = null;
    map.boxes.forEach((b) => {
      if (b[4] > ceiling + 0.01) return;
      let near = -Infinity,
        far = Infinity,
        axis = -1,
        sign = 0;
      for (let i = 0; i < 3; i++) {
        if (Math.abs(direction[i]) < 1e-9) {
          if (v.eye[i] < b[i] || v.eye[i] > b[i + 3]) return;
          continue;
        }
        let a = (b[i] - v.eye[i]) / direction[i],
          z = (b[i + 3] - v.eye[i]) / direction[i];
        const s = a < z ? -1 : 1;
        if (a > z) [a, z] = [z, a];
        if (a > near) {
          near = a;
          axis = i;
          sign = s;
        }
        far = Math.min(far, z);
        if (near > far) return;
      }
      if (near > 0 && near < best && far >= near) {
        best = near;
        hit = {
          point: vec.add(v.eye, vec.scale(direction, near)),
          top: axis === 1 && sign === 1
        };
      }
    });
    if (!hit || !hit.top) return null;
    const id = graph.nearest(hit.point, 1.1);
    return id < 0 ? null : { id, point: map.nodes[id] };
  }
  function status(text, error = false) {
    $('status').textContent = text;
    $('status').style.color = error ? '#ffba9b' : '';
  }
  function remember() {
    history.push(JSON.stringify(graph.serialise(state)));
    if (history.length > 30) history.shift();
    $('undo').disabled = false;
    savePending = true;
  }
  function setMode(value) {
    mode = value;
    connectFrom = null;
    ['inspect', 'add', 'connect'].forEach((id) =>
      $(id).classList.toggle('active', mode === id)
    );
    canvas.style.cursor = value === 'inspect' ? 'grab' : 'crosshair';
    status(
      value === 'add'
        ? 'Click a supported top surface. The height cutaway reveals lower floors.'
        : value === 'connect'
          ? 'Click a saved starting area, then a destination area.'
          : 'Drag to orbit. Use Top view for precise area selection.'
    );
  }
  function selectArea(id) {
    selectedArea = id;
    selectedLink = null;
    draft = null;
    const a = state.areas.find((a) => a.id === id);
    if (!a) return;
    $('area-name').value = a.name;
    $('area-height').value = a.y;
    $('area-radius').value = a.radius;
    $('radius-text').textContent = a.radius + ' blocks';
    $('area-position').textContent =
      'X ' +
      a.x +
      ' · Z ' +
      a.z +
      ' · ' +
      graph.members(a).length +
      ' supported grid cells';
    $('save-area').disabled = false;
    $('remove-area').disabled = false;
    if (mode === 'connect') {
      if (!connectFrom) {
        connectFrom = id;
        $('from').value = id;
        status('Choose the destination area.');
      } else if (connectFrom !== id) {
        $('to').value = id;
        connectFrom = null;
        previewRoute();
      }
    }
    refreshLists();
    render();
  }
  function refreshLists() {
    const oldFrom = $('from').value,
      oldTo = $('to').value;
    $('areas').replaceChildren();
    $('links').replaceChildren();
    $('from').replaceChildren();
    $('to').replaceChildren();
    state.areas.forEach((a) => {
      const button = document.createElement('button'),
        name = document.createElement('span'),
        height = document.createElement('small');
      name.textContent = a.name;
      height.textContent = 'Y ' + a.y;
      button.append(name, height);
      button.classList.toggle('selected', a.id === selectedArea);
      button.onclick = () => selectArea(a.id);
      $('areas').append(button);
      ['from', 'to'].forEach((id) => {
        const option = document.createElement('option');
        option.value = a.id;
        option.textContent = a.name;
        $(id).append(option);
      });
    });
    $('from').value = state.areas.some((a) => a.id === oldFrom)
      ? oldFrom
      : state.areas[0]?.id || '';
    $('to').value = state.areas.some((a) => a.id === oldTo)
      ? oldTo
      : state.areas[1]?.id || state.areas[0]?.id || '';
    state.links.forEach((l) => {
      const a = state.areas.find((a) => a.id === l.from),
        b = state.areas.find((a) => a.id === l.to),
        button = document.createElement('button');
      button.textContent =
        a.name.split(' · ').pop() + ' → ' + b.name.split(' · ').pop();
      button.classList.toggle('selected', l.id === selectedLink);
      button.onclick = () => {
        selectedLink = l.id;
        selectedArea = null;
        draft = null;
        preview = { forward: l.route, reverse: null };
        $('from').value = l.from;
        $('to').value = l.to;
        $('parkour').checked = l.parkour;
        $('both').checked = false;
        $('remove-link').disabled = false;
        routeDetail(l.route);
        refreshLists();
        $('from').value = l.from;
        $('to').value = l.to;
        render();
      };
      $('links').append(button);
    });
    $('area-count').textContent = state.areas.length + ' saved';
    $('link-count').textContent = state.links.length + ' directed';
    $('undo').disabled = !history.length;
    persist();
  }
  function routeName(from, to) {
    return from.name + ' (Y ' + from.y + ') → ' + to.name + ' (Y ' + to.y + ')';
  }
  function routeDetail(route) {
    const from = state.areas.find((a) => a.id === $('from').value),
      to = state.areas.find((a) => a.id === $('to').value);
    const advanced = [];
    if (route.kinds.includes(4))
      advanced.push('momentum chain: two consecutive sprint jumps');
    if (route.kinds.includes(5))
      advanced.push(
        'precision jump: tight corner takeoff and a timed final step'
      );
    $('route-detail').textContent =
      (from && to ? routeName(from, to) + ': ' : '') +
      route.points.length +
      ' model points · ' +
      route.visited +
      ' graph nodes searched · cost ' +
      route.cost.toFixed(1) +
      (advanced.length
        ? '. Includes a ' +
          advanced.join('; ') +
          '. Advanced movement; draft preview only.'
        : '. Preview only; live validation is required.');
  }
  function previewRoute() {
    const from = state.areas.find((a) => a.id === $('from').value),
      to = state.areas.find((a) => a.id === $('to').value);
    preview = null;
    selectedLink = null;
    $('save-link').disabled = true;
    function failed(message) {
      $('route-detail').textContent = message;
      status(message, true);
      render();
    }
    if (!from || !to || from === to) {
      failed('Choose two different saved areas.');
      return;
    }
    const forward = graph.path(from, to, $('parkour').checked),
      reverse = $('both').checked
        ? graph.path(to, from, $('parkour').checked)
        : null;
    if (!forward) {
      let reason = 'No model-checked route: ' + routeName(from, to) + '. ';
      if (!$('parkour').checked && graph.path(from, to, true))
        reason += 'Enable “Include model-checked jumps” for this direction.';
      else if (graph.path(to, from, $('parkour').checked))
        reason +=
          'A route exists in the reverse direction. Use “Swap direction and preview” to inspect it.';
      else
        reason +=
          'The captured graph has no connection in this direction. Check the selected surfaces and heights; a missing model edge can also cause this. Adding an intermediate area does not add terrain connections.';
      failed(reason);
      return;
    }
    if ($('both').checked && !reverse) {
      failed(
        'A route exists: ' +
          routeName(from, to) +
          '. The return route is unavailable. Turn off “Check both directions separately” to preview and save the one-way connection.'
      );
      return;
    }
    preview = { forward, reverse };
    routeDetail(forward);
    $('save-link').disabled = false;
    status(
      'Preview ready. Save this connection to include it in the draft graph.'
    );
    render();
  }
  function clearSelection() {
    selectedArea = selectedLink = null;
    draft = preview = null;
    $('area-name').value = '';
    $('area-height').value = '';
    ['save-area', 'remove-area', 'save-link', 'remove-link'].forEach(
      (id) => ($(id).disabled = true)
    );
  }
  function clicked(x, y) {
    const label = labelRects.find(
      (r) => r.id && x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + 22
    );
    if (label && mode !== 'add') {
      selectArea(label.id);
      return;
    }
    const hit = pick(x, y);
    if (!hit) {
      status(
        'Choose a clear, supported top surface. Try Top view or a lower height cutaway.',
        true
      );
      return;
    }
    if (mode === 'add') {
      clearSelection();
      const p = hit.point;
      draft = {
        name: 'Area ' + (state.areas.length + 1),
        x: p[0],
        y: p[1],
        z: p[2],
        radius: Number($('area-radius').value)
      };
      $('area-name').value = draft.name;
      $('area-height').value = p[1];
      $('save-area').disabled = false;
      $('area-position').textContent =
        'X ' +
        p[0] +
        ' · Z ' +
        p[2] +
        ' · ' +
        graph.members(draft).length +
        ' supported cells';
      status(
        'Name this area and save it. Its radius gives the bot room to choose a point.'
      );
      render();
    } else {
      const a = state.areas
        .filter((a) => Math.abs(a.y - hit.point[1]) < 0.26)
        .sort(
          (a, b) =>
            Math.hypot(a.x - hit.point[0], a.z - hit.point[2]) -
            Math.hypot(b.x - hit.point[0], b.z - hit.point[2])
        )[0];
      if (
        a &&
        Math.hypot(a.x - hit.point[0], a.z - hit.point[2]) <= a.radius + 0.6
      )
        selectArea(a.id);
      else
        status(
          'X ' +
            hit.point[0] +
            ' · Y ' +
            hit.point[1] +
            ' · Z ' +
            hit.point[2] +
            '. Choose Add area to save this surface.'
        );
    }
  }
  ['inspect', 'add', 'connect'].forEach(
    (id) =>
      ($(id).onclick = () => {
        setMode(id);
        if (id === 'add') clearSelection();
        render();
      })
  );
  $('area-radius').oninput = () => {
    const source = draft || state.areas.find((a) => a.id === selectedArea);
    $('radius-text').textContent = $('area-radius').value + ' blocks';
    if (source) {
      draft = { ...source, radius: Number($('area-radius').value) };
      render();
    }
  };
  $('save-area').onclick = () => {
    const a = draft || state.areas.find((a) => a.id === selectedArea);
    if (!a || !$('area-name').value.trim()) {
      status('Choose a surface and give the area a name.', true);
      return;
    }
    const area = {
      ...a,
      name: $('area-name').value.trim(),
      radius: Number($('area-radius').value)
    };
    if (!area.id) {
      let base =
        area.name
          .toLowerCase()
          .replace(/[^a-z0-9]+/g, '-')
          .replace(/^-|-$/g, '')
          .slice(0, 45) || 'area';
      if (!/^[a-z]/.test(base)) base = 'area-' + base;
      let id = base;
      for (let n = 2; state.areas.some((a) => a.id === id); n++)
        id = base + '-' + n;
      area.id = id;
    }
    if (
      state.areas.length >= 128 &&
      !state.areas.some((a) => a.id === area.id)
    ) {
      status('The draft can contain up to 128 areas.', true);
      return;
    }
    try {
      const areas = state.areas.filter((a) => a.id !== area.id).concat(area);
      const next = graph.normalise({ ...graph.serialise(state), areas });
      remember();
      state = next;
      clearSelection();
      refreshLists();
      selectArea(area.id);
      status(
        'Area saved. Its connections were rechecked against the updated area.'
      );
    } catch (error) {
      status(error.message, true);
    }
  };
  $('remove-area').onclick = () => {
    if (!selectedArea) return;
    remember();
    state.areas = state.areas.filter((a) => a.id !== selectedArea);
    state.links = state.links.filter(
      (l) => l.from !== selectedArea && l.to !== selectedArea
    );
    clearSelection();
    refreshLists();
    render();
    status('Area and its connections removed.');
  };
  ['from', 'to', 'parkour', 'both'].forEach(
    (id) =>
      ($(id).onchange = () => {
        preview = null;
        $('save-link').disabled = true;
        render();
      })
  );
  $('preview').onclick = previewRoute;
  $('swap').onclick = () => {
    const from = $('from').value;
    $('from').value = $('to').value;
    $('to').value = from;
    connectFrom = null;
    previewRoute();
  };
  $('save-link').onclick = () => {
    if (!preview) return;
    if (state.links.length >= 510) {
      status('The draft can contain up to 512 directed connections.', true);
      return;
    }
    remember();
    const from = $('from').value,
      to = $('to').value,
      parkour = $('parkour').checked;
    function save(a, b, route) {
      state.links = state.links.filter((l) => l.from !== a || l.to !== b);
      state.links.push({
        id: 'link-' + Date.now() + '-' + state.links.length,
        from: a,
        to: b,
        parkour,
        route
      });
    }
    save(from, to, preview.forward);
    if (preview.reverse) save(to, from, preview.reverse);
    preview = null;
    $('save-link').disabled = true;
    refreshLists();
    render();
    status('Directed connection saved to this draft.');
  };
  $('remove-link').onclick = () => {
    if (!selectedLink) return;
    remember();
    state.links = state.links.filter((l) => l.id !== selectedLink);
    selectedLink = null;
    preview = null;
    $('remove-link').disabled = true;
    refreshLists();
    render();
  };
  $('undo').onclick = () => {
    if (!history.length) return;
    state = graph.normalise(JSON.parse(history.pop()));
    savePending = true;
    clearSelection();
    refreshLists();
    render();
    status('Last graph edit undone.');
  };
  $('suggest').onclick = () => {
    remember();
    state = graph.suggested();
    clearSelection();
    refreshLists();
    render();
    status('Suggested areas restored. Undo returns to your previous graph.');
  };
  $('clear').onclick = () => {
    remember();
    state = { areas: [], links: [] };
    clearSelection();
    refreshLists();
    render();
    setMode('add');
  };
  $('export').onclick = () => {
    const blob = new Blob([JSON.stringify(graph.serialise(state), null, 2)], {
        type: 'application/json'
      }),
      url = URL.createObjectURL(blob),
      a = document.createElement('a');
    a.href = url;
    a.download = 'optimus-route-hints.json';
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
    status(
      'Route hints exported. Load the matching map and hints in Optimus before using /o routes reload.'
    );
  };
  $('import').onclick = () => $('file').click();
  $('file').onchange = async () => {
    const file = $('file').files[0];
    if (!file) return;
    try {
      if (file.size > 8 * 1024 * 1024)
        throw new Error('Choose a JSON file smaller than 8 MB.');
      const next = graph.normalise(JSON.parse(await file.text()));
      remember();
      state = next;
      clearSelection();
      refreshLists();
      render();
      status('Draft imported and its connections checked against this map.');
    } catch (error) {
      status(error.message, true);
    }
    $('file').value = '';
  };
  $('height').onchange = () => {
    ceiling = Number($('height').value);
    terrain();
    render();
    status(
      !Number.isFinite(ceiling)
        ? 'Showing all elevations.'
        : 'Showing collision surfaces at or below Y ' + ceiling + '.'
    );
  };
  $('top').onclick = () => {
    camera.pitch = Math.PI / 2 - 0.001;
    camera.yaw = 0;
    render();
  };
  $('reset').onclick = () => {
    camera = defaultCamera();
    render();
  };
  canvas.oncontextmenu = (e) => e.preventDefault();
  canvas.onpointerdown = (e) => {
    canvas.setPointerCapture(e.pointerId);
    drag = {
      x: e.clientX,
      y: e.clientY,
      startX: e.clientX,
      startY: e.clientY,
      moved: false,
      pan: e.shiftKey || e.button === 2
    };
  };
  canvas.onpointermove = (e) => {
    const rect = canvas.getBoundingClientRect(),
      x = e.clientX - rect.left,
      y = e.clientY - rect.top;
    if (drag) {
      const dx = e.clientX - drag.x,
        dy = e.clientY - drag.y;
      drag.moved ||=
        Math.hypot(e.clientX - drag.startX, e.clientY - drag.startY) > 4;
      if (drag.moved) {
        if (drag.pan) {
          const v = view();
          camera.target = vec.add(
            camera.target,
            vec.add(
              vec.scale(v.right, (-dx * camera.distance) / 900),
              vec.scale(
                vec.norm([v.up[0], 0, v.up[2]]),
                (dy * camera.distance) / 900
              )
            )
          );
        } else {
          camera.yaw -= dx * 0.008;
          camera.pitch = Math.max(
            0.18,
            Math.min(Math.PI / 2 - 0.001, camera.pitch + dy * 0.006)
          );
        }
        render();
      }
      drag.x = e.clientX;
      drag.y = e.clientY;
    } else {
      const hit = pick(x, y);
      $('coordinates').textContent = hit
        ? 'X ' + hit.point[0] + '   Y ' + hit.point[1] + '   Z ' + hit.point[2]
        : 'Choose a supported top surface';
    }
  };
  canvas.onpointerup = (e) => {
    if (drag && !drag.moved) {
      const r = canvas.getBoundingClientRect();
      clicked(e.clientX - r.left, e.clientY - r.top);
    }
    drag = null;
  };
  canvas.onpointercancel = () => (drag = null);
  canvas.onwheel = (e) => {
    e.preventDefault();
    camera.distance = Math.max(
      9,
      Math.min(
        Math.max(140, mapSpan() * 3),
        camera.distance * Math.exp(e.deltaY * 0.001)
      )
    );
    render();
  };
  function mapSpan() {
    return Math.max(
      map.bounds[3] - map.bounds[0],
      map.bounds[5] - map.bounds[2]
    );
  }
  function defaultCamera() {
    if (
      map.mapHash ===
      'b84d5626434409549056b173a614e12965b8f3d12fd7d622641b7214502f1c94'
    )
      return { target: [0, 64, -102], yaw: 0.65, pitch: 0.92, distance: 66 };
    const ys = map.nodes.map((point) => point[1]).sort((a, b) => a - b);
    return {
      target: [
        (map.bounds[0] + map.bounds[3]) / 2,
        ys.length
          ? ys[Math.floor(ys.length / 2)]
          : (map.bounds[1] + map.bounds[4]) / 2,
        (map.bounds[2] + map.bounds[5]) / 2
      ],
      yaw: 0.65,
      pitch: 0.92,
      distance: Math.max(18, mapSpan() * 1.3)
    };
  }
  function updateMapControls() {
    $('height').textContent = '';
    const all = document.createElement('option');
    all.value = 'Infinity';
    all.textContent = 'All elevations';
    $('height').append(all);
    [...new Set(map.nodes.map((p) => p[1]))]
      .sort((a, b) => b - a)
      .forEach((y) => {
        const option = document.createElement('option');
        option.value = y;
        option.textContent = 'At / below Y ' + y;
        $('height').append(option);
      });
    $('height').value = 'Infinity';
    ceiling = Infinity;
    $('map-name').textContent = map.name || 'Captured Duels pit';
    $('map-stats').textContent =
      map.nodes.length.toLocaleString() +
      ' supported surfaces / ' +
      map.edges.length.toLocaleString() +
      ' directed connections';
    camera = defaultCamera();
  }
  function installMap(next) {
    persist();
    const nextGraph = PitGraph.create(next);
    let nextState = nextGraph.suggested(),
      message = '';
    const nextKey = 'optimus-route-workshop-' + next.mapHash;
    try {
      const saved = storage?.getItem(nextKey);
      if (saved != null) {
        nextState = nextGraph.normalise(JSON.parse(saved));
        message = 'Saved route draft restored. ';
      }
    } catch (error) {
      message =
        'Saved route draft could not be restored: ' + error.message + ' ';
    }
    map = next;
    graph = nextGraph;
    state = nextState;
    storageKey = nextKey;
    history = [];
    savePending = false;
    clearSelection();
    updateMapControls();
    terrain();
    refreshLists();
    setMode('inspect');
    render();
    status(
      message +
        (next.importNote || 'Native collision map opened.') +
        ' Area: X ' +
        next.bounds[0] +
        ' to ' +
        next.bounds[3] +
        ', Z ' +
        next.bounds[2] +
        ' to ' +
        next.bounds[5] +
        '.'
    );
  }
  function downloadJson(value, filename) {
    const blob = new Blob([JSON.stringify(value)], {
        type: 'application/json'
      }),
      url = URL.createObjectURL(blob),
      link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  $('open-map').onclick = () => $('map-file').click();
  $('map-file').onchange = async () => {
    const file = $('map-file').files[0];
    if (!file) return;
    $('open-map').disabled = true;
    try {
      if (file.size > 128 * 1024 * 1024)
        throw new Error('Choose a map file smaller than 128 MB.');
      status('Opening map...');
      const options = {
        radius: Number($('import-radius').value || 32),
        centerX: $('import-x').value.trim()
          ? Number($('import-x').value)
          : null,
        centerZ: $('import-z').value.trim() ? Number($('import-z').value) : null
      };
      const next = await MapImport.read(
        new Uint8Array(await file.arrayBuffer()),
        file.name,
        options,
        (message) => status(message)
      );
      installMap(next);
    } catch (error) {
      status(error.message, true);
    } finally {
      $('open-map').disabled = false;
      $('map-file').value = '';
    }
  };
  $('download-map').onclick = () => {
    downloadJson(
      {
        ...map,
        format: 'optimus-map',
        name: map.name || 'Captured Duels pit',
        model: map.model || 'native-collision'
      },
      'optimus-map.json'
    );
    status(
      'Map downloaded. Open this JSON in the workshop to restore this terrain. Route drafts export separately.'
    );
  };
  updateMapControls();
  new ResizeObserver(render).observe($('viewport'));
  terrain();
  refreshLists();
  setMode('inspect');
  render();
  const saveMessage = storageFailed
    ? 'Browser saving is unavailable. Export a JSON backup before closing.'
    : 'Edits save locally in this browser. Export a JSON backup before moving to another browser.';
  status(
    restoreError ||
      (restored || 'Suggested areas are editable.') + ' ' + saveMessage,
    !!restoreError
  );
})();