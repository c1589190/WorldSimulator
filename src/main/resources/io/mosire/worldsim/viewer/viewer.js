'use strict';

const $ = (id) => document.getElementById(id);
const canvas = $('map');
const context = canvas.getContext('2d', { alpha: false });
const nodes = $('nodes');
const queryInput = $('province-query');
const railwayToggle = $('railways');
const hubToggle = $('hubs');
const terrainNames = {
  plains: '平原', forest: '森林', hills: '丘陵', mountain: '山地', desert: '沙漠',
  urban: '城市', marsh: '沼泽', jungle: '丛林', unknown: '未知', ocean: '海洋', lakes: '湖泊'
};
const kindNames = { land: '陆地', sea: '海域', lake: '湖泊' };
const number = new Intl.NumberFormat('zh-CN');
const timeline = new Map();
const tileLimit = 96;

let view;
let mapRequest;
let nodeListRequest;
let selectionRequest;
let nextOffset = 0;
let layer = 'terrain';
let selection;
let drag;
let drawingScheduled = false;
let width = 1;
let height = 1;
let density = 1;
let scale = 1;
let left = 0;
let top = 0;

function url(path, parameters) {
  const address = new URL(path, location.origin);
  for (const [key, value] of Object.entries(parameters)) {
    if (value != null) address.searchParams.set(key, value);
  }
  return address.href;
}

async function json(path, parameters, signal) {
  const response = await fetch(url(path, parameters), { signal });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new Error(error.message || '请求失败 · HTTP ' + response.status);
  }
  return response.json();
}

function version(target) {
  return { node: target.info.node, hash: target.info.stateHash };
}

function status(message = '') {
  $('map-status').textContent = message;
  $('map-status').hidden = !message;
}

function searchStatus(message = '', error = false) {
  $('search-status').textContent = message;
  $('search-status').hidden = !message;
  $('search-status').classList.toggle('error', error);
}

async function loadNodes(reset = false) {
  nodeListRequest?.abort();
  const request = new AbortController();
  nodeListRequest = request;
  $('more-nodes').disabled = true;
  try {
    const page = await json('/api/nodes', { offset: reset ? 0 : nextOffset }, request.signal);
    if (request.signal.aborted) return;
    if (reset) timeline.clear();
    for (const row of page.nodes) timeline.set(row.node, row);
    nextOffset = page.offset + page.nodes.length;
    $('more-nodes').hidden = nextOffset >= page.total;
    updateNodes();
  } catch (error) {
    if (!request.signal.aborted) status('节点列表读取失败 · ' + error.message);
  } finally {
    if (!request.signal.aborted) $('more-nodes').disabled = false;
  }
}

function updateNodes() {
  const labels = [...timeline.keys()];
  const current = view?.info.node;
  if (current && !timeline.has(current)) labels.push(current);
  nodes.replaceChildren(...labels.map((label) => new Option(label, label)));
  nodes.disabled = labels.length === 0;
  if (current) nodes.value = current;
}

async function loadMap(node) {
  mapRequest?.abort();
  selectionRequest?.abort();
  const request = new AbortController();
  mapRequest = request;
  status('正在加载地图…');
  try {
    const info = await json('/api/map', { node }, request.signal);
    if (request.signal.aborted) return null;
    if (view) {
      view.requests.abort();
      for (const entry of [...view.overview.values(), ...view.tiles.values()]) discardImage(entry);
    }
    view = { info, overview: new Map(), tiles: new Map(), requests: new AbortController() };
    selectProvince(null);
    searchStatus();
    $('map-name').textContent = info.mapName;
    $('map-counts').textContent = number.format(info.provinces) + ' 省  ·  '
      + number.format(info.states) + ' 州  ·  ' + number.format(info.regions) + ' 战略区域';
    $('map-counts').hidden = false;
    updateNodes();
    fitMap();
    loadLogistics();
    return view;
  } catch (error) {
    if (!request.signal.aborted) {
      updateNodes();
      status('地图读取失败 · ' + error.message);
    }
    return null;
  } finally {
    if (mapRequest === request) mapRequest = null;
  }
}

function scheduleDraw() {
  if (drawingScheduled) return;
  drawingScheduled = true;
  requestAnimationFrame(draw);
}

function fitScale() {
  const margin = Math.min(width, height) * .04;
  return Math.min((width - 2 * margin) / view.info.width, (height - 2 * margin) / view.info.height);
}

function maxScale() {
  return Math.max(fitScale(), view.info.overviewScale * 2 ** view.info.maxTileLevel * 1.5);
}

function between(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function containMap() {
  const margin = Math.min(90, width / 4, height / 4);
  left = between(left, margin - view.info.width * scale, width - margin);
  top = between(top, margin - view.info.height * scale, height - margin);
  scheduleDraw();
}

function fitMap() {
  if (!view) return;
  scale = fitScale();
  left = (width - view.info.width * scale) / 2;
  top = (height - view.info.height * scale) / 2;
  scheduleDraw();
}

function zoom(factor, x = width / 2, y = height / 2) {
  if (!view) return;
  const next = between(scale * factor, fitScale(), maxScale());
  left = x - (x - left) * next / scale;
  top = y - (y - top) * next / scale;
  scale = next;
  containMap();
}

function resize() {
  const bounds = canvas.getBoundingClientRect();
  const previousWidth = width;
  const previousHeight = height;
  const fitted = view && scale <= fitScale() * 1.001;
  width = Math.max(1, bounds.width);
  height = Math.max(1, bounds.height);
  density = Math.min(2, window.devicePixelRatio || 1);
  canvas.width = Math.round(width * density);
  canvas.height = Math.round(height * density);
  if (view && fitted) {
    fitMap();
  } else if (view) {
    left += (width - previousWidth) / 2;
    top += (height - previousHeight) / 2;
    zoom(1);
  }
  scheduleDraw();
}

function image(cache, key, source) {
  const entry = cache.get(key);
  if (entry) {
    cache.delete(key);
    cache.set(key, entry);
    return entry;
  }
  const target = view;
  const picture = new Image();
  const next = { picture, state: 'loading' };
  picture.onload = () => {
    next.state = 'ready';
    if (view === target) scheduleDraw();
  };
  picture.onerror = () => {
    next.state = 'failed';
    if (view === target) scheduleDraw();
  };
  cache.set(key, next);
  picture.src = source;
  return next;
}

function discardImage(entry) {
  if (entry.state !== 'loading') return;
  entry.picture.onload = null;
  entry.picture.onerror = null;
  entry.picture.removeAttribute('src');
}

function draw() {
  drawingScheduled = false;
  context.setTransform(density, 0, 0, density, 0, 0);
  context.fillStyle = '#122630';
  context.fillRect(0, 0, width, height);
  if (!view) return;

  const { info, overview } = view;
  const base = image(overview, layer, url('/api/overview', { ...version(view), layer }));
  if (base.state !== 'ready') {
    if (!mapRequest) status(base.state === 'failed' ? '底图加载失败，可刷新重试。' : '正在绘制底图…');
    return;
  }
  if (!mapRequest && $('map-status').textContent === '正在绘制底图…') status();
  if (!mapRequest && $('map-status').textContent === '正在加载地图…') status();

  context.save();
  context.translate(left, top);
  context.scale(scale, scale);
  context.beginPath();
  context.rect(0, 0, info.width, info.height);
  context.clip();
  context.drawImage(base.picture, 0, 0,
    base.picture.naturalWidth / info.overviewScale, base.picture.naturalHeight / info.overviewScale);
  drawTiles();
  drawLogistics();
  drawSelection();
  context.restore();
}

function drawTiles() {
  const { info, tiles } = view;
  let level = Math.min(info.maxTileLevel, Math.floor(Math.log2(scale * density / info.overviewScale)));
  while (level > 0) {
    const tilePixels = info.tileSize * scale / (info.overviewScale * 2 ** level);
    const visibleColumns = Math.ceil(width / tilePixels) + 1;
    const visibleRows = Math.ceil(height / tilePixels) + 1;
    if (visibleColumns * visibleRows <= tileLimit) break;
    level--;
  }
  if (level <= 0) return;
  const step = info.tileSize / (info.overviewScale * 2 ** level);
  const firstX = Math.max(0, Math.floor(-left / scale / step));
  const firstY = Math.max(0, Math.floor(-top / scale / step));
  const lastX = Math.min(Math.ceil(info.width / step), Math.ceil((width - left) / scale / step));
  const lastY = Math.min(Math.ceil(info.height / step), Math.ceil((height - top) / scale / step));
  for (let y = firstY; y < lastY; y++) {
    for (let x = firstX; x < lastX; x++) {
      const key = [layer, level, x, y].join('/');
      const tile = image(tiles, key, url('/api/tile', { ...version(view), layer, level, x, y }));
      if (tile.state === 'ready') context.drawImage(tile.picture, x * step, y * step, step, step);
    }
  }
  while (tiles.size > tileLimit) {
    const key = tiles.keys().next().value;
    discardImage(tiles.get(key));
    tiles.delete(key);
  }
}

async function loadLogistics() {
  const target = view;
  if (!target || target.logistics || target.loadingLogistics || (!railwayToggle.checked && !hubToggle.checked)) return;
  target.loadingLogistics = true;
  try {
    const data = await json('/api/logistics', version(target), target.requests.signal);
    if (view !== target) return;
    const railPath = new Path2D();
    for (const railway of data.railways) {
      railPath.moveTo(railway.points[0].x, railway.points[0].y);
      for (const point of railway.points.slice(1)) railPath.lineTo(point.x, point.y);
    }
    target.logistics = { railPath, hubs: data.hubs };
    scheduleDraw();
  } catch (error) {
    if (!target.requests.signal.aborted) status('后勤图层加载失败 · ' + error.message);
  } finally {
    target.loadingLogistics = false;
  }
}

function drawLogistics() {
  if (!view.logistics) return;
  if (railwayToggle.checked) {
    context.strokeStyle = '#754c35';
    context.lineWidth = 2.4 / scale;
    context.stroke(view.logistics.railPath);
    context.strokeStyle = '#f0a067';
    context.lineWidth = 1.1 / scale;
    context.stroke(view.logistics.railPath);
  }
  if (hubToggle.checked) {
    const radius = 3.2 / scale;
    context.beginPath();
    for (const { point } of view.logistics.hubs) {
      context.moveTo(point.x + radius, point.y);
      context.arc(point.x, point.y, radius, 0, 2 * Math.PI);
    }
    context.fillStyle = '#32605d';
    context.fill();
    context.strokeStyle = '#d6fff1';
    context.lineWidth = 1.2 / scale;
    context.stroke();
  }
}

function drawSelection() {
  if (!selection) return;
  context.fillStyle = '#f9ce7870';
  context.fill(selection.path, 'evenodd');
  context.strokeStyle = '#33261c';
  context.lineWidth = 3.8 / scale;
  context.stroke(selection.path);
  context.strokeStyle = '#ffe0a1';
  context.lineWidth = 1.8 / scale;
  context.stroke(selection.path);
}

function selectProvince(result) {
  $('province').hidden = !result;
  $('empty-province').hidden = Boolean(result);
  selection = null;
  if (result) {
    const province = result.entity;
    const path = new Path2D();
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const part of province.shape.parts) {
      for (const ring of [part.outer, ...part.holes]) {
        path.moveTo(ring.points[0].x, ring.points[0].y);
        for (let index = 0; index < ring.points.length; index++) {
          const point = ring.points[index];
          if (index > 0) path.lineTo(point.x, point.y);
          minX = Math.min(minX, point.x);
          minY = Math.min(minY, point.y);
          maxX = Math.max(maxX, point.x);
          maxY = Math.max(maxY, point.y);
        }
        path.closePath();
      }
    }
    selection = { path, width: maxX - minX, height: maxY - minY, point: province.displayPoint };
    $('province-name').textContent = result.displayName;
    $('province-kind').textContent = kindNames[province.kind] || province.kind;
    $('province-id').textContent = province.id;
    $('province-terrain').textContent = terrainNames[province.terrain] || province.terrain;
    $('province-state').textContent = result.stateName || province.stateId || '—';
    $('province-region').textContent = result.regionName || province.regionId || '—';
    $('province-address').value = result.address;
    showEvidence(result);
  }
  scheduleDraw();
}

function showEvidence(result) {
  const entries = (result.fieldEvidence || []).map((entry) => {
    const field = { kind: '海陆类型', shape: '省界' }[entry.field] || entry.field;
    const state = { inferred: '推断', repaired: '修复' }[entry.status] || entry.status;
    const confidence = entry.confidence == null ? '置信度未知' : '置信度 ' + entry.confidence;
    return [field, state, entry.source, confidence].filter(Boolean).join(' · ');
  });
  if (result.provenanceWarning) entries.push(result.provenanceWarning);
  $('evidence').hidden = entries.length === 0;
  $('evidence-list').replaceChildren(...entries.map((text) => {
    const item = document.createElement('li');
    item.textContent = text;
    return item;
  }));
}

async function findProvince(path, parameters, center = false) {
  const target = view;
  if (!target || mapRequest) return;
  selectionRequest?.abort();
  const request = new AbortController();
  selectionRequest = request;
  searchStatus();
  try {
    const result = await json(path, { ...version(target), ...parameters }, request.signal);
    if (view !== target || request.signal.aborted) return;
    selectProvince(result);
    if (!result) searchStatus('此处没有省份。');
    if (result && center) {
      const fit = Math.min(width / selection.width, height / selection.height) * .55;
      scale = between(fit, fitScale(), maxScale());
      left = width / 2 - selection.point.x * scale;
      top = height / 2 - selection.point.y * scale;
      containMap();
    }
  } catch (error) {
    if (!request.signal.aborted) searchStatus(error.message, true);
  }
}

function showLegend() {
  const items = [];
  if (layer === 'terrain') {
    for (const [key, label] of Object.entries(terrainNames)) {
      const entry = document.createElement('li');
      const swatch = document.createElement('span');
      swatch.className = 'swatch ' + ({ ocean: 'sea', lakes: 'lake' }[key] || key);
      swatch.setAttribute('aria-hidden', 'true');
      entry.append(swatch, label);
      items.push(entry);
    }
  } else {
    const note = document.createElement('li');
    note.className = 'legend-note';
    note.textContent = layer === 'state' ? '陆地颜色按省份所属的州分组。' : '颜色按省份所属的战略区域分组。';
    items.push(note);
  }
  $('legend').replaceChildren(...items);
}

function cursor(event) {
  const bounds = canvas.getBoundingClientRect();
  return { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
}

canvas.addEventListener('pointerdown', (event) => {
  if (!view || !event.isPrimary || event.button !== 0) return;
  drag = { ...cursor(event), left, top, moved: false, pointerId: event.pointerId };
  canvas.setPointerCapture(event.pointerId);
  canvas.focus({ preventScroll: true });
});
canvas.addEventListener('pointermove', (event) => {
  if (!drag || event.pointerId !== drag.pointerId) return;
  const point = cursor(event);
  const dx = point.x - drag.x;
  const dy = point.y - drag.y;
  if (!drag.moved && Math.hypot(dx, dy) < 4) return;
  drag.moved = true;
  canvas.classList.add('dragging');
  left = drag.left + dx;
  top = drag.top + dy;
  containMap();
});
canvas.addEventListener('pointerup', (event) => {
  if (!drag || event.pointerId !== drag.pointerId) return;
  if (!drag.moved) {
    const point = cursor(event);
    findProvince('/api/pick', { x: (point.x - left) / scale, y: (point.y - top) / scale });
  }
  drag = null;
  canvas.classList.remove('dragging');
});
canvas.addEventListener('lostpointercapture', () => {
  drag = null;
  canvas.classList.remove('dragging');
});
canvas.addEventListener('wheel', (event) => {
  event.preventDefault();
  const point = cursor(event);
  const multiplier = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? height : 1;
  zoom(Math.exp(between(-.0014 * event.deltaY * multiplier, -.7, .7)), point.x, point.y);
}, { passive: false });
canvas.addEventListener('keydown', (event) => {
  if (!view) return;
  switch (event.key) {
    case '+': case '=': zoom(1.5); break;
    case '-': zoom(1 / 1.5); break;
    case 'Home': fitMap(); break;
    case 'ArrowLeft': left += 55; containMap(); break;
    case 'ArrowRight': left -= 55; containMap(); break;
    case 'ArrowUp': top += 55; containMap(); break;
    case 'ArrowDown': top -= 55; containMap(); break;
    default: return;
  }
  event.preventDefault();
});

$('search').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!view || mapRequest) return;
  let address = queryInput.value.trim();
  if (!address) return;
  if (address.includes('/')) {
    const parts = address.split('/');
    if (parts.length !== 4 || parts[1] !== 'map' || parts[2] !== 'province' || !parts[3]) {
      searchStatus('请输入省 ID 或完整的省份节点地址。', true);
      return;
    }
    if (parts[0] !== view.info.node && !await loadMap(parts[0])) return;
  } else {
    address = view.info.node + '/map/province/' + address;
  }
  findProvince('/api/province', { address }, true);
});

$('copy').addEventListener('click', async () => {
  const address = $('province-address');
  try {
    await navigator.clipboard.writeText(address.value);
    searchStatus('已复制地址。');
  } catch {
    address.select();
    searchStatus('地址已选中，可手动复制。');
  }
});
$('clear-province').addEventListener('click', () => {
  selectionRequest?.abort();
  selectProvince(null);
  searchStatus();
});
document.querySelectorAll('input[name="layer"]').forEach((input) => {
  input.addEventListener('change', () => {
    layer = input.value;
    status();
    showLegend();
    scheduleDraw();
  });
});
for (const toggle of [railwayToggle, hubToggle]) {
  toggle.addEventListener('change', () => {
    loadLogistics();
    scheduleDraw();
  });
}
$('zoom-in').addEventListener('click', () => zoom(1.5));
$('zoom-out').addEventListener('click', () => zoom(1 / 1.5));
$('fit').addEventListener('click', fitMap);
$('reload').addEventListener('click', () => {
  loadNodes(true);
  loadMap(view?.info.node);
});
$('more-nodes').addEventListener('click', () => loadNodes());
nodes.addEventListener('change', () => loadMap(nodes.value));

new ResizeObserver(resize).observe($('viewport'));
resize();
showLegend();
loadNodes(true);
loadMap();
