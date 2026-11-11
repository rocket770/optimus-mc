// Exercise the self-contained renderer without a browser or connection to a client.
const fs = require('fs'),
  vm = require('vm'),
  path = require('path'),
  assert = require('assert');
const rootPath = path.resolve(__dirname, '..');
const fragment = fs.readFileSync(
  path.join(rootPath, 'diagnostics/parkour-replay.html'),
  'utf8'
);
const code = fragment.match(/<script>([\s\S]*?)<\/script>/)[1];
let width = 736;
class Element {
  constructor(tag) {
    this.tag = tag;
    this.attributes = {};
    this.children = [];
    this.listeners = {};
    this.textContent = '';
  }
  setAttribute(k, v) {
    this.attributes[k] = String(v);
  }
  appendChild(n) {
    this.children.push(n);
  }
  replaceChildren() {
    this.children = [];
  }
  getBoundingClientRect() {
    return { width };
  }
  addEventListener(k, fn) {
    this.listeners[k] = fn;
  }
  querySelector(selector) {
    return selector === '[value="before"]'
      ? beforeOption
      : nodes[selector.slice(1)];
  }
}
const nodes = {};
for (const id of [
  'optimus-parkour-replay',
  'optimus-scene',
  'optimus-overview',
  'optimus-case',
  'optimus-revision',
  'optimus-frame',
  'optimus-tick',
  'optimus-detail',
  'optimus-result'
])
  nodes[id] = new Element(
    id.includes('scene') || id.includes('overview') ? 'svg' : 'div'
  );
const beforeOption = new Element('option');
const document = {
  getElementById: (id) => nodes[id],
  createElement: (tag) => new Element(tag),
  createElementNS: (_, tag) => new Element(tag)
};
const window = {
  localStorage: {
    getItem() {
      return null;
    },
    setItem() {}
  }
};
vm.runInNewContext(code, {
  document,
  window,
  ResizeObserver: class {
    observe() {}
  }
});
const palette = {
  '--foreground': '#20252a',
  '--border': '#b8bfc7',
  '--muted': '#e0e4e9',
  '--secondary': '#c3cbd4',
  '--card': '#f4f6f8',
  '--viz-series-1': '#166b9b',
  '--viz-series-2': '#b76616'
};
function serialize(node) {
  const attrs = Object.entries(node.attributes)
    .map(
      ([k, v]) =>
        ` ${k}="${v.replace(/var\(([^)]+)\)/g, (_, n) => palette[n])}"`
    )
    .join('');
  return `<${node.tag}${attrs}>${node.textContent}${node.children.map(serialize).join('')}</${node.tag}>`;
}
function exportScene(name) {
  const svg = nodes['optimus-scene'];
  svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
  fs.writeFileSync(
    path.join(rootPath, 'diagnostics', name + '.svg'),
    serialize(svg)
  );
  for (const p of svg.children.filter((c) => c.tag === 'polygon'))
    for (const xy of p.attributes.points.split(' ')) {
      const [x, y] = xy.split(',').map(Number);
      assert(
        x >= 0 && x <= width && y >= 0 && y <= Number(svg.attributes.height),
        'map polygon must fit'
      );
    }
}
for (const option of nodes['optimus-case'].children) {
  nodes['optimus-case'].value = option.value;
  nodes['optimus-case'].listeners.change();
  assert(nodes['optimus-result'].textContent.startsWith('Arrived'));
  assert(
    nodes['optimus-scene'].children.filter((c) => c.tag === 'polygon').length >
      10,
    'saved geometry must be visible'
  );
  nodes['optimus-frame'].value = Math.floor(
    Number(nodes['optimus-frame'].max) / 2
  );
  nodes['optimus-frame'].listeners.input();
  assert(
    nodes['optimus-tick'].textContent.startsWith(
      String(Number(nodes['optimus-frame'].value) + 1)
    )
  );
}
nodes['optimus-case'].value = 'wool_diagonal_step';
nodes['optimus-case'].listeners.change();
nodes['optimus-frame'].value = 22;
nodes['optimus-frame'].listeners.input();
exportScene('parkour-after-preview');
if (!beforeOption.disabled) {
  nodes['optimus-revision'].value = 'before';
  nodes['optimus-revision'].listeners.change();
  nodes['optimus-frame'].value = Number(nodes['optimus-frame'].max);
  nodes['optimus-frame'].listeners.input();
  exportScene('parkour-before-preview');
}
width = 320;
nodes['optimus-revision'].value = 'after';
nodes['optimus-revision'].listeners.change();
exportScene('parkour-narrow-preview');
console.log(
  'PASS: replay selection, optional comparison, movement slider, collision geometry and 320px bounds'
);