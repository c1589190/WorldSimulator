export const COUNTER_HIDE_ZOOM = .9;
export const STACK_ZOOM = 3.5;

const counterWidth = 50;
const counterHeight = 32;
const counterGap = 8;
const battleAccent = '#efad85';
const contactAccent = '#e7a6ff';
const ghostPadding = 5;
const ghostSprites = new Map();
const noSelection = new Set();
let ghostScratch;
const number = new Intl.NumberFormat('zh-CN');
const centered = [[0, 0]];
const offsets = [...centered];
for (let row = -2; row <= 2; row++) {
  for (let column = -2; column <= 2; column++) {
    if (row || column) offsets.push([column, row]);
  }
}
offsets.sort((a, b) => Math.hypot(...a) - Math.hypot(...b));

const echelons = new Map([
  ['team', ['组', '·']], ['squad', ['班', '··']], ['section', ['分队', '··']],
  ['platoon', ['排', '···']], ['company', ['连', 'I']], ['battery', ['炮兵连', 'I']],
  ['battalion', ['营', 'II']], ['regiment', ['团', 'III']], ['brigade', ['旅', 'X']],
  ['division', ['师', 'XX']], ['corps', ['军', 'XXX']], ['army', ['集团军', 'XXXX']],
  ['army-group', ['集团军群', 'XXXXX']], ['fleet', ['舰队', '']], ['wing', ['联队', '']]
]);
const types = new Map([
  ['infantry', ['步兵', 'infantry']], ['light-infantry', ['轻步兵', 'light-infantry']],
  ['mountaineer', ['山地步兵', 'light-infantry']],
  ['motorized', ['摩托化步兵', 'motorized']], ['mechanized', ['机械化步兵', 'motorized']],
  ['cavalry', ['骑兵', 'cavalry']], ['armored', ['装甲', 'armored']], ['armour', ['装甲', 'armored']],
  ['armor', ['装甲', 'armored']], ['tank', ['装甲', 'armored']], ['artillery', ['炮兵', 'artillery']],
  ['engineer', ['工兵', 'engineer']], ['recon', ['侦察', 'cavalry']],
  ['headquarters', ['司令部', 'headquarters']], ['hq', ['司令部', 'headquarters']]
]);

function normalized(value) {
  return value?.trim().toLowerCase().replace(/[ _]+/g, '-');
}

function identityLine(unit) {
  if (unit.kind === 'battle') return '战斗记录';
  if (unit.kind === 'contact') return '情报报告';
  return [echelons.get(normalized(unit.echelon))?.[0] || unit.echelon,
    types.get(normalized(unit.type))?.[0] || unit.type].filter(Boolean).join(' · ');
}

function countBattles(markers) {
  return markers.filter(marker => marker.kind === 'battle').length;
}

function countContacts(markers) {
  return markers.filter(marker => marker.kind === 'contact').length;
}

export function counterSummary(markers) {
  const battles = countBattles(markers);
  const contacts = countContacts(markers);
  const units = markers.length - battles - contacts;
  return [units && number.format(units) + ' 个部署单位',
    contacts && number.format(contacts) + ' 份情报',
    battles && number.format(battles) + ' 场战斗'].filter(Boolean).join(' · ');
}

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

export function layoutUnitCounters(units, { left, top, width, height, scale, zoom, avoid = [] }) {
  const hits = [];
  if (zoom < COUNTER_HIDE_ZOOM || width < 30 || height < 30) return hits;
  const cardWidth = Math.min(counterWidth, width - 8);
  const cardHeight = Math.min(counterHeight, height - 8);
  const halfWidth = cardWidth / 2;
  const halfHeight = cardHeight / 2;
  const stepX = cardWidth + counterGap;
  const stepY = cardHeight + counterGap;
  const bottom = height > 160 ? 58 : 4;
  const expanded = zoom >= STACK_ZOOM;
  const markers = expanded ? units.toSorted((a, b) => Number(b.kind === 'battle') - Number(a.kind === 'battle')) : units;
  const spread = expanded || avoid.length ? offsets : centered;
  const occupied = new Map(avoid.map(hit => [Math.floor(hit.x / stepX) + '/' + Math.floor(hit.y / stepY), hit]));
  const reserved = new Set(avoid);
  const stackedProvinces = new Map();

  function nearby(x, y, column, row) {
    let closest;
    let gap = Infinity;
    for (let dy = -1; dy <= 1; dy++) {
      for (let dx = -1; dx <= 1; dx++) {
        const hit = occupied.get((column + dx) + '/' + (row + dy));
        if (!hit || Math.abs(hit.x - x) >= stepX || Math.abs(hit.y - y) >= stepY) continue;
        const distance = Math.hypot(hit.x - x, hit.y - y);
        if (distance < gap) {
          closest = hit;
          gap = distance;
        }
      }
    }
    return closest;
  }

  for (const unit of markers) {
    const anchorX = left + unit.point.x * scale;
    const anchorY = top + unit.point.y * scale;
    if (anchorX < 0 || anchorX > width || anchorY < 0 || anchorY > height) continue;
    const stacked = stackedProvinces.get(unit.provinceAddress);
    if (stacked) {
      stacked.units.push(unit);
      continue;
    }
    let covered;
    let unitStack;
    let placed = false;
    for (const [dx, dy] of spread) {
      const x = clamp(anchorX + dx * (stepX + 1), halfWidth + 4, width - halfWidth - 4);
      const y = clamp(anchorY + dy * (stepY + 1), halfHeight + 4, height - halfHeight - bottom);
      const column = Math.floor(x / stepX);
      const row = Math.floor(y / stepY);
      const overlap = nearby(x, y, column, row);
      if (overlap) {
        if (reserved.has(overlap)) continue;
        covered ??= overlap;
        if (expanded && unit.kind !== 'battle' && overlap.units[0].kind !== 'battle') unitStack ??= overlap;
        if (!expanded) break;
        continue;
      }
      const hit = { x, y, anchorX, anchorY, width: cardWidth, height: cardHeight, units: [unit] };
      occupied.set(column + '/' + row, hit);
      hits.push(hit);
      placed = true;
      break;
    }
    if (!placed && covered) {
      const stack = unitStack || covered;
      stack.units.push(unit);
      if (unit.provinceAddress) stackedProvinces.set(unit.provinceAddress, stack);
    } else if (!placed && avoid.length) {
      // Keep the deployment behind foreground markers when there is no space to displace it.
      hits.push({ x: anchorX, y: anchorY, anchorX, anchorY, width: cardWidth, height: cardHeight, units: [unit] });
    }
  }
  return hits;
}

function nationColor(value) {
  return /^#[0-9a-f]{6}$/i.test(value) ? value : '#91a891';
}

function symbol(ctx, x, y, size, type, ink) {
  const icon = types.get(normalized(type))?.[1];
  ctx.save();
  ctx.translate(x, y);
  ctx.scale(size / 24, size / 24);
  ctx.strokeStyle = ink;
  ctx.fillStyle = ink;
  ctx.lineWidth = 1.5;
  ctx.strokeRect(-10, -6, 20, 12);
  ctx.beginPath();
  switch (icon) {
    case 'infantry':
    case 'light-infantry':
    case 'motorized':
      ctx.moveTo(-10, -6);
      ctx.lineTo(10, 6);
      ctx.moveTo(-10, 6);
      ctx.lineTo(10, -6);
      if (icon === 'light-infantry') {
        ctx.moveTo(0, -6);
        ctx.lineTo(0, 6);
      }
      if (icon === 'motorized') {
        ctx.moveTo(-6, 9);
        ctx.lineTo(6, 9);
      }
      break;
    case 'cavalry':
      ctx.moveTo(-10, 6);
      ctx.lineTo(10, -6);
      break;
    case 'artillery':
      ctx.arc(0, 0, 2.7, 0, Math.PI * 2);
      ctx.fill();
      break;
    case 'armored':
      ctx.ellipse(0, 0, 6.6, 3.2, 0, 0, Math.PI * 2);
      break;
    case 'engineer':
      ctx.moveTo(-6, 3);
      ctx.lineTo(-6, -2);
      ctx.lineTo(6, -2);
      ctx.lineTo(6, 3);
      break;
    default:
      ctx.font = '700 8px system-ui';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(type === 'uncertain' ? '?' : icon === 'headquarters' ? 'HQ' : '·', 0, 0, 18);
  }
  ctx.stroke();
  ctx.restore();
}

function battleSymbol(ctx, x, y, size) {
  ctx.save();
  ctx.translate(x, y);
  ctx.scale(size / 16, size / 16);
  ctx.strokeStyle = battleAccent;
  ctx.lineCap = 'round';
  ctx.lineWidth = 1.8;
  ctx.beginPath();
  ctx.moveTo(-5.5, -5.5);
  ctx.lineTo(6.5, 6.5);
  ctx.moveTo(5.5, -5.5);
  ctx.lineTo(-6.5, 6.5);
  ctx.moveTo(2, 6);
  ctx.lineTo(6, 2);
  ctx.moveTo(-2, 6);
  ctx.lineTo(-6, 2);
  ctx.stroke();
  ctx.restore();
}

function drawCounterLeaders(ctx, hits) {
  for (const hit of hits) {
    if (Math.hypot(hit.x - hit.anchorX, hit.y - hit.anchorY) < 8) continue;
    ctx.beginPath();
    ctx.moveTo(hit.anchorX, hit.anchorY);
    ctx.lineTo(hit.x, hit.y);
    ctx.strokeStyle = '#142728c5';
    ctx.lineWidth = 3;
    ctx.stroke();
    ctx.strokeStyle = '#d9dfc8';
    ctx.lineWidth = 1;
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(hit.anchorX, hit.anchorY, 2.6, 0, 2 * Math.PI);
    ctx.fillStyle = '#f4e3b3';
    ctx.fill();
  }
}

function counterCountryColor(units) {
  const [front] = units;
  return nationColor(units.every(unit => unit.countryId === front.countryId) ? front.countryColor : null);
}

export function drawUnitCounters(ctx, hits, selected) {
  ctx.save();
  drawCounterLeaders(ctx, hits);
  for (const hit of hits) {
    const { x, y, units, width, height } = hit;
    const front = units[0];
    const stack = units.length > 1;
    const battles = countBattles(units);
    const contacts = countContacts(units);
    const deployed = units.length - battles - contacts;
    const reportsOnly = contacts === units.length;
    const active = selected.size > 0 && units.some(unit => selected.has(unit.address));
    const accent = reportsOnly ? contactAccent : battles ? battleAccent : counterCountryColor(units);
    const cardX = x - width / 2;
    const cardY = y - height / 2;
    const ink = active ? '#ffe5a9' : '#edf2df';
    ctx.fillStyle = '#0f1d21c9';
    ctx.fillRect(cardX - 1, cardY + 2, width + 3, height + 2);
    if (stack) {
      ctx.fillStyle = accent;
      ctx.fillRect(cardX + 6, cardY - 3, width - 10, 3);
    }
    ctx.fillStyle = reportsOnly ? '#372c45' : battles ? (active ? '#5e3931' : '#472e2a') : (active ? '#3d4634' : '#233933');
    ctx.fillRect(cardX, cardY, width, height);
    ctx.fillStyle = accent;
    ctx.fillRect(cardX + 1, cardY + 1, 4, height - 2);
    ctx.strokeStyle = active ? '#ffd082' : reportsOnly ? contactAccent : battles ? '#d6a18b' : '#bcc9b6';
    ctx.lineWidth = active ? 2 : 1;
    if (reportsOnly) ctx.setLineDash([4, 2]);
    ctx.strokeRect(cardX + .5, cardY + .5, width - 1, height - 1);
    if (reportsOnly) ctx.setLineDash([]);

    ctx.textBaseline = 'middle';
    ctx.textAlign = 'center';
    ctx.fillStyle = ink;
    if (contacts) {
      if (reportsOnly) {
        symbol(ctx, stack ? cardX + width * .32 : x, y, Math.min(24, width * .48), 'uncertain', contactAccent);
        if (stack) {
          ctx.font = '750 15px system-ui';
          ctx.fillStyle = contactAccent;
          ctx.fillText(number.format(contacts), cardX + width * .73, y, width * .45);
        }
      } else {
        const kinds = [[deployed, '□', ink], [contacts, '?', contactAccent], [battles, '×', battleAccent]]
          .filter(([count]) => count > 0);
        ctx.font = '700 9px system-ui';
        kinds.forEach(([count, glyph, color], index) => {
          ctx.fillStyle = color;
          ctx.fillText(glyph + ' ' + number.format(count), x, y + (index - (kinds.length - 1) / 2) * 9, width - 10);
        });
      }
    } else if (battles) {
      const glyphX = cardX + width * .32;
      const labelX = cardX + width * .72;
      const labelWidth = width * .47;
      if (deployed) {
        ctx.strokeStyle = ink;
        ctx.lineWidth = 1;
        ctx.strokeRect(glyphX - 5.5, y - 11, 10, 6);
        ctx.strokeRect(glyphX - 3.5, y - 9, 10, 6);
        ctx.font = '700 12px system-ui';
        ctx.fillText(number.format(deployed), labelX, y - 6, labelWidth);
      }
      battleSymbol(ctx, glyphX, y + (deployed ? 7 : 0), deployed ? 12 : 16);
      ctx.fillStyle = battleAccent;
      ctx.font = '700 12px system-ui';
      ctx.fillText(stack ? number.format(battles) : '战斗', labelX, y + (deployed ? 7 : 0), labelWidth);
    } else if (stack) {
      const stackX = cardX + width * .31;
      ctx.strokeStyle = ink;
      ctx.lineWidth = 1.15;
      ctx.strokeRect(stackX - 7, y - 6, 14, 10);
      ctx.strokeRect(stackX - 4, y - 3, 14, 10);
      ctx.font = '750 16px system-ui';
      ctx.fillText(number.format(units.length), cardX + width * .72, y + 1, width * .44);
    } else {
      const glyphX = front.countryId ? cardX + width * .33 : x;
      symbol(ctx, glyphX, y + 4, Math.min(24, width * .48), front.type, ink);
      const echelon = echelons.get(normalized(front.echelon))?.[1];
      ctx.font = '700 9px system-ui';
      if (echelon) ctx.fillText(echelon, glyphX, y - 8, width * .45);
      if (front.countryId) {
        ctx.fillStyle = '#142824';
        ctx.fillRect(cardX + width * .57, y - 1, width * .38, 11);
        ctx.fillStyle = ink;
        ctx.fillText(front.countryId, cardX + width * .76, y + 5, width * .33);
      }
    }
  }
  ctx.restore();
}

function ghostSprite(hit, density) {
  const { width, height, units } = hit;
  const key = JSON.stringify([density, width, height, units.length, counterCountryColor(units),
    units.length === 1 ? [units[0].countryId, normalized(units[0].type), normalized(units[0].echelon)] : null]);
  const cached = ghostSprites.get(key);
  if (cached) {
    ghostSprites.delete(key);
    ghostSprites.set(key, cached);
    return cached;
  }
  ghostScratch ??= document.createElement('canvas');
  ghostScratch.width = Math.ceil((width + 2 * ghostPadding) * density);
  ghostScratch.height = Math.ceil((height + 2 * ghostPadding) * density);
  const ctx = ghostScratch.getContext('2d');
  ctx.scale(density, density);
  const x = width / 2 + ghostPadding;
  const y = height / 2 + ghostPadding;
  drawUnitCounters(ctx, [{ x, y, anchorX: x, anchorY: y, width, height, units }], noSelection);

  // Filter a small sprite once, rather than filtering each primitive on the map on every frame.
  const sprite = document.createElement('canvas');
  sprite.width = ghostScratch.width;
  sprite.height = ghostScratch.height;
  const raster = sprite.getContext('2d');
  raster.filter = `grayscale(1) blur(${.6 * density}px)`;
  raster.drawImage(ghostScratch, 0, 0);
  ghostSprites.set(key, sprite);
  if (ghostSprites.size > 128) ghostSprites.delete(ghostSprites.keys().next().value);
  return sprite;
}

export function drawGhostCounters(ctx, hits, density) {
  ctx.save();
  ctx.globalAlpha = .30;
  drawCounterLeaders(ctx, hits);
  for (const hit of hits) {
    const sprite = ghostSprite(hit, density);
    ctx.drawImage(sprite, hit.x - hit.width / 2 - ghostPadding, hit.y - hit.height / 2 - ghostPadding,
      sprite.width / density, sprite.height / density);
  }
  ctx.restore();
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function action(label, onClick, className) {
  const button = element('button', className, label);
  button.type = 'button';
  button.addEventListener('click', onClick);
  return button;
}

function badge(unit) {
  const frame = element('span', 'military-badge');
  frame.style.setProperty('--nation', unit.kind === 'contact' ? contactAccent : unit.kind === 'battle' ? battleAccent : nationColor(unit.countryColor));
  const canvas = element('canvas');
  canvas.width = 84;
  canvas.height = 60;
  canvas.setAttribute('aria-hidden', 'true');
  const ctx = canvas.getContext('2d');
  if (unit.kind === 'battle') battleSymbol(ctx, 42, 30, 40);
  else if (unit.kind === 'contact') symbol(ctx, 42, 30, 49, 'uncertain', contactAccent);
  else symbol(ctx, 42, 30, 49, unit.type, '#ebeed8');
  frame.append(canvas);
  return frame;
}

function quantityText(quantity) {
  if (quantity?.value == null) return quantity?.status === 'legacy-ambiguous' ? '待核对' : '未知';
  return number.format(quantity.value) + (quantity.status === 'estimated' ? '（估计）' : '');
}

function differenceText(quantity) {
  return (quantity.delta > 0 ? '+' : '') + number.format(quantity.delta) + (quantity.estimated ? '（估计）' : '');
}

function formationDetails(node, evidence) {
  const content = document.createDocumentFragment();
  const inventory = node.equipment;
  const coverage = { complete: '完整', partial: '部分', unknown: '未知' }[inventory?.coverage] || '未知';
  const strength = node.personnel?.value == null ? quantityText(node.personnel) : quantityText(node.personnel) + ' 人';
  content.append(element('h3', '', node.name));
  const identity = identityLine(node);
  if (identity) content.append(element('p', 'formation-subtitle', identity));
  const personnel = element('section', 'formation-facts');
  personnel.append(element('h4', '', '直属人员'), element('p', 'formation-strength', strength));
  if (node.personnel?.source) personnel.append(evidence(node.personnel));
  const equipment = element('section', 'formation-facts');
  equipment.append(element('h4', '', '直属装备'), element('p', 'formation-subtitle', '覆盖 · ' + coverage));
  if (inventory?.items?.length) {
    const items = element('ul', 'orbat-equipment');
    for (const item of inventory.items) {
      const entry = element('li');
      entry.append(element('span', '', item.name || item.itemId),
        element('span', '', quantityText(item.quantity)));
      if (item.quantity?.source) entry.title = item.quantity.source;
      items.append(entry);
    }
    equipment.append(items);
  }
  if (inventory?.evidence) equipment.append(evidence(inventory.evidence));
  content.append(personnel, equipment);
  return content;
}

function smallFormation(root) {
  const nodes = [root];
  for (let index = 0; index < nodes.length; index++) {
    for (const child of nodes[index].children || []) {
      if (nodes.length >= 80) return false;
      nodes.push(child);
    }
  }
  return true;
}

function formationTree(root, viewport, select) {
  const expandAll = smallFormation(root);
  function branch(node, expanded) {
    const item = element('li', 'formation-branch');
    const box = element('div', 'formation-card');
    const pick = action('', () => select(node, pick), 'formation-pick');
    const label = element('span', 'formation-card-name');
    label.append(element('strong', '', node.name));
    const identity = identityLine(node);
    if (identity) label.append(element('small', '', identity));
    pick.setAttribute('aria-pressed', 'false');
    pick.title = node.name;
    pick.append(badge(node), label);
    box.append(pick);
    item.append(box);
    if (node.children?.length) {
      const children = element('ul', 'formation-children');
      const toggle = action('', () => {
        const before = box.getBoundingClientRect();
        setExpanded(children.hidden);
        const after = box.getBoundingClientRect();
        viewport.scrollLeft += after.left - before.left;
        viewport.scrollTop += after.top - before.top;
      }, 'formation-toggle');
      function setExpanded(open) {
        if (open && !children.childElementCount) {
          for (const child of node.children) children.append(branch(child, expandAll));
        }
        if (!open && children.querySelector('[aria-pressed="true"]')) select(node, pick);
        children.hidden = !open;
        toggle.textContent = open ? '−' : '+';
        toggle.setAttribute('aria-expanded', String(open));
        toggle.setAttribute('aria-label', (open ? '折叠' : '展开') + ' ' + node.name + ' 的下属单位');
      }
      box.append(toggle);
      item.append(children);
      setExpanded(expanded);
    }
    return item;
  }
  const tree = element('ul', 'formation-tree');
  tree.append(branch(root, true));
  return tree;
}

export function showFormation(dialog, { result, actual, ideal }, evidence) {
  const heading = element('header', 'formation-heading');
  const title = element('h2', '', result.displayName + ' · 编制图');
  title.id = 'formation-title';
  const close = action('×', () => dialog.close(), 'quiet');
  close.setAttribute('aria-label', '关闭编制图');
  close.autofocus = true;
  heading.append(title, close);

  const toolbar = element('div', 'formation-toolbar');
  const tabs = element('div', 'formation-tabs');
  tabs.setAttribute('role', 'group');
  tabs.setAttribute('aria-label', '编制口径');
  const zoomControls = element('div', 'formation-zoom');
  zoomControls.setAttribute('role', 'group');
  zoomControls.setAttribute('aria-label', '编制图缩放');
  const size = element('span', 'formation-scale', '100%');
  const smaller = action('−', () => zoomChart(scale / 1.25));
  smaller.setAttribute('aria-label', '缩小编制图');
  const larger = action('+', () => zoomChart(scale * 1.25));
  larger.setAttribute('aria-label', '放大编制图');
  const fit = action('适应窗口', fitChart);
  zoomControls.append(smaller, size, larger, fit);
  toolbar.append(tabs, zoomControls);

  const workspace = element('div', 'formation-workspace');
  const viewport = element('div', 'formation-viewport');
  viewport.tabIndex = 0;
  viewport.setAttribute('role', 'region');
  viewport.setAttribute('aria-label', '编制结构图');
  const inspector = element('aside', 'formation-inspector');
  inspector.setAttribute('aria-label', '编制节点详情');
  workspace.append(viewport, inspector);

  let chart;
  let selected;
  let drag;
  let scale = 1;
  const roles = [
    { name: '实际编制', tree: actual },
    { name: '理想编制', tree: ideal }
  ];
  const buttons = roles.map(({ name }, index) => {
    const button = action(name, () => activate(index));
    tabs.append(button);
    return button;
  });

  function select(node, button) {
    selected?.setAttribute('aria-pressed', 'false');
    selected = button;
    button.setAttribute('aria-pressed', 'true');
    inspector.replaceChildren(formationDetails(node, evidence));
    inspector.scrollTop = 0;
  }
  function zoomChart(next, anchorX, anchorY) {
    if (!chart) return;
    next = clamp(next, .5, 1.75);
    if (next === scale) return;
    const bounds = viewport.getBoundingClientRect();
    const x = anchorX ?? bounds.left + viewport.clientWidth / 2;
    const y = anchorY ?? bounds.top + viewport.clientHeight / 2;
    const before = chart.getBoundingClientRect();
    const factor = next / scale;
    scale = next;
    chart.style.zoom = String(scale);
    const after = chart.getBoundingClientRect();
    viewport.scrollLeft += after.left + (x - before.left) * factor - x;
    viewport.scrollTop += after.top + (y - before.top) * factor - y;
    size.textContent = number.format(Math.round(scale * 100)) + '%';
  }
  function fitChart() {
    if (!chart) return;
    const bounds = chart.getBoundingClientRect();
    const factor = Math.min((viewport.clientWidth - 48) / bounds.width,
      (viewport.clientHeight - 48) / bounds.height);
    zoomChart(clamp(scale * factor, .65, 1));
    viewport.scrollTop = 0;
    const rootCard = chart.querySelector('.formation-pick').getBoundingClientRect();
    viewport.scrollLeft += rootCard.left + rootCard.width / 2
      - viewport.getBoundingClientRect().left - viewport.clientWidth / 2;
  }
  function activate(index) {
    buttons.forEach((button, position) => button.setAttribute('aria-pressed', String(position === index)));
    const { name, tree } = roles[index];
    scale = 1;
    size.textContent = '100%';
    selected = null;
    chart = tree ? formationTree(tree, viewport, select) : null;
    inspector.hidden = !chart;
    workspace.classList.toggle('formation-empty', !chart);
    viewport.replaceChildren(chart || element('p', 'formation-message', '暂无' + name + '记录'));
    for (const button of [smaller, larger, fit]) button.disabled = !chart;
    if (chart) {
      select(tree, chart.querySelector('.formation-pick'));
      fitChart();
    } else {
      inspector.replaceChildren();
    }
  }

  viewport.addEventListener('wheel', (event) => {
    if (!event.ctrlKey || !chart) return;
    event.preventDefault();
    zoomChart(scale * Math.exp(clamp(-event.deltaY * .003, -.5, .5)), event.clientX, event.clientY);
  }, { passive: false });
  viewport.addEventListener('pointerdown', (event) => {
    if (event.pointerType !== 'mouse' || event.button !== 0 || !chart || event.target.closest('button')) return;
    if (viewport.scrollWidth <= viewport.clientWidth && viewport.scrollHeight <= viewport.clientHeight) return;
    drag = { x: event.clientX, y: event.clientY, left: viewport.scrollLeft, top: viewport.scrollTop };
    viewport.setPointerCapture(event.pointerId);
    viewport.classList.add('dragging');
  });
  viewport.addEventListener('pointermove', (event) => {
    if (!drag) return;
    viewport.scrollLeft = drag.left + drag.x - event.clientX;
    viewport.scrollTop = drag.top + drag.y - event.clientY;
  });
  viewport.addEventListener('lostpointercapture', () => {
    drag = null;
    viewport.classList.remove('dragging');
  });

  dialog.replaceChildren(heading, toolbar, workspace);
  if (!dialog.open) dialog.showModal();
  activate(actual || !ideal ? 0 : 1);
}

function rosterView(roster, actions) {
  const fragment = document.createDocumentFragment();
  const heading = element('section', 'military-hero');
  const caption = element('div', 'military-hero-copy');
  const sameProvince = roster.units.every(unit => unit.provinceAddress === roster.units[0].provinceAddress);
  const specialMarkers = roster.units.some(unit => unit.kind === 'battle' || unit.kind === 'contact');
  caption.append(element('h3', '', specialMarkers ? counterSummary(roster.units) : '部署单位'),
    element('p', '', sameProvince ? roster.units[0].provinceName || '同省' : '邻近地点'));
  if (!specialMarkers) heading.append(element('span', 'military-badge military-count', number.format(roster.units.length)));
  heading.append(caption);
  const controls = element('div', 'military-actions');
  controls.append(action('放大展开', () => actions.locate(roster.point), 'military-primary'));
  const list = element('ul', 'military-roster');
  for (const unit of roster.units) {
    const row = element('li');
    const button = action('', () => actions.selectUnit(unit.address), 'military-roster-row');
    const label = element('span', 'military-roster-copy');
    label.append(element('strong', '', unit.name), element('small', '', [
      identityLine(unit), unit.countryId, sameProvince ? null : unit.provinceName
    ].filter(Boolean).join(' · ')));
    button.append(badge(unit), label, element('span', 'military-chevron', '›'));
    row.append(button);
    list.append(row);
  }
  fragment.append(heading, controls, list);
  return fragment;
}

export function renderMilitary(content, state, actions) {
  const { result, actual, ideal, roster } = state;
  if (state.message) {
    const message = element('p', 'military-empty', state.message);
    message.setAttribute('role', 'status');
    content.replaceChildren(message);
    if (roster) content.append(action('‹ 返回名单', actions.backToRoster));
    return;
  }
  if (!result) {
    content.replaceChildren(rosterView(roster, actions));
    return;
  }

  const unit = result.kind === 'unit' ? result.entity : null;
  const identity = {
    type: unit?.type?.trim() || actual?.type?.trim() || ideal?.type,
    echelon: actual?.echelon?.trim() || ideal?.echelon,
    countryColor: result.countryColor
  };
  const hero = element('section', 'military-hero');
  const caption = element('div', 'military-hero-copy');
  caption.append(element('h3', '', result.displayName),
    element('p', '', [result.countryName || unit?.countryId, identityLine(identity)]
      .filter(Boolean).join(' · ') || '编制'));
  hero.append(badge(identity), caption);
  const controls = element('div', 'military-actions');
  if (roster) controls.append(action('‹ 返回名单', actions.backToRoster));
  if (result.point) controls.append(action('定位兵牌', () => actions.locate(result.point)));
  if (result.provinceAddress) controls.append(action(result.provinceName || '部署省',
    () => actions.selectProvince(result.provinceAddress)));
  if (unit && !result.provinceAddress) controls.append(element('span', 'military-empty', '部署位置未知'));
  const children = [hero];
  if (controls.childNodes.length) children.push(controls);
  const formation = action('查看编制图', actions.openFormation, 'military-primary formation-open');
  formation.setAttribute('aria-haspopup', 'dialog');
  children.push(formation);

  const difference = result.directStrengthDifference;
  if (difference) {
    const summary = element('details', 'orbat-differences');
    summary.append(element('summary', '', '直属差额 · 实际减理想'));
    if (difference.personnel) summary.append(element('p', '', '人员 ' + differenceText(difference.personnel)));
    for (const item of difference.equipment || []) {
      summary.append(element('p', '', (item.name || item.itemId) + ' ' + differenceText(item.quantity)));
    }
    children.push(summary);
  }

  const sources = element('details', 'military-sources');
  sources.append(element('summary', '', unit ? '来源与地址' : '节点地址'));
  if (unit) sources.append(actions.evidence(unit.deploymentEvidence || unit.evidence));
  sources.append(element('p', 'entity-address', result.address));
  if (result.countryAddress) sources.append(action('查看所属国家', () => actions.selectCountry(result.countryAddress)));
  children.push(sources);
  content.replaceChildren(...children);
}
