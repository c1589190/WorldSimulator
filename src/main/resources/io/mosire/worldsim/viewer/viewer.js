import {
  COUNTER_HIDE_ZOOM, counterSummary, drawGhostCounters, drawUnitCounters, layoutUnitCounters, renderMilitary, showFormation, STACK_ZOOM
} from './military.js';
import { renderCountry, renderDecisions, renderPopulation, renderProvinceLogistics, renderRoundRulings } from './panels.js';

const $ = (id) => document.getElementById(id);
const canvas = $('map');
const context = canvas.getContext('2d', { alpha: false });
const nodes = $('nodes');
const branchPicker = $('branch');
const timelineTrack = $('timeline-track');
const queryInput = $('province-query');
const railwayToggle = $('railways');
const hubToggle = $('hubs');
const borderToggle = $('geo-borders');
const unitToggle = $('units');
const marchToggle = $('marches');
const featureLayers = {port: $('ports'), resource: $('resources'), contact: $('contacts')};
const featureLabels = {port: '港口', resource: '资源', contact: '情报'};
const featureColors = {port: '#7fceff', resource: '#ffca72'};
let featureHits = [];
const unitZoomNote = $('unit-zoom-note');
const militaryPanel = $('military-panel');
const perspectiveSeat = $('unit-perspective-seat');
const formationDialog = $('formation-dialog');
const subjectDialog = $('subject-decisions-dialog');
const roundDialog = $('round-rulings-dialog');
const decisionButton = $('open-subject-decisions');
const rulingsButton = $('open-round-rulings');
const recordRequests = new Map();
const noUnitSelection = new Set();
const terrainNames = {
  plains: '平原', forest: '森林', hills: '丘陵', mountain: '山地', desert: '沙漠',
  urban: '城市', marsh: '沼泽', jungle: '丛林', unknown: '未知', ocean: '海洋', lakes: '湖泊'
};
const kindNames = { land: '陆地', sea: '海域', lake: '湖泊' };
const entityNames = { province: '省份', state: '州', region: '战略区域', country: '国家',
  railway: '铁路', supplyHub: '补给中心',
  feature: '地图标记', contact: '情报标记', document: '情报文档', command: '参谋机关',
  unit: '单位', orbatDocument: '编制文档', orbatNode: '编制节点', battle: '战斗' };
const battleOutcomes = { 'attacker-victory': '攻方胜利', 'defender-victory': '守方胜利', draw: '平局', disengaged: '脱离接触' };
const supplyNames = { connected: '补给连通', disconnected: '补给不通', unknown: '补给未知' };
const areaKinds = new Set(['state', 'region']);
const number = new Intl.NumberFormat('zh-CN');
const tileLimit = 96;
const overviewLimit = 4;
const renderVersion = 3;
const minimumMapZoom = .8;
const overviews = new Map();
const tiles = new Map();

let view;
let mapRequest;
let nodeListRequest;
let timeline;
let selectionRequest;
let armyRequest;
let provinceExtra;
let mapSelection;
let layer = 'terrain';
let selection;
let selectedBattleAddress;
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

async function loadNodes(branch, followHead = false) {
  nodeListRequest?.abort();
  if (followHead) mapRequest?.abort();
  const request = new AbortController();
  nodeListRequest = request;
  try {
    const page = await json('/api/timeline', { branch }, request.signal);
    if (request.signal.aborted) return;
    if (followHead && page.head !== view?.info.node) {
      await loadMap(page.head, page);
      return;
    }
    if (view && !page.nodes.some((row) => row.node === view.info.node) && branch !== view.info.branch) {
      await loadNodes(view.info.branch);
      return;
    }
    timeline = page;
    updateNodes();
  } catch (error) {
    if (!request.signal.aborted) {
      if (view && branch !== view.info.branch) await loadNodes(view.info.branch);
      else status('时间线读取失败 · ' + error.message);
    }
  }
}

function clockLabel(clock) {
  if (!clock) return '日期未知';
  if (clock.precision === 'date') return clock.time.slice(0, 10);
  return clock.time.replace('T', ' ').replace(/:00$/, '');
}

function eventLabel(events) {
  const labels = {
    'march-arrived': '行军抵达',
    'march-blocked': '行军受阻',
    'battle-resolved': '战斗裁决',
    SetTerritory: '领土变更',
    SetTransitRight: '通行变更',
    SetRiverCrossing: '跨河资料',
    StartMarch: '出发行军',
    CancelMarch: '取消行军',
    clock: '回合截止',
    'turn-opened': '回合开放',
    'turn-resolved': '回合裁决',
    SubmitDecision: '决策收件',
  };
  const counts = new Map();
  for (const event of events || []) {
    const label = labels[event.type] || '定时变更';
    counts.set(label, (counts.get(label) || 0) + 1);
  }
  return [...counts].map(([label, count]) => count > 1 ? label + ' ×' + count : label).join(' · ');
}

function updateNodes() {
  const current = view?.info;
  const rows = timeline?.nodes || (current ? [current] : []);
  const index = rows.findIndex((row) => row.node === current?.node);
  nodes.replaceChildren(...rows.map((row) => new Option(clockLabel(row.clock) + ' · ' + row.node, row.node)));
  nodes.disabled = rows.length === 0;
  if (current) nodes.value = current.node;
  branchPicker.replaceChildren(...(timeline?.branches || []).map((branch) => new Option(branch, branch)));
  branchPicker.disabled = !timeline;
  if (timeline) branchPicker.value = timeline.branch;
  $('timeline-prev').disabled = index <= 0;
  $('timeline-next').disabled = index < 0 || index >= rows.length - 1;
  const scroll = timelineTrack.scrollLeft;
  const visible = rows.slice(Math.max(0, index - 40), Math.max(0, index) + 41);
  timelineTrack.replaceChildren(...visible.map((row) => {
    const item = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.node = row.node;
    const summary = eventLabel(row.events);
    button.title = summary ? row.node + ' · ' + summary : row.clock?.source || row.node;
    button.append(textElement('span', clockLabel(row.clock)), textElement('code', row.node));
    if (summary) button.append(textElement('small', summary));
    if (row.node === current?.node) button.setAttribute('aria-current', 'step');
    button.addEventListener('click', () => loadMap(row.node));
    item.append(button);
    return item;
  }));
  timelineTrack.scrollLeft = scroll;
  const selected = timelineTrack.querySelector('[aria-current=step]');
  if (selected) {
    const track = timelineTrack.getBoundingClientRect();
    const item = selected.getBoundingClientRect();
    if (item.left < track.left) timelineTrack.scrollLeft -= track.left - item.left + 5;
    if (item.right > track.right) timelineTrack.scrollLeft += item.right - track.right + 5;
  }
  if (timeline && index >= 0) {
    const address = new URL(window.location.href);
    address.searchParams.set('branch', timeline.branch);
    window.history.replaceState(null, '', address);
  }
}

function stepTimeline(delta) {
  const rows = timeline?.nodes || [];
  const index = rows.findIndex((row) => row.node === view?.info.node);
  if (index >= 0 && rows[index + delta]) loadMap(rows[index + delta].node);
}

function rebindPath(address) {
  if (!address) return null;
  const path = address.slice(address.indexOf('/') + 1);
  return /^(country|orbat\/unit|map\/province)\/[^/]+$/.test(path) ? path : null;
}

async function loadMap(node, branchPage) {
  closeRecordDialogs();
  rulingsButton.disabled = true;
  const carry = mapRequest ? mapSelection : {
    province: rebindPath(selection?.address),
    entity: rebindPath(armyState?.result?.address || armyState?.address),
  };
  mapSelection = carry;
  mapRequest?.abort();
  if (!branchPage) nodeListRequest?.abort();
  selectionRequest?.abort();
  armyRequest?.abort();
  provinceExtra?.request.abort();
  if (carry.entity) setArmyState({ kind: carry.entity.startsWith('country/') ? 'country' : 'unit', message: '正在切换节点…' });
  else if (armyState) setArmyState(null);
  const request = new AbortController();
  mapRequest = request;
  status('正在加载地图…');
  try {
    const info = await json('/api/map', { node }, request.signal);
    if (request.signal.aborted) return null;
    const first = !view;
    const keepCamera = view && view.info.width === info.width && view.info.height === info.height;
    view?.requests.abort();
    view = { info, requests: new AbortController() };
    updateCounterHover();
    if (branchPage) timeline = branchPage;
    if (!timeline?.nodes.some((row) => row.node === info.node)) {
      timeline = null;
      loadNodes(first ? initialUrl.searchParams.get('branch') || info.branch : info.branch);
    }
    $('march-option').hidden = !info.marches;
    $('march-panel').hidden = true;
    $('march-list').replaceChildren();
    $('battle-panel').hidden = true;
    $('battle-list').replaceChildren();
    $('more-battles').hidden = true;
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
    if (keepCamera) containMap();
    else fitMap();
    loadLogistics();
    loadGeographicBorders();
    loadTerritoryGaps();
    loadUnits();
    loadMapFeatures();
    loadMarches();
    loadBattles();
    return view;
  } catch (error) {
    if (!request.signal.aborted) {
      updateNodes();
      status('地图读取失败 · ' + error.message);
    }
    return null;
  } finally {
    if (mapRequest === request) {
      mapRequest = null;
      rulingsButton.disabled = !view;
      mapSelection = null;
      if (view && !request.signal.aborted) {
        const label = view.info.node;
        if (carry.province) findProvince('/api/province', { address: label + '/' + carry.province });
        if (carry.entity) openEntity(label + '/' + carry.entity, false);
      }
    }
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
  if (entry && (entry.state !== 'failed' || entry.source === source)) {
    cache.delete(key);
    cache.set(key, entry);
    return entry;
  }
  cache.delete(key);
  const picture = new Image();
  const next = { picture, source, state: 'loading' };
  picture.onload = () => {
    next.state = 'ready';
    scheduleDraw();
  };
  picture.onerror = () => {
    next.state = 'failed';
    scheduleDraw();
  };
  cache.set(key, next);
  picture.src = source;
  return next;
}

function trimImages(cache, limit) {
  while (cache.size > limit) {
    const key = cache.keys().next().value;
    const entry = cache.get(key);
    entry.picture.onload = null;
    entry.picture.onerror = null;
    if (entry.state === 'loading') entry.picture.removeAttribute('src');
    cache.delete(key);
  }
}

function renderKey(info) {
  if (layer === 'owner-gaps') return info.stateHash + '/' + layer;
  const key = info.mapVersion + '/' + layer;
  return layer === 'owner' || layer === 'controller' ? key + '/' + info.politicsVersion : key;
}

function draw() {
  drawingScheduled = false;
  unitHits = [];
  const relativeZoom = view ? scale / fitScale() : 1;
  const belowCounterZoom = relativeZoom < COUNTER_HIDE_ZOOM;
  const hasCounters = view && (view.info.units || view.info.battleMarkers.length || view.contactCounters?.length);
  unitZoomNote.toggleAttribute('hidden', !hasCounters || !unitToggle.checked || !belowCounterZoom);
  if (belowCounterZoom || !unitToggle.checked) updateCounterHover();
  context.setTransform(density, 0, 0, density, 0, 0);
  context.fillStyle = '#122630';
  context.fillRect(0, 0, width, height);
  if (!view) return;

  const { info } = view;
  const key = renderKey(info);
  const base = image(overviews, key, url('/api/overview', { ...version(view), layer, renderVersion }));
  trimImages(overviews, overviewLimit);
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
  drawTiles(key);
  drawLogistics();
  drawGeographicBorders();
  drawSelection();
  drawSelectedMarch();
  context.restore();
  if (!belowCounterZoom) drawUnits(relativeZoom);
  drawMapFeatures();
}

async function loadMapFeatures() {
  const target = view;
  try {
    const data = await json('/api/map-features', version(target), target.requests.signal);
    if (view !== target) return;
    target.features = data;
    target.contactCounters = data.filter(isContact);
    target.counterLayout = null;
    showProvinceFeatures();
    scheduleDraw();
  } catch {
    if (view === target && !target.requests.signal.aborted) status('地图标记读取失败');
  }
}

function showProvinceFeatures() {
  const sight = view?.unitSight;
  const items = (view?.features || []).filter(item => item.provinceAddress === selection?.address
    && (!isContact(item) || !sight || sight.contacts.has(item.address)));
  $('province-features').hidden = items.length === 0;
  $('province-feature-list').replaceChildren(...items.map(item =>
    listLink(featureLabels[item.kind] + ' · ' + item.name, item.address, false)));
}

function drawMapFeatures() {
  featureHits = [];
  const offsets = new Map();
  for (const marker of view.features || []) {
    if (isContact(marker) || !featureLayers[marker.kind].checked) continue;
    const index = offsets.get(marker.provinceAddress) || 0;
    offsets.set(marker.provinceAddress, index + 1);
    const x = left + marker.point.x * scale + (index % 5) * 15;
    const y = top + marker.point.y * scale - 13 - Math.floor(index / 5) * 15;
    if (x < 0 || x > width || y < 0 || y > height) continue;
    context.fillStyle = '#172528';
    context.strokeStyle = featureColors[marker.kind];
    context.lineWidth = 1.5;
    context.beginPath();
    if (marker.kind === 'resource') {
      context.moveTo(x, y - 6);
      context.lineTo(x + 6, y);
      context.lineTo(x, y + 6);
      context.lineTo(x - 6, y);
      context.closePath();
    } else {
      context.rect(x - 5, y - 5, 10, 10);
    }
    context.fill();
    context.stroke();
    featureHits.push({ x, y, marker });
  }
}

function isContact(marker) {
  return marker.kind === 'contact';
}

function drawTiles(renderKey) {
  const { info } = view;
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
      const key = [renderKey, level, x, y].join('/');
      const tile = image(tiles, key, url('/api/tile', { ...version(view), layer, level, x, y, renderVersion }));
      if (tile.state === 'ready') context.drawImage(tile.picture, x * step, y * step, step, step);
    }
  }
  trimImages(tiles, tileLimit);
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
  const shapes = [
    { shape: selection.region, fill: '#328b9730', border: '#a0f1ee', outline: '#214b56', width: 1.6, dash: [6, 4] },
    { shape: selection.state, fill: '#e4a34a3b', border: '#f4bc78', outline: '#51381f', width: 1.8, dash: [] },
    { shape: selection.province, fill: '#f9ce785c', border: '#fff1bd', outline: '#33261c', width: 2, dash: [] },
  ].filter(({ shape }) => shape);
  for (const { shape, fill } of shapes) {
    context.fillStyle = fill;
    context.fill(shape.path, 'evenodd');
  }
  // Dash the region outline over any state edges that share its boundary.
  for (const { shape, outline, width: strokeWidth } of shapes) {
    context.strokeStyle = outline;
    context.lineWidth = (strokeWidth + 2) / scale;
    context.stroke(shape.path);
  }
  for (const { shape, border, width: strokeWidth, dash } of [...shapes].reverse()) {
    context.setLineDash(dash.map((length) => length / scale));
    context.strokeStyle = border;
    context.lineWidth = strokeWidth / scale;
    context.stroke(shape.path);
  }
  context.setLineDash([]);
}

function marchLine(points) {
  const vertices = points.filter((point, index) => index === 0
    || point.x !== points[index - 1].x || point.y !== points[index - 1].y);
  if (vertices.length < 2) return null;

  const path = new Path2D();
  path.moveTo(vertices[0].x, vertices[0].y);
  for (let index = 1; index < vertices.length - 1; index++) {
    const previous = vertices[index - 1];
    const corner = vertices[index];
    const next = vertices[index + 1];
    const incoming = Math.hypot(previous.x - corner.x, previous.y - corner.y);
    const outgoing = Math.hypot(next.x - corner.x, next.y - corner.y);
    const radius = .28 * Math.min(incoming, outgoing);
    path.lineTo(corner.x + (previous.x - corner.x) * radius / incoming,
      corner.y + (previous.y - corner.y) * radius / incoming);
    path.quadraticCurveTo(corner.x, corner.y, corner.x + (next.x - corner.x) * radius / outgoing,
      corner.y + (next.y - corner.y) * radius / outgoing);
  }
  const end = vertices.at(-1);
  const beforeEnd = vertices.at(-2);
  const dx = end.x - beforeEnd.x;
  const dy = end.y - beforeEnd.y;
  path.lineTo(end.x, end.y);
  return { path, end, angle: Math.atan2(dy, dx), length: Math.hypot(dx, dy) };
}

function marchDrawing(march) {
  const { points, reachedLegs, fraction } = march;
  const from = points[reachedLegs];
  const to = points[reachedLegs + 1];
  const position = to ? { x: from.x + (to.x - from.x) * fraction, y: from.y + (to.y - from.y) * fraction } : from;
  return {
    position,
    reached: marchLine([...points.slice(0, reachedLegs + 1), position]),
    remaining: to ? marchLine([position, ...points.slice(reachedLegs + 1)]) : null,
  };
}

function strokeMarchLine(line, color, dashed = false) {
  if (!line) return;
  context.strokeStyle = '#213439';
  context.lineWidth = 8 / scale;
  context.stroke(line.path);
  context.strokeStyle = color;
  context.lineWidth = 4.5 / scale;
  if (dashed) context.setLineDash([8 / scale, 7 / scale]);
  context.stroke(line.path);
  context.setLineDash([]);
}

function drawMarchHead(line, color, clearArrival) {
  const length = between(line.length * scale * .65, 7, 19);
  const inset = clearArrival ? Math.min(27 / scale, line.length * .38) : 0;
  context.save();
  context.translate(line.end.x - Math.cos(line.angle) * inset, line.end.y - Math.sin(line.angle) * inset);
  context.rotate(line.angle);
  context.scale(1 / scale, 1 / scale);
  context.beginPath();
  context.moveTo(0, 0);
  context.quadraticCurveTo(-.55 * length, .32 * length, -length, .48 * length);
  context.lineTo(-.76 * length, 0);
  context.lineTo(-length, -.48 * length);
  context.quadraticCurveTo(-.55 * length, -.32 * length, 0, 0);
  context.closePath();
  context.lineWidth = 2.2;
  context.strokeStyle = '#213439';
  context.fillStyle = color;
  context.stroke();
  context.fill();
  context.restore();
}

function drawSelectedMarch() {
  const selected = armyState?.selected;
  if (!marchToggle.checked || selected?.size !== 1 || selectedBattleAddress) return;
  const march = view.marches?.get(selected.values().next().value);
  if (!march || march.points.length < 2) return;
  const { reached, remaining, position } = march.drawing ??= marchDrawing(march);
  const arrow = remaining || reached;
  if (!arrow) return;
  const reachedColor = '#86e8d8';
  const remainingColor = { cancelled: '#b2b6b2', blocked: '#ff9b84' }[march.status] || '#ffd794';
  context.save();
  context.lineJoin = 'round';
  context.lineCap = 'round';
  strokeMarchLine(reached, reachedColor);
  strokeMarchLine(remaining, remainingColor, true);
  drawMarchHead(arrow, remaining ? remainingColor : reachedColor, march.status === 'arrived' && unitToggle.checked);
  if (march.status === 'moving') {
    context.beginPath();
    context.arc(position.x, position.y, 3.4 / scale, 0, 2 * Math.PI);
    context.fillStyle = reachedColor;
    context.fill();
  }
  context.restore();
}

function focusMarch(march) {
  if (march.points.length) {
    const x = march.points.map((point) => point.x);
    const y = march.points.map((point) => point.y);
    const minX = Math.min(...x);
    const minY = Math.min(...y);
    const maxX = Math.max(...x);
    const maxY = Math.max(...y);
    fitSelection({ width: maxX - minX, height: maxY - minY,
      point: { x: (minX + maxX) / 2, y: (minY + maxY) / 2 } });
  }
  openEntity(march.unitAddress, false);
}

async function loadMarches() {
  const target = view;
  if (!target?.info.marches) return;
  try {
    const result = await json('/api/marches', version(target), target.requests.signal);
    if (view !== target) return;
    const { marches } = result;
    target.marches = new Map(marches.map(march => [march.unitAddress, march]));
    $('march-panel').hidden = marches.length === 0;
    $('march-list').replaceChildren(...marches.map((march) => {
      const label = { moving: '行军中', arrived: '已抵达', cancelled: '已取消', blocked: '通行受阻' }[march.status];
      const time = clockLabel({ time: march.blockage?.time || march.cancelledAt || march.arrivalTime });
      const detail = label + ' · ' + march.reachedLegs + '/' + march.legs.length + ' 段 · ' + time;
      const item = document.createElement('li');
      const button = document.createElement('button');
      button.type = 'button';
      button.append(textElement('span', march.name), textElement('span', detail, 'march-meta'));
      const leg = march.legs[Math.min(march.reachedLegs, march.legs.length - 1)];
      const conditions = leg?.conditions;
      if (march.blockage) {
        button.append(textElement('span', '下一省管理方 ' + march.blockage.passage.authorityCountryId, 'march-meta'));
      } else if (march.status === 'moving' && conditions) {
        const terrain = terrainNames[conditions.terrain] || conditions.terrain;
        const supply = supplyNames[conditions.supply.status];
        button.append(textElement('span', terrain + ' · ' + supply + ' · '
          + number.format(conditions.effectiveSpeedKmPerHour) + ' 公里/小时', 'march-meta'));
      }
      if (leg?.edge) {
        const river = {
          'confirmed-crossing': '已确认跨河',
          'confirmed-clear': '已确认无跨河',
          candidate: '跨河待核',
          'none-in-reference': '参考图未见河流',
          unknown: '河流资料缺失',
        }[leg.edge.river.status];
        const rail = leg.edge.railways.length === 0 ? '无铁路记录'
          : leg.edge.railways.every(track => track.status === 'closed') ? '铁路停用'
          : leg.edge.railways.some(track => track.status === 'open') ? '有运行铁路' : '有铁路 · 运行未知';
        button.append(textElement('span', river + ' · ' + rail, 'march-meta'));
      }
      button.addEventListener('click', () => focusMarch(march));
      item.append(button);
      return item;
    }));
    scheduleDraw();
  } catch (error) {
    if (!target.requests.signal.aborted) status('行军读取失败 · ' + error.message);
  }
}

async function loadBattles(offset = 0) {
  const target = view;
  if (!target?.info.battles || target.loadingBattles) return;
  target.loadingBattles = true;
  const more = $('more-battles');
  more.disabled = true;
  try {
    const page = await json('/api/battles', { ...version(target), offset }, target.requests.signal);
    if (view !== target) return;
    $('battle-panel').hidden = !page.total;
    const items = page.battles.map((battle) => {
      const item = document.createElement('li');
      const button = linkButton(battle.displayName, battle.address);
      const label = battleOutcomes[battle.entity.outcome] || battle.entity.outcome;
      button.append(textElement('span', label + ' · ' + clockLabel({ time: battle.entity.endedAt }), 'battle-meta'));
      item.append(button);
      return item;
    });
    $('battle-list').append(...items);
    more.dataset.offset = page.offset + page.battles.length;
    more.hidden = Number(more.dataset.offset) >= page.total;
  } catch (error) {
    if (!target.requests.signal.aborted) status('战斗记录读取失败 · ' + error.message);
  } finally {
    target.loadingBattles = false;
    if (view === target) more.disabled = false;
  }
}

async function loadUnits() {
  const target = view;
  if (!target?.info.units || target.units || target.loadingUnits || !unitToggle.checked) return;
  target.loadingUnits = true;
  try {
    const units = await json('/api/units', version(target), target.requests.signal);
    if (view !== target) return;
    target.units = units;
    target.counterLayout = null;
    scheduleDraw();
  } catch (error) {
    if (!target.requests.signal.aborted) status('单位图层加载失败 · ' + error.message);
  } finally {
    target.loadingUnits = false;
  }
}

function drawUnits(relativeZoom) {
  if (!unitToggle.checked) return;
  const camera = [left, top, width, height, scale];
  if (!view.counterLayout || camera.some((value, index) => value !== view.counterLayout.camera[index])) {
    const bounds = { left, top, width, height, scale, zoom: relativeZoom };
    const sight = view.unitSight;
    const visible = [];
    const ghosts = [];
    for (const unit of view.units || []) {
      if (!sight || sight.units.has(unit.address)) visible.push(unit);
      else ghosts.push(unit);
    }
    const contacts = featureLayers.contact.checked ? view.contactCounters || [] : [];
    const hits = layoutUnitCounters([...visible, ...view.info.battleMarkers,
      ...contacts.filter(contact => !sight || sight.contacts.has(contact.address))], bounds);
    const ghostHits = layoutUnitCounters(ghosts, { ...bounds, avoid: hits }).map(hit => ({ ...hit, ghost: true }));
    view.counterLayout = { camera, hits, ghostHits };
  }
  const { hits, ghostHits } = view.counterLayout;
  unitHits = [...ghostHits, ...hits];
  const armySelection = armyState?.selected || noUnitSelection;
  const selected = selectedBattleAddress ? new Set([...armySelection, selectedBattleAddress]) : armySelection;
  if (ghostHits.length) drawGhostCounters(context, ghostHits, density);
  drawUnitCounters(context, hits, selected);
}

function shapeSelection(shape, point) {
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
  return { path, width: maxX - minX, height: maxY - minY,
    point: point || { x: (minX + maxX) / 2, y: (minY + maxY) / 2 } };
}

function fitSelection(shape) {
  const fit = Math.min(width / shape.width, height / shape.height) * .55;
  scale = between(fit, fitScale(), maxScale());
  left = width / 2 - shape.point.x * scale;
  top = height / 2 - shape.point.y * scale;
  containMap();
}

function showProvinceArea(kind, area, fallback) {
  const field = $('province-' + kind);
  field.textContent = area?.displayName || fallback || '—';
  if (area) field.append(' · ', textElement('code', area.stableId));
}

function selectProvince(result) {
  provinceExtra?.request.abort();
  provinceExtra = null;
  $('province-logistics').open = false;
  $('province-population').hidden = true;
  $('province-population-content').replaceChildren();
  $('province-logistics-content').replaceChildren();
  $('entity-detail').hidden = true;
  selectedBattleAddress = null;
  $('province').hidden = !result;
  $('empty-province').hidden = Boolean(result);
  selection = null;
  if (result) {
    const province = result.entity;
    selection = {
      address: result.address,
      province: shapeSelection(province.shape, province.displayPoint),
      state: result.state && shapeSelection(result.state.shape),
      region: result.region && shapeSelection(result.region.shape),
    };
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
    showProvinceArea('state', result.state, result.stateName || province.stateId);
    showProvinceArea('region', result.region, result.regionName || province.regionId);
    $('province-owner-row').hidden = !view.info.countries;
    $('province-controller-row').hidden = !view.info.countries;
    showCountryRelation('owner', result);
    showCountryRelation('controller', result);
    const units = result.units || [];
    $('province-unit-section').hidden = units.length === 0;
    $('province-units').replaceChildren(...units.map((unit) => listLink(unit.name, unit.address, false)));
    $('province-address').value = result.address;
    showEvidence(result);
    const details = { address: result.address, view, request: new AbortController() };
    provinceExtra = details;
    if (province.kind === 'land') {
      $('province-population').hidden = false;
      loadProvinceExtra(details, 'population');
    }
  }
  showProvinceFeatures();
  scheduleDraw();
}

function showCountryRelation(kind, result) {
  const field = $('province-' + kind);
  const address = result[kind + 'Address'];
  const name = result[kind + 'Name'];
  if (name && address) field.replaceChildren(linkButton(name, address, false));
  else field.textContent = name || '未知';
}

async function loadProvinceExtra(details, kind) {
  const area = $('province-' + kind + '-content');
  area.replaceChildren(textElement('p', '正在读取…', 'entity-source'));
  const { view: target, address, request } = details;
  try {
    const data = await json('/api/province/' + kind, { ...version(target), address }, request.signal);
    if (view !== target || details !== provinceExtra || request.signal.aborted) return;
    if (kind === 'population') renderPopulation(area, data.records, evidenceLine);
    else {
      renderProvinceLogistics(area, data, { link: linkButton, evidence: evidenceLine });
      details.logisticsLoaded = true;
    }
  } catch (error) {
    if (view === target && details === provinceExtra && !request.signal.aborted) {
      area.replaceChildren(textElement('p', '读取失败 · ' + error.message, 'entity-source'));
    }
  } finally {
    if (kind === 'logistics') details.logisticsLoading = false;
  }
}

$('province-logistics').addEventListener('toggle', (event) => {
  if (event.currentTarget.open && provinceExtra && !provinceExtra.logisticsLoaded && !provinceExtra.logisticsLoading) {
    provinceExtra.logisticsLoading = true;
    loadProvinceExtra(provinceExtra, 'logistics');
  }
});

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

function showUnitSight(sight) {
  const panel = $('unit-perspective');
  panel.hidden = !sight;
  perspectiveSeat.hidden = (sight?.seats?.length || 0) < 2;
  if (sight) {
    $('unit-perspective-note').textContent = sight.message
      || (sight.seat ? sight.seat.name + ' · 灰色为 GM 底影，? 为情报' : '当前回合无绑定 · 仅确认本单位');
  }
  if (view) view.counterLayout = null;
  updateCounterHover();
  showProvinceFeatures();
  scheduleDraw();
}

function selectUnitSight(sight, seat) {
  sight.seat = seat;
  sight.units = new Set(seat?.units || [sight.address]);
  sight.contacts = new Set(seat?.contacts || []);
  if (!$('entity-detail').hidden && $('entity-kind').textContent === entityNames.contact
      && !sight.contacts.has($('entity-address').textContent)) selectProvince(null);
  showUnitSight(sight);
}

async function loadUnitSight(state) {
  const target = view;
  const address = state?.result?.kind === 'unit' ? state.result.address : null;
  const unitAddress = address?.startsWith(target?.info.node + '/orbat/unit/') ? address : null;
  if (!unitAddress) {
    if (target) target.unitSight = null;
    showUnitSight(null);
    return;
  }
  if (target.unitSight?.address === unitAddress) return;
  const sight = { address: unitAddress, message: '正在读取部队视野…' };
  target.unitSight = sight;
  selectUnitSight(sight, null);
  try {
    const seats = await json('/api/unit-perspective', { ...version(target), address: unitAddress }, target.requests.signal);
    if (view !== target || target.unitSight !== sight) return;
    sight.seats = seats;
    sight.message = null;
    perspectiveSeat.replaceChildren(...seats.map(seat => new Option(seat.name, seat.id)));
    selectUnitSight(sight, seats[0]);
  } catch {
    if (view !== target || target.unitSight !== sight || target.requests.signal.aborted) return;
    sight.message = '部队视野读取失败 · 仅确认本单位';
    showUnitSight(sight);
  }
}

perspectiveSeat.addEventListener('change', () => {
  const sight = view?.unitSight;
  if (!sight) return;
  const seat = sight.seats.find(item => item.id === perspectiveSeat.value);
  if (!seat) return;
  selectUnitSight(sight, seat);
});

function setArmyState(state) {
  if (formationDialog.open) formationDialog.close();
  const changed = Boolean(armyState) !== Boolean(state);
  const content = $('military-content');
  armyState = state;
  militaryPanel.hidden = !state;
  decisionButton.hidden = !['country', 'unit'].includes(state?.result?.kind);
  militaryPanel.parentElement.classList.toggle('military-open', Boolean(state));
  if (state) {
    const address = state.address || state.result?.address;
    const country = state.kind === 'country';
    $('military-heading').textContent = country ? '国家' : '军队';
    state.selected = new Set(country ? [] : address ? [address] : state.roster?.units.map(unit => unit.address));
    if (country && state.result) renderCountry(content, state.result, { link: linkButton, evidence: evidenceLine });
    else renderMilitary(content, state, {
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
  loadUnitSight(state);
  scheduleDraw();
}

function closeRecordDialogs() {
  for (const dialog of [subjectDialog, roundDialog]) {
    recordRequests.get(dialog)?.abort();
    if (dialog.open) dialog.close();
  }
}

async function openRecordDialog(dialog, heading, path, params, render) {
  if (!view || mapRequest) return;
  closeRecordDialogs();
  const target = view;
  const request = new AbortController();
  recordRequests.set(dialog, request);
  const title = dialog.querySelector('h2');
  const context = dialog.querySelector('.record-bar p');
  const body = dialog.querySelector('.record-body');
  title.textContent = heading;
  context.textContent = target.info.node;
  body.replaceChildren(textElement('p', '正在读取…', 'record-meta'));
  body.scrollTop = 0;
  dialog.showModal();
  try {
    const data = await json(path, { ...version(target), ...params }, request.signal);
    if (view !== target || !dialog.open || request.signal.aborted) return;
    if (data.openingAddress) {
      const [opening, , id] = data.openingAddress.split('/');
      const resolved = data.resolvedAddress?.split('/')[0];
      context.textContent = id + ' · 开放于 ' + opening + (resolved ? ' · 裁定于 ' + resolved : '') + ' · 当前 ' + data.node;
    }
    render(body, data, title);
  } catch (error) {
    if (view === target && dialog.open && !request.signal.aborted) {
      body.replaceChildren(textElement('p', '读取失败 · ' + error.message, 'record-meta'));
    }
  } finally {
    if (recordRequests.get(dialog) === request) recordRequests.delete(dialog);
  }
}

function openSubjectDecisions(address, decisionMakerId, fromRound = false) {
  if (!address) return;
  openRecordDialog(subjectDialog, '决策原文', '/api/decisions', { address }, (body, data, title) => {
    title.textContent = data.subjectName + ' · 决策原文';
    renderDecisions(body, data, {
      link: (label, ref) => {
        const link = textElement('button', label, 'entity-link');
        link.type = 'button';
        link.addEventListener('click', () => {
          closeRecordDialogs();
          openEntity(ref, false);
        });
        return link;
      },
      showNode: loadMap,
      showRound: openRoundRulings,
      decisionMakerId,
      fromRound,
    });
  });
}

function openRoundRulings() {
  openRecordDialog(roundDialog, '本回合裁定', '/api/turn/rulings', {}, (body, data) =>
    renderRoundRulings(body, data, (address, id) => openSubjectDecisions(address, id, true)));
}

function selectCounter(counter) {
  selectionRequest?.abort();
  if (counter.units.length === 1) {
    const address = counter.units[0].address;
    const roster = armyState?.roster?.units.some(unit => unit.address === address) ? armyState.roster : undefined;
    openEntity(address, false, { roster });
    return;
  }
  if (view.unitSight && counter.units.some(isContact)) {
    showContactStack(counter.units);
    return;
  }
  armyRequest?.abort();
  const point = counter.units.reduce((center, unit) => ({
    x: center.x + unit.point.x / counter.units.length,
    y: center.y + unit.point.y / counter.units.length
  }), { x: 0, y: 0 });
  setArmyState({ roster: { units: counter.units, point } });
}

function showContactStack(units) {
  selectProvince(null);
  $('empty-province').hidden = true;
  $('entity-detail').hidden = false;
  $('entity-kind').textContent = entityNames.contact;
  $('entity-name').textContent = counterSummary(units);
  $('entity-key').hidden = true;
  $('entity-address').textContent = '';
  const list = textElement('ul', '', 'entity-links');
  for (const unit of units) list.append(listLink((isContact(unit) ? '情报 · ' : '') + unit.name, unit.address, false));
  $('entity-content').replaceChildren(list);
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
    await findProvince('/api/province', { address }, center);
    return;
  }
  const target = view;
  const request = new AbortController();
  const country = parts[1] === 'country';
  const military = parts[1] === 'orbat' && !['battle', 'command'].includes(parts[2]);
  const leftPanel = country || military;
  if (leftPanel) {
    if (country || parts[2] === 'unit') selectedBattleAddress = null;
    armyRequest?.abort();
    armyRequest = request;
    setArmyState({ address, roster, kind: country ? 'country' : 'unit', message: country ? '正在读取国家档案…' : '正在读取编制…' });
  } else {
    selectionRequest?.abort();
    selectionRequest = request;
  }
  searchStatus();
  const lookup = (entityAddress) => json('/api/entity', { ...version(target), address: entityAddress }, request.signal);
  try {
    const result = await lookup(address);
    if (result.kind === 'country') {
      if (view !== target || request.signal.aborted) return;
      setArmyState({ result, kind: 'country' });
      return;
    }
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
    if (areaKinds.has(result.kind) && result.point) {
      await findProvince('/api/pick', result.point, center, result.shape);
      return;
    }
    showEntity(result, center);
  } catch (error) {
    if (request.signal.aborted) return;
    if (leftPanel) setArmyState({ address, roster, kind: country ? 'country' : 'unit',
      message: '读取失败 · ' + error.message });
    else searchStatus(error.message, true);
  }
}

function showEntity(result, center) {
  const content = [];
  if (result.kind === 'battle') {
    content.push(...battleDetail(result));
  } else if (result.kind === 'railway') {
    const railway = result.entity;
    const service = { open: '开通', closed: '停运' }[railway.operation?.status] || '运营状态未知';
    content.push(textElement('p', '等级 ' + railway.level + ' · ' + service));
    if (railway.operation?.tonnesPerDay != null) {
      content.push(textElement('p', number.format(railway.operation.tonnesPerDay) + ' 吨／日'));
    }
    if (railway.operation?.evidence) content.push(evidenceLine(railway.operation.evidence));
  } else if (result.kind === 'supplyHub') {
    const hub = result.entity;
    content.push(textElement('p', '等级 ' + hub.level));
    content.push(linkButton('查看所在省', view.info.node + '/map/province/' + hub.provinceId, false));
  } else if (result.kind === 'feature' || result.kind === 'contact') {
    const item = result.entity;
    if (result.kind === 'contact') {
      content.push(textElement('p', '发布者 · ' + item.owner + ' · ' + item.observedAt));
      if (item.suspectedUnit) content.push(textElement('p', '疑似单位 · ' + item.suspectedUnit));
      content.push(textElement('p', item.text, 'record-text'));
    } else {
      const operation = { open: '开通', closed: '关闭', active: '开采中', inactive: '停采' };
      content.push(textElement('p', featureLabels[item.kind] + ' · ' + (operation[item.operation] || '状态未知')));
      if (item.level != null) content.push(textElement('p', '等级 ' + item.level));
      if (item.commodity) content.push(textElement('p', item.commodity + (item.quantity == null ? ' · 产量未知' : ' · ' + item.quantity + ' ' + item.unit)));
      if (item.waterProvinceId) content.push(linkButton('临接海省', view.info.node + '/map/province/' + item.waterProvinceId, false));
    }
    content.push(evidenceLine(item.evidence), linkButton('查看所在省', view.info.node + '/map/province/' + item.provinceId, false));
  } else if (result.kind === 'document') {
    content.push(textElement('p', result.entity.text, 'record-text'), evidenceLine(result.entity.evidence));
  } else if (result.kind === 'command') {
    const item = result.entity;
    content.push(textElement('p', item.kind === 'general-staff' ? '总参谋部' : '集团军参谋部'));
    if (item.parentId) content.push(linkButton('查看上级', view.info.node + '/orbat/command/' + item.parentId, false));
    for (const unit of item.unitIds) content.push(linkButton(unit, view.info.node + '/orbat/unit/' + unit, false));
    content.push(textElement('button', '查看决策', 'entity-link'));
    content.at(-1).addEventListener('click', () => openSubjectDecisions(result.address));
  } else if (result.entity.provinceIds) {
    const provinceIds = result.entity.provinceIds;
    content.push(textElement('p', '覆盖 ' + number.format(provinceIds.length) + ' 省'));
    if (provinceIds.length) {
      content.push(linkButton('查看成员省 ' + provinceIds[0], view.info.node + '/map/province/' + provinceIds[0]));
    }
  }
  selectProvince(null);
  if (result.kind === 'battle') selectedBattleAddress = result.address;
  $('empty-province').hidden = true;
  $('entity-detail').hidden = false;
  $('entity-kind').textContent = entityNames[result.kind] || result.kind;
  $('entity-name').textContent = result.displayName;
  $('entity-key').hidden = !areaKinds.has(result.kind);
  $('entity-key').textContent = 'Key ' + result.stableId;
  $('entity-address').textContent = result.address;
  $('entity-content').replaceChildren(...content);
  if (center && result.point) centerMap(result.point, 4);
}

function battleDetail(result) {
  const { entity } = result;
  const conclusion = battleOutcomes[entity.outcome] || entity.outcome;
  const rows = [textElement('p', conclusion + ' · ' + clockLabel({ time: entity.endedAt })),
    textElement('p', entity.rationale), evidenceLine(entity.evidence)];
  const units = new Map();
  for (const [label, participants] of [['攻方', result.attackers], ['守方', result.defenders]]) {
    rows.push(textElement('h4', label));
    for (const participant of participants) {
      units.set(participant.address.split('/').at(-1), participant.name);
      rows.push(linkButton(participant.name, participant.address, false));
    }
  }
  if (result.controllerName) rows.push(textElement('p', '战后控制 · ' + result.controllerName));
  if (entity.losses.length) {
    rows.push(textElement('h4', '直属损耗'));
    const list = document.createElement('ul');
    list.className = 'battle-losses';
    for (const loss of entity.losses) {
      const counts = loss.equipment.map((item) => lossText(item.name || item.itemId, item.quantity));
      if (loss.personnel) counts.unshift(lossText('人员', loss.personnel));
      list.append(textElement('li', (units.get(loss.unitId) || loss.unitId) + ' / ' + loss.nodeId + ' · ' + counts.join('、')));
    }
    rows.push(list);
  }
  if (result.provinceAddress) rows.push(linkButton('查看交战省', result.provinceAddress, false));
  return rows;
}

function lossText(label, count) {
  const before = count.before == null ? '基数未知' : number.format(count.before) + ' → ' + number.format(count.after);
  return label + ' −' + number.format(count.lost) + '（' + before + '）';
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

async function findProvince(path, parameters, center = false, focusShape) {
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
    selectProvince(result);
    if (!result) searchStatus('此处没有地块。');
    else if (center) fitSelection(focusShape ? shapeSelection(focusShape) : selection.province);
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
    note.textContent = {
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
    summary.textContent = result.coverage === 'none' ? '当前查询没有参考覆盖'
      : scope + ' · 已检查 ' + number.format(result.checkedProvinces) + ' 省 · 待补 '
        + number.format(result.unassigned) + ' · 归属冲突 ' + number.format(result.ownershipMismatches);
    list.append(...result.gaps.map(gap => listLink(
      [gap.name, '省 ' + gap.provinceId, gap.stateName,
        gap.issue === 'owner-mismatch' ? gap.actualCountryId + ' → 预期 ' + gap.expectedCountryId : gap.expectedCountryId]
        .filter(Boolean).join(' · '), gap.address)));
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
  if (!unitToggle.checked || !view || mapRequest || scale / fitScale() < COUNTER_HIDE_ZOOM) return undefined;
  return unitHits.findLast((hit) => Math.abs(hit.x - point.x) <= hit.width / 2
    && Math.abs(hit.y - point.y) <= hit.height / 2);
}

function updateCounterHover(counter) {
  canvas.classList.toggle('over-counter', Boolean(counter));
  const front = counter?.units[0];
  const label = counter?.units.length > 1 ? counterSummary(counter.units)
    : front ? (isContact(front) ? '情报 · ' : '') + front.name : '';
  const title = counter?.ghost ? 'GM 底影 · ' + label : label;
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
    const feature = featureHits.findLast(hit => Math.hypot(hit.x - point.x, hit.y - point.y) <= 8);
    if (feature) openEntity(feature.marker.address, false);
    else if (counter) selectCounter(counter);
    else {
      const location = { x: (point.x - left) / scale, y: (point.y - top) / scale };
      if (armyState?.kind !== 'country') closeMilitary();
      findProvince('/api/pick', location);
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
const initialUrl = new URL(window.location.href);
const requestedLayer = initialUrl.searchParams.get('layer');
if (areaKinds.has(requestedLayer)) {
  initialUrl.searchParams.set('layer', 'terrain');
  window.history.replaceState(null, '', initialUrl);
}
document.querySelectorAll('input[name="layer"]').forEach((input) => {
  if (input.value === requestedLayer) {
    layer = requestedLayer;
    input.checked = true;
  }
  input.addEventListener('change', () => {
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
$('more-battles').addEventListener('click', () => loadBattles(Number($('more-battles').dataset.offset)));
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
  loadNodes(timeline?.branch || view?.info.branch, Boolean(view?.info.node === timeline?.head));
});
branchPicker.addEventListener('change', () => loadNodes(branchPicker.value, true));
$('timeline-prev').addEventListener('click', () => stepTimeline(-1));
$('timeline-next').addEventListener('click', () => stepTimeline(1));
decisionButton.addEventListener('click', () => openSubjectDecisions(armyState?.result?.address));
rulingsButton.addEventListener('click', openRoundRulings);
for (const dialog of [subjectDialog, roundDialog]) {
  dialog.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !event.isComposing) {
      event.preventDefault();
      dialog.close();
    }
  });
  dialog.addEventListener('close', () => {
    if (!dialog.open) recordRequests.get(dialog)?.abort();
  });
}
nodes.addEventListener('change', () => loadMap(nodes.value));
marchToggle.addEventListener('change', scheduleDraw);
for (const toggle of Object.values(featureLayers)) toggle.addEventListener('change', scheduleDraw);
featureLayers.contact.addEventListener('change', () => {
  if (view) view.counterLayout = null;
  updateCounterHover();
});

new ResizeObserver(resize).observe($('viewport'));
resize();
showLegend();
loadMap(new URLSearchParams(window.location.search).get('node'));
