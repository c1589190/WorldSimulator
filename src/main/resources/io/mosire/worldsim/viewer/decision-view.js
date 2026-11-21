import { counterSummary, drawUnitCounters, layoutUnitCounters, STACK_ZOOM } from './military.js';

const $ = id => document.getElementById(id);
const labels = {port: '港口', resource: '资源', contact: '情报', unit: '部队'};
const colors = {port: '#7fceff', resource: '#ffca72'};
const detail = $('perspective-detail');
const canvas = $('perspective-map');
const ctx = canvas.getContext('2d');
let hitPoints = [];
let counterHits = [];
let selection;
let currentNode;

async function read(path, parameters = {}) {
  const url = new URL(path, location.href);
  for (const [key, value] of Object.entries(parameters)) url.searchParams.set(key, value);
  const response = await fetch(url);
  if (!response.ok) throw Error('无法读取该项 · ' + response.status);
  return response.json();
}
function text(tag, value) {
  const node = document.createElement(tag);
  node.textContent = value;
  return node;
}
function button(label, address) {
  const node = text('button', label);
  node.type = 'button';
  node.addEventListener('click', () => show(address, label));
  return node;
}
function documentBody(item) {
  const article = text('article', '');
  article.append(text('h3', item.title), text('p', item.text));
  if (item.evidence?.source) article.append(text('small', item.evidence.source));
  return article;
}
async function show(address, label) {
  const token = selection = {};
  try {
    const data = await read('/api/entity', {address});
    if (selection !== token) return;
    const item = data.entity || data;
    const sections = [text('h2', data.displayName || item.title || item.name || label)];
    if (item.text) sections.push(text('p', item.text));
    if (item.owner) sections.push(text('p', '发布者 · ' + item.owner + ' · ' + item.observedAt));
    if (item.suspectedUnit) sections.push(text('p', '疑似单位 · ' + item.suspectedUnit));
    if (item.commodity) sections.push(text('p', item.commodity + (item.quantity == null ? ' · 产量未知' : ' · ' + item.quantity + ' ' + item.unit)));
    if (item.operation) sections.push(text('p', ({open:'开通', closed:'关闭', active:'开采中', inactive:'停采'})[item.operation] || '状态未知'));
    if (item.evidence?.source) sections.push(text('small', '来源 · ' + item.evidence.source));
    if (data.documents) sections.push(...data.documents.map(documentBody));
    detail.replaceChildren(...sections);
  } catch (error) {
    if (selection === token) detail.replaceChildren(text('p', error.message));
  }
}

$('perspective-search').addEventListener('submit', async event => {
  event.preventDefault();
  const query = $('perspective-query').value.trim();
  if (!query) return;
  try {
    const result = await read('/api/search', {query});
    $('perspective-results').replaceChildren(...(result.matches.length
      ? result.matches.map(item => button(item.displayName, item.address))
      : [text('p', '未找到可见实体')]));
  } catch (error) { $('perspective-results').replaceChildren(text('p', error.message)); }
});
canvas.addEventListener('click', event => {
  const bounds = canvas.getBoundingClientRect();
  const x = (event.clientX - bounds.left) * canvas.width / bounds.width;
  const y = (event.clientY - bounds.top) * canvas.height / bounds.height;
  const counter = counterHits.findLast(hit => Math.abs(x - hit.x) <= hit.width / 2 && Math.abs(y - hit.y) <= hit.height / 2);
  if (counter) {
    if (counter.units.length === 1) {
      const [unit] = counter.units;
      show(unit.address, unit.name);
    } else {
      selection = null;
      const list = text('div', '');
      list.className = 'perspective-list';
      list.append(...counter.units.map(unit => button(labels[unit.kind] + ' · ' + unit.name, unit.address)));
      detail.replaceChildren(text('h2', counterSummary(counter.units)), list);
    }
    return;
  }
  const hit = hitPoints.findLast(point => Math.hypot(point.x - x, point.y - y) < 10);
  if (hit) show(hit.marker.address, hit.marker.name);
});

try {
  const [brief, markers, dimensions] = await Promise.all([read('/api/brief'), read('/api/markers'), read('/api/dimensions')]);
  currentNode = brief.turnAddress.split('/')[0];
  $('perspective-heading').textContent = brief.displayName;
  $('perspective-node').textContent = currentNode + ' · ' + brief.turnAddress.split('/').at(-1);
  $('perspective-markers').replaceChildren(...markers.map(item => button(labels[item.kind] + ' · ' + item.name, item.address)));
  $('perspective-documents').replaceChildren(...brief.documents.map(item => button(item.title, currentNode + '/info/document/' + item.id)));
  const image = new Image();
  image.src = '/api/terrain';
  await image.decode();
  canvas.height = Math.round(canvas.width * dimensions.height / dimensions.width);
  ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
  hitPoints = markers.filter(marker => colors[marker.kind]).map(marker => ({marker, x: marker.point.x / dimensions.width * canvas.width,
    y: marker.point.y / dimensions.height * canvas.height}));
  for (const {marker, x, y} of hitPoints) {
    ctx.beginPath();
    ctx.arc(x, y, 5, 0, Math.PI * 2);
    ctx.fillStyle = colors[marker.kind];
    ctx.fill();
    ctx.strokeStyle = '#182728';
    ctx.stroke();
  }
  counterHits = layoutUnitCounters(markers.filter(marker => marker.kind === 'unit' || marker.kind === 'contact'), {
    left: 0, top: 0, width: canvas.width, height: canvas.height, scale: canvas.width / dimensions.width, zoom: STACK_ZOOM
  });
  drawUnitCounters(ctx, counterHits, new Set());
} catch (error) { $('perspective-node').textContent = error.message; }
