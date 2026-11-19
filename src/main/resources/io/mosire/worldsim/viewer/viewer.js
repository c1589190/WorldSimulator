import {
  COUNTER_HIDE_ZOOM, drawUnitCounters, layoutUnitCounters, renderMilitary, showFormation, STACK_ZOOM
} from './military.js';

const $ = (id) => document.getElementById(id);
const canvas = $('map');
const context = canvas.getContext('2d', { alpha: false });
const nodes = $('nodes');
const queryInput = $('province-query');
const railwayToggle = $('railways');
const hubToggle = $('hubs');
const borderToggle = $('geo-borders');
const unitToggle = $('units');
const unitZoomNote = $('unit-zoom-note');
const militaryPanel = $('military-panel');
const formationDialog = $('formation-dialog');
const noUnitSelection = new Set();
const terrainNames = {
  plains: '平原', forest: '森林', hills: '丘陵', mountain: '山地', desert: '沙漠',
  urban: '城市', marsh: '沼泽', jungle: '丛林', unknown: '未知', ocean: '海洋', lakes: '湖泊'
};
const kindNames = { land: '陆地', sea: '海域', lake: '湖泊' };
const entityNames = { province: '省份', state: '州', region: '战略区域', country: '国家',
  unit: '单位', orbatDocument: '编制文档', orbatNode: '编制节点' };
const areaLayers = new Set(['state', 'region']);
const number = new Intl.NumberFormat('zh-CN');
const timeline = new Map();
const tileLimit = 96;
const renderVersion = 2;
const minimumMapZoom = .8;

let view;
let mapRequest;
let nodeListRequest;
let selectionRequest;
let armyRequest;
let nextOffset = 0;
let layer = 'terrain';
let selection;
let armyState;
let unitHits = [];
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
  armyRequest?.abort();
  if (armyState) setArmyState(null);
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
    $('geo-border-option').hidden = !info.geographicReference;
    $('geo-border-source').hidden = true;
    queryInput.placeholder = info.geographicReference ? '地名、Key、地址或经度,纬度' : '名称、ID、Key 或节点地址';
    const location = new URL(window.location.href);
    if (node || location.searchParams.has('node')) {
      location.searchParams.set('node', info.node);
      window.history.replaceState(null, '', location);
    }
    selectProvince(null);
    clearSearchResults();
    searchStatus();
    $('map-name').textContent = info.mapName;
    $('map-counts').textContent = number.format(info.provinces) + ' 省  ·  '
      + number.format(info.states) + ' 州  ·  ' + number.format(info.regions) + ' 战略区域';
    $('map-counts').hidden = false;
    updateNodes();
    fitMap();
    loadLogistics();
    loadGeographicBorders();
    loadTerritoryGaps();
    loadUnits();
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
  const next = between(scale * factor, fitScale() * minimumMapZoom, maxScale());
  if (next === scale) return;
  left = x - (x - left) * next / scale;
  top = y - (y - top) * next / scale;
  scale = next;
  containMap();
}

function centerMap(point, minimumZoom) {
  scale = Math.max(scale, Math.min(maxScale(), fitScale() * minimumZoom));
  left = width / 2 - point.x * scale;
  top = height / 2 - point.y * scale;
  containMap();
}

function resize() {
  const bounds = canvas.getBoundingClientRect();
  const nextWidth = Math.max(1, bounds.width);
  const nextHeight = Math.max(1, bounds.height);
  const nextDensity = Math.min(2, window.devicePixelRatio || 1);
  if (width === nextWidth && height === nextHeight && density === nextDensity
      && canvas.width === Math.round(nextWidth * nextDensity)
      && canvas.height === Math.round(nextHeight * nextDensity)) return;
  const previousZoom = view ? scale / fitScale() : 1;
  const centerX = (width / 2 - left) / scale;
  const centerY = (height / 2 - top) / scale;
  width = nextWidth;
  height = nextHeight;
  density = nextDensity;
  canvas.width = Math.round(width * density);
  canvas.height = Math.round(height * density);
  if (!view) {
    scheduleDraw();
  } else if (Math.abs(previousZoom - 1) <= .001) {
    fitMap();
  } else {
    scale = Math.min(fitScale() * previousZoom, maxScale());
    left = width / 2 - centerX * scale;
    top = height / 2 - centerY * scale;
    containMap();
  }
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
  unitHits = [];
  const relativeZoom = view ? scale / fitScale() : 1;
  const belowCounterZoom = relativeZoom < COUNTER_HIDE_ZOOM;
  unitZoomNote.toggleAttribute('hidden', !view?.info.units || !unitToggle.checked || !belowCounterZoom);
  if (belowCounterZoom || !unitToggle.checked) updateCounterHover();
  context.setTransform(density, 0, 0, density, 0, 0);
  context.fillStyle = '#122630';
  context.fillRect(0, 0, width, height);
  if (!view) return;

  const { info, overview } = view;
  const base = image(overview, layer, url('/api/overview', { ...version(view), layer, renderVersion }));
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
  drawGeographicBorders();
  drawSelection();
  context.restore();
  if (!belowCounterZoom) drawUnits(relativeZoom);
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
      const tile = image(tiles, key, url('/api/tile', { ...version(view), layer, level, x, y, renderVersion }));
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

async function loadGeographicBorders() {
  const target = view;
  const source = $('geo-border-source');
  source.hidden = !(target?.info.geographicReference && borderToggle.checked && target.geographicBorders);
  if (!target?.info.geographicReference || !borderToggle.checked || target.geographicBorders || target.loadingBorders) return;
  target.loadingBorders = true;
  try {
    const { borders } = await json('/api/geography/borders', version(target), target.requests.signal);
    if (view !== target) return;
    const paths = new Path2D();
    for (const border of borders) {
      for (const line of border.paths) {
        paths.moveTo(line[0].x, line[0].y);
        for (const point of line.slice(1)) paths.lineTo(point.x, point.y);
      }
    }
    target.geographicBorders = paths;
    source.textContent = borders.length ? borders.map(border => border.name).join('、') + ' · ' + borders[0].date : '当前图幅暂无参考边界';
    if (borders.length && /^https?:\/\//.test(borders[0].source)) {
      const link = textElement('a', '来源');
      link.href = borders[0].source;
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      source.append(' · ', link);
    }
    source.hidden = !borderToggle.checked;
    scheduleDraw();
  } catch (error) {
    if (!target.requests.signal.aborted) status('史料边界读取失败 · ' + error.message);
  } finally {
    target.loadingBorders = false;
  }
}

function drawGeographicBorders() {
  if (!borderToggle.checked || !view.geographicBorders) return;
  context.save();
  context.setLineDash([6 / scale, 3 / scale]);
  context.strokeStyle = '#472e20';
  context.lineWidth = 3.6 / scale;
  context.stroke(view.geographicBorders);
  context.strokeStyle = '#f8d990';
  context.lineWidth = 1.65 / scale;
  context.stroke(view.geographicBorders);
  context.restore();
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

async function loadUnits() {
  const target = view;
  if (!target?.info.units || target.units || target.loadingUnits || !unitToggle.checked) return;
  target.loadingUnits = true;
  try {
    const units = await json('/api/units', version(target), target.requests.signal);
    if (view !== target) return;
    target.units = units;
    scheduleDraw();
  } catch (error) {
    if (!target.requests.signal.aborted) status('单位图层加载失败 · ' + error.message);
  } finally {
    target.loadingUnits = false;
  }
}

function drawUnits(relativeZoom) {
  if (!unitToggle.checked || !view.units?.length) return;
  const camera = [left, top, width, height, scale];
  if (!view.counterLayout || camera.some((value, index) => value !== view.counterLayout.camera[index])) {
    view.counterLayout = { camera, hits: layoutUnitCounters(view.units,
      { left, top, width, height, scale, zoom: relativeZoom }) };
  }
  unitHits = view.counterLayout.hits;
  drawUnitCounters(context, unitHits, armyState?.selected || noUnitSelection);
}

function selectShape(shape, point) {
  const path = new Path2D();
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const part of shape.parts) {
    for (const ring of [part.outer, ...part.holes]) {
      path.moveTo(ring.points[0].x, ring.points[0].y);
      for (let index = 0; index < ring.points.length; index++) {
        const vertex = ring.points[index];
        if (index > 0) path.lineTo(vertex.x, vertex.y);
        minX = Math.min(minX, vertex.x);
        minY = Math.min(minY, vertex.y);
        maxX = Math.max(maxX, vertex.x);
        maxY = Math.max(maxY, vertex.y);
      }
      path.closePath();
    }
  }
  selection = { path, width: maxX - minX, height: maxY - minY,
    point: point || { x: (minX + maxX) / 2, y: (minY + maxY) / 2 } };
}

function fitSelection() {
  const fit = Math.min(width / selection.width, height / selection.height) * .55;
  scale = between(fit, fitScale(), maxScale());
  left = width / 2 - selection.point.x * scale;
  top = height / 2 - selection.point.y * scale;
  containMap();
}

function selectProvince(result) {
  $('entity-detail').hidden = true;
  $('province').hidden = !result;
  $('empty-province').hidden = Boolean(result);
  selection = null;
  if (result) {
    const province = result.entity;
    selectShape(province.shape, province.displayPoint);
    $('province-name').textContent = result.displayName;
    $('province-kind').textContent = kindNames[province.kind] || province.kind;
    $('province-id').textContent = province.id;
    $('province-terrain').textContent = terrainNames[province.terrain] || province.terrain;
    $('province-geography-row').hidden = !result.geographicPosition;
    if (result.geographicPosition) {
      const longitude = result.geographicPosition.longitude.toFixed(3);
      const latitude = result.geographicPosition.latitude.toFixed(3);
      const link = $('province-geography');
      link.textContent = longitude + ', ' + latitude;
      link.href = `https://www.openstreetmap.org/?mlat=${latitude}&mlon=${longitude}#map=10/${latitude}/${longitude}`;
    }
    $('province-state').textContent = result.stateName || province.stateId || '—';
    $('province-region').textContent = result.regionName || province.regionId || '—';
    $('province-owner-row').hidden = !view.info.countries;
    $('province-controller-row').hidden = !view.info.countries;
    $('province-owner').textContent = result.ownerName || '未知';
    $('province-controller').textContent = result.controllerName || '未知';
    const units = result.units || [];
    $('province-unit-section').hidden = units.length === 0;
    $('province-units').replaceChildren(...units.map((unit) => listLink(unit.name, unit.address, false)));
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
  if (result.territory?.owner) entries.push('归属 · ' + evidenceText(result.territory.owner.evidence));
  if (result.territory?.controller) entries.push('控制 · ' + evidenceText(result.territory.controller.evidence));
  $('evidence').hidden = entries.length === 0;
  $('evidence-list').replaceChildren(...entries.map((text) => {
    const item = document.createElement('li');
    item.textContent = text;
    return item;
  }));
}

function evidenceText(evidence) {
  if (!evidence) return '来源未知';
  const description = { known: '有来源', inferred: '推断', estimated: '估计', unknown: '未知',
    'legacy-ambiguous': '旧数据待核对' }[evidence.status] || evidence.status;
  return [description, evidence.source, evidence.date,
    evidence.confidence == null ? null : '置信度 ' + evidence.confidence].filter(Boolean).join(' · ');
}

function linkButton(label, address, center = true) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'entity-link';
  button.textContent = label;
  button.addEventListener('click', () => openEntity(address, center));
  return button;
}

function listLink(label, address, center = true) {
  const item = document.createElement('li');
  item.append(linkButton(label, address, center));
  return item;
}

function clearSearchResults() {
  $('search-results').replaceChildren();
  $('search-results').hidden = true;
}

function textElement(tag, text, className) {
  const element = document.createElement(tag);
  element.textContent = text;
  if (className) element.className = className;
  return element;
}

function evidenceLine(evidence) {
  if (!evidence) return textElement('p', '来源未知', 'entity-source');
  const line = textElement('p', evidenceText({ ...evidence, source: null }), 'entity-source');
  const source = evidence.source;
  if (!source) return line;
  const url = /^https?:\/\/[^\s]+/.exec(source)?.[0];
  if (url) {
    const link = textElement('a', '资料来源');
    link.href = url;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.title = source;
    line.append(' · ', link);
  } else {
    line.append(' · ', source);
  }
  return line;
}

function setArmyState(state) {
  if (formationDialog.open) formationDialog.close();
  const changed = Boolean(armyState) !== Boolean(state);
  const content = $('military-content');
  armyState = state;
  militaryPanel.hidden = !state;
  militaryPanel.parentElement.classList.toggle('military-open', Boolean(state));
  if (state) {
    const address = state.address || state.result?.address;
    state.selected = new Set(address ? [address] : state.roster?.units.map(unit => unit.address));
    renderMilitary(content, state, {
      evidence: evidenceLine,
      openFormation: () => showFormation(formationDialog, state, evidenceLine),
      locate: (point) => {
        unitToggle.checked = true;
        loadUnits();
        const minimum = state.result ? STACK_ZOOM + .65 : Math.max(STACK_ZOOM + .65, scale / fitScale() * 1.4);
        centerMap(point, minimum);
      },
      selectUnit: (address) => openEntity(address, false, { roster: state.roster }),
      selectProvince: (address) => openEntity(address, false),
      selectCountry: (address) => openEntity(address, false),
      backToRoster: () => {
        armyRequest?.abort();
        setArmyState({ roster: state.roster });
      }
    });
    content.scrollTop = 0;
  } else {
    content.replaceChildren();
  }
  if (changed) resize();
  scheduleDraw();
}

function selectCounter(counter) {
  selectionRequest?.abort();
  if (counter.units.length === 1) {
    const address = counter.units[0].address;
    const roster = armyState?.roster?.units.some(unit => unit.address === address) ? armyState.roster : undefined;
    openEntity(address, false, { roster });
    return;
  }
  armyRequest?.abort();
  const point = counter.units.reduce((center, unit) => ({
    x: center.x + unit.point.x / counter.units.length,
    y: center.y + unit.point.y / counter.units.length
  }), { x: 0, y: 0 });
  setArmyState({ roster: { units: counter.units, point } });
}

async function openEntity(address, center = true, { roster } = {}) {
  if (!view || mapRequest) return;
  const parts = address.split('/');
  if (parts.length < 2 || parts.some((part) => !part)) {
    searchStatus('请输入完整的实体节点地址。', true);
    return;
  }
  if (parts[0] !== view.info.node && !await loadMap(parts[0])) return;
  clearSearchResults();
  if (parts[1] === 'map' && parts[2] === 'province') {
    await findMapEntity('/api/province', { address }, center);
    return;
  }
  const target = view;
  const request = new AbortController();
  const military = parts[1] === 'orbat';
  if (military) {
    armyRequest?.abort();
    armyRequest = request;
    setArmyState({ address, roster, message: '正在读取编制…' });
  } else {
    selectionRequest?.abort();
    selectionRequest = request;
  }
  searchStatus();
  const lookup = (entityAddress) => json('/api/entity', { ...version(target), address: entityAddress }, request.signal);
  try {
    const result = await lookup(address);
    if (result.kind === 'unit') {
      const [actual, ideal] = await Promise.all([result.actualAddress, result.idealAddress]
        .map(async (ref) => ref ? (await lookup(ref)).entity : null));
      if (view !== target || request.signal.aborted) return;
      setArmyState({ result, actual, ideal, roster });
      if (center && result.point) centerMap(result.point, STACK_ZOOM + .65);
      return;
    }
    if (view !== target || request.signal.aborted) return;
    if (result.kind === 'orbatDocument' || result.kind === 'orbatNode') {
      const tree = result.kind === 'orbatDocument' ? result.entity.root : result.entity;
      const state = { result, [result.role]: tree };
      setArmyState(state);
      showFormation(formationDialog, state, evidenceLine);
      return;
    }
    showEntity(result, center);
  } catch (error) {
    if (request.signal.aborted) return;
    if (military) setArmyState({ address, roster, message: '读取编制失败 · ' + error.message });
    else searchStatus(error.message, true);
  }
}

function showEntity(result, center) {
  const content = [];
  if (result.kind === 'country') {
    const control = result.controlledProvinces > 0
      ? '已记录控制 ' + number.format(result.controlledProvinces) + ' 省' : '控制范围暂无记录';
    content.push(textElement('p', '已记录归属 ' + number.format(result.ownedProvinces) + ' 省 · ' + control));
    content.push(evidenceLine(result.entity.evidence));
    if (result.capitalAddress) content.push(linkButton('定位首都', result.capitalAddress));
  } else if (result.entity.provinceIds) {
    const provinceIds = result.entity.provinceIds;
    content.push(textElement('p', '覆盖 ' + number.format(provinceIds.length) + ' 省'));
    if (provinceIds.length) {
      content.push(linkButton('查看成员省 ' + provinceIds[0], view.info.node + '/map/province/' + provinceIds[0]));
    }
  }
  selectProvince(null);
  $('empty-province').hidden = true;
  $('entity-detail').hidden = false;
  $('entity-kind').textContent = entityNames[result.kind] || result.kind;
  $('entity-name').textContent = result.displayName;
  $('entity-key').hidden = !areaLayers.has(result.kind);
  $('entity-key').textContent = 'Key ' + result.stableId;
  $('entity-address').textContent = result.address;
  $('entity-content').replaceChildren(...content);
  if (result.shape) {
    selectShape(result.shape);
    if (center) fitSelection();
  } else if (center && result.point) centerMap(result.point, 4);
}

async function searchEntities(query) {
  const target = view;
  selectionRequest?.abort();
  const request = new AbortController();
  selectionRequest = request;
  clearSearchResults();
  searchStatus();
  try {
    const page = await json('/api/search', { ...version(target), query, limit: 25 }, request.signal);
    if (view !== target || request.signal.aborted) return;
    if (page.total !== 1 && target.info.geographicReference) {
      const geo = await json('/api/geography', { ...version(target), name: query }, request.signal).catch(() => null);
      if (view !== target || request.signal.aborted) return;
      if (geo?.candidates.length) {
        await showGeographicResult(geo);
        return;
      }
    }
    if (page.total === 1) {
      await openEntity(page.matches[0].address);
    } else if (page.total === 0) {
      searchStatus('没有找到匹配的实体。');
    } else {
      const results = page.matches.map((match) => listLink(
        [match.displayName, entityNames[match.kind], match.context].filter(Boolean).join(' · '), match.address));
      $('search-results').replaceChildren(...results);
      $('search-results').hidden = false;
      searchStatus(page.total > results.length ? '显示前 ' + results.length + ' 项，共 ' + page.total + ' 项。' : '请选择匹配项。');
    }
  } catch (error) {
    if (!request.signal.aborted) searchStatus(error.message, true);
  }
}

async function findCoordinates(longitude, latitude) {
  const target = view;
  selectionRequest?.abort();
  const request = new AbortController();
  selectionRequest = request;
  clearSearchResults();
  searchStatus();
  try {
    const result = await json('/api/geography', { ...version(target), longitude, latitude }, request.signal);
    if (view !== target || request.signal.aborted) return;
    await showGeographicResult(result);
  } catch (error) {
    if (!request.signal.aborted) searchStatus(error.message, true);
  }
}

async function showGeographicResult(result) {
  if (result.status === 'resolved') {
    await openEntity(result.candidates[0].address);
  } else if (result.candidates.length) {
    $('search-results').replaceChildren(...result.candidates.map(match =>
      listLink(match.name + ' · 省 ' + match.provinceId, match.address)));
    $('search-results').hidden = false;
    const margin = result.point ? '（配准误差 ' + number.format(result.errorRadius) + ' 个地图坐标单位）' : '';
    searchStatus('查到 ' + result.candidates.length + ' 个候选' + margin + '，请核对后选择。');
  } else {
    searchStatus({ unavailable: '此世界尚无地理参考。', 'outside-coverage': '坐标在已配准图幅外。' }[result.status]
      || '没有找到候选省份。');
  }
}

async function findMapEntity(path, parameters, center = false) {
  const target = view;
  if (!target || mapRequest) return;
  selectionRequest?.abort();
  const request = new AbortController();
  selectionRequest = request;
  clearSearchResults();
  searchStatus();
  try {
    const result = await json(path, { ...version(target), ...parameters }, request.signal);
    if (view !== target || request.signal.aborted) return;
    if (result && result.kind !== 'province') {
      showEntity(result, center);
    } else {
      selectProvince(result);
      if (!result) searchStatus('此处没有' + (entityNames[parameters.layer] || '省份') + '。');
      else if (center) fitSelection();
    }
  } catch (error) {
    if (!request.signal.aborted) searchStatus(error.message, true);
  }
}

function showLegend() {
  const items = [];
  $('empty-province').textContent = '点击地图查看' + (entityNames[layer] || '省份') + '。';
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
    note.textContent = {
      state: '点击色块选择整州。',
      region: '点击色块选择整个战略区。',
      owner: '有归属记录的省按国家着色。',
      controller: '有控制记录的省按实际控制方着色。',
      'owner-gaps': '粉色标出待补归属的陆省。'
    }[layer];
    items.push(note);
  }
  $('legend').replaceChildren(...items);
}

async function loadTerritoryGaps(offset = 0) {
  const panel = $('territory-gaps');
  const target = view;
  panel.hidden = layer !== 'owner-gaps' || !target;
  if (panel.hidden) return;
  const summary = $('territory-gap-status');
  const list = $('territory-gap-list');
  const more = $('more-territory-gaps');
  const request = {};
  target.gapRequest = request;
  more.disabled = true;
  if (!offset) {
    list.replaceChildren();
    more.hidden = true;
    summary.textContent = '正在核对省份…';
  }
  try {
    const result = await json('/api/territory/gaps', { ...version(target), offset, limit: 8 }, target.requests.signal);
    if (view !== target || target.gapRequest !== request || layer !== 'owner-gaps') return;
    const scope = result.scope === 'reference' ? result.referenceDate + ' 史料范围' : '全图';
    summary.textContent = scope + ' · 待补 ' + number.format(result.total) + ' 省';
    list.append(...result.gaps.map(gap => listLink(
      [gap.name, '省 ' + gap.provinceId, gap.stateName, gap.expectedCountryId].filter(Boolean).join(' · '), gap.address)));
    const next = result.offset + result.gaps.length;
    more.dataset.offset = next;
    more.hidden = next >= result.total;
  } catch (error) {
    if (view === target && target.gapRequest === request && layer === 'owner-gaps') {
      summary.textContent = '归属调试读取失败 · ' + error.message;
    }
  } finally {
    if (view === target && target.gapRequest === request) more.disabled = false;
  }
}

function cursor(event) {
  const bounds = canvas.getBoundingClientRect();
  return { x: event.clientX - bounds.left, y: event.clientY - bounds.top };
}

function counterAt(point) {
  if (!unitToggle.checked || !view || scale / fitScale() < COUNTER_HIDE_ZOOM) return undefined;
  return unitHits.findLast((hit) => Math.abs(hit.x - point.x) <= hit.width / 2
    && Math.abs(hit.y - point.y) <= hit.height / 2);
}

function updateCounterHover(counter) {
  canvas.classList.toggle('over-counter', Boolean(counter));
  const title = counter?.units.length > 1 ? number.format(counter.units.length) + ' 个部署单位'
    : counter?.units[0]?.name || '';
  if (canvas.title !== title) canvas.title = title;
}

canvas.addEventListener('pointerdown', (event) => {
  if (!view || !event.isPrimary || event.button !== 0) return;
  drag = { ...cursor(event), left, top, moved: false, pointerId: event.pointerId };
  canvas.setPointerCapture(event.pointerId);
  canvas.focus({ preventScroll: true });
});
canvas.addEventListener('pointermove', (event) => {
  const point = cursor(event);
  if (!drag) {
    updateCounterHover(counterAt(point));
    return;
  }
  if (event.pointerId !== drag.pointerId) return;
  const dx = point.x - drag.x;
  const dy = point.y - drag.y;
  if (!drag.moved && Math.hypot(dx, dy) < 4) return;
  drag.moved = true;
  canvas.classList.add('dragging');
  left = drag.left + dx;
  top = drag.top + dy;
  containMap();
});
canvas.addEventListener('pointerleave', () => updateCounterHover());
canvas.addEventListener('pointerup', (event) => {
  if (!drag || event.pointerId !== drag.pointerId) return;
  if (!drag.moved) {
    const point = cursor(event);
    const counter = counterAt(point);
    if (counter) selectCounter(counter);
    else {
      const location = { x: (point.x - left) / scale, y: (point.y - top) / scale };
      closeMilitary();
      findMapEntity('/api/pick', { ...location, layer });
    }
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
  const value = queryInput.value.trim();
  if (!value) return;
  const coordinates = /^([+-]?(?:\d+\.?\d*|\.\d+))\s*[,，]\s*([+-]?(?:\d+\.?\d*|\.\d+))$/.exec(value);
  const areaKey = /^(state|region):([^/\s]+)$/.exec(value);
  if (coordinates) await findCoordinates(coordinates[1], coordinates[2]);
  else if (areaKey) await openEntity(view.info.node + '/map/' + areaKey[1] + '/' + areaKey[2]);
  else if (value.includes('/')) await openEntity(value);
  else if (/^\d+$/.test(value)) await openEntity(view.info.node + '/map/province/' + value);
  else await searchEntities(value);
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
for (const id of ['clear-province', 'clear-entity']) {
  $(id).addEventListener('click', () => {
    selectionRequest?.abort();
    selectProvince(null);
    searchStatus();
  });
}
$('close-military').addEventListener('click', closeMilitary);
formationDialog.addEventListener('close', () => {
  if (!formationDialog.open) formationDialog.replaceChildren();
});
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && armyState && !formationDialog.open) {
    event.preventDefault();
    closeMilitary();
  }
});
function closeMilitary() {
  if (!armyState) return;
  armyRequest?.abort();
  setArmyState(null);
  canvas.focus({ preventScroll: true });
}
const requestedLayer = new URLSearchParams(window.location.search).get('layer');
document.querySelectorAll('input[name="layer"]').forEach((input) => {
  if (input.value === requestedLayer) {
    layer = requestedLayer;
    input.checked = true;
  }
  input.addEventListener('change', () => {
    if (areaLayers.has(layer) || areaLayers.has(input.value)) {
      selectionRequest?.abort();
      selectProvince(null);
      clearSearchResults();
      searchStatus();
    }
    layer = input.value;
    const location = new URL(window.location.href);
    location.searchParams.set('layer', layer);
    window.history.replaceState(null, '', location);
    status();
    showLegend();
    loadTerritoryGaps();
    scheduleDraw();
  });
});
$('more-territory-gaps').addEventListener('click', () => loadTerritoryGaps(Number($('more-territory-gaps').dataset.offset)));
for (const toggle of [railwayToggle, hubToggle]) {
  toggle.addEventListener('change', () => {
    loadLogistics();
    scheduleDraw();
  });
}
borderToggle.addEventListener('change', () => {
  loadGeographicBorders();
  scheduleDraw();
});
unitToggle.addEventListener('change', () => {
  loadUnits();
  scheduleDraw();
});
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
loadMap(new URLSearchParams(window.location.search).get('node'));
