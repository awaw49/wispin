const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {join} = require('node:path');
const vm = require('node:vm');

const html = readFileSync(join(__dirname, '../app/src/main/assets/map.html'), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const POINTS = [{lat: 31, lon: 121}, {lat: 32, lon: 122}, {lat: 33, lon: 123}];

function layer(kind) {
  return {
    kind, handlers: {},
    on(event, handler) { this.handlers[event] = handler; return this; },
    addTo(target) { target.layers.add(this); return this; }
  };
}

function setup(isPath = true) {
  const state = {layers: new Set(), nativeCalls: [], lines: []};
  const native = new Proxy({}, {get: (_, name) => (...args) => state.nativeCalls.push([name, ...args])});
  const map = {
    layers: state.layers, handlers: {},
    setView() { return this; }, fitBounds() {},
    hasLayer(item) { return this.layers.has(item); },
    removeLayer(item) { this.layers.delete(item); },
    on(event, handler) { this.handlers[event] = handler; }
  };
  const L = leafletDouble(state, map);
  const document = {
    getElementById: () => ({}),
    createElement: () => ({addEventListener(event, listener) { this[event] = listener; }})
  };
  const context = vm.createContext({L, AndroidMap: native, document,
    window: {AndroidMap: native, addEventListener() {}}});
  vm.runInContext(source, context);
  vm.runInContext(`init(31,121,13,${isPath})`, context);
  return {state, context, map};
}

function leafletDouble(state, map) {
  return {
    map: () => map,
    layerGroup() {
      const group = layer('markers');
      group.layers = new Set();
      group.clearLayers = () => group.layers.clear();
      state.markers = group;
      return group;
    },
    tileLayer: () => layer('tile'),
    divIcon: options => options,
    latLngBounds: positions => positions,
    polyline(positions) {
      const line = layer('line');
      line.positions = positions;
      state.lines.push(line);
      return line;
    },
    marker(position) {
      const marker = layer('marker');
      marker.position = {lat: position[0], lng: position[1]};
      marker.getLatLng = () => marker.position;
      marker.bindPopup = popup => { marker.popup = popup; };
      return marker;
    }
  };
}

function render(harness, points = POINTS, datum = 'wgs') {
  vm.runInContext(`renderRoute(${JSON.stringify(points)},'${datum}')`, harness.context);
}

test('three or more waypoints render without treating a polyline as a marker', () => {
  const harness = setup();
  render(harness);
  assert.equal(harness.state.markers.layers.size, POINTS.length);
  const line = harness.state.lines.at(-1);
  assert.deepEqual(JSON.parse(JSON.stringify(line.positions)), POINTS.map(p => [p.lat, p.lon]));
  assert.equal([...harness.state.markers.layers].some(item => item.kind === 'line'), false);
  render(harness, POINTS.slice(0, 2));
  assert.equal(harness.state.markers.layers.size, 2);
  assert.equal([...harness.state.layers].filter(item => item.kind === 'line').length, 1);
});

test('drag, popup delete and right-click report stable native waypoint indices', () => {
  const harness = setup();
  render(harness);
  const marker = [...harness.state.markers.layers][1];
  marker.position = {lat: 34, lng: 124};
  marker.handlers.dragend();
  marker.popup.click();
  marker.handlers.contextmenu();
  assert.deepEqual(harness.state.nativeCalls.slice(-3), [
    ['onMove', 1, 34, 124], ['onDelete', 1], ['onDelete', 1]
  ]);
});

test('point mode reports selection and moving without a route polyline', () => {
  const harness = setup(false);
  render(harness, POINTS.slice(0, 1));
  assert.equal(harness.state.lines.length, 0);
  harness.map.handlers.click({latlng: {lat: 35, lng: 125}});
  assert.deepEqual(harness.state.nativeCalls.at(-1), ['onPick', 35, 125]);
});

test('empty and cleared routes render with no stale markers or lines', () => {
  const harness = setup();
  render(harness, []);
  render(harness);
  render(harness, []);
  assert.equal(harness.state.markers.layers.size, 0);
  assert.equal([...harness.state.layers].some(item => item.kind === 'line'), false);
});
