const number = new Intl.NumberFormat('zh-CN');

function element(tag, value, className) {
  const node = document.createElement(tag);
  if (value != null) node.textContent = value;
  if (className) node.className = className;
  return node;
}

function fact(list, label, value) {
  const row = element('div');
  row.append(element('dt', label), element('dd', value ?? '未知'));
  list.append(row);
}

const treatyNames = { war: '战争', ceasefire: '停战', alliance: '同盟', transit: '通行', agreement: '协定' };
const treatyStates = { active: '生效', expired: '到期', scheduled: '待生效', unknown: '状态未知' };
const dateLabel = (date) => date?.replace('T', ' ') || '日期未知';

export function renderCountry(container, result, { link, evidence }) {
  const country = result.entity;
  const title = element('h3', result.displayName);
  const facts = element('dl', null, 'panel-facts');
  if (result.capitalAddress) {
    const row = element('div');
    const value = element('dd');
    value.append(link('定位首都', result.capitalAddress));
    row.append(element('dt', '首都'), value);
    facts.append(row);
  }
  fact(facts, '已记归属', number.format(result.ownedProvinces) + ' 省');
  fact(facts, '已记实控', result.controlledProvinces > 0 ? number.format(result.controlledProvinces) + ' 省' : '暂无记录');
  const politics = result.politics;
  fact(facts, '政体', politics?.regime);
  fact(facts, '政府', politics?.government);
  fact(facts, '动员', politics?.mobilization);
  if (result.manpower) fact(facts, '国家人力池', number.format(result.manpower.available));
  const rail = result.railCapacity;
  if (rail?.country.remainingKg != null) fact(facts, '本期可用铁路运力', number.format(rail.country.remainingKg) + ' kg');
  const sections = [title, facts];
  if (politics) sections.push(evidence(politics.evidence));
  if (result.manpower) {
    const details = element('details', null, 'panel-detail');
    details.append(element('summary', '人力与铁路资料'), evidence(result.manpower.evidence));
    if (rail?.country.evidence) details.append(evidence(rail.country.evidence));
    sections.push(details);
  } else if (rail?.country.evidence) {
    sections.push(evidence(rail.country.evidence));
  }
  if (result.treaties?.length) {
    const diplomacy = element('details', null, 'panel-detail');
    diplomacy.append(element('summary', '外交条约'));
    for (const { treaty, status } of result.treaties) {
      const row = element('article', null, 'panel-row');
      const other = treaty.first === country.id ? treaty.second : treaty.first;
      row.append(element('strong', (treatyNames[treaty.type] || treaty.type) + ' · ' + (treatyStates[status] || status)),
        link(other, result.address.split('/')[0] + '/country/' + other, false),
        element('small', dateLabel(treaty.startsAt) + (treaty.endsAt ? ' 至 ' + dateLabel(treaty.endsAt) : '')),
        element('p', treaty.terms, 'panel-prose'), evidence(treaty.evidence));
      diplomacy.append(row);
    }
    sections.push(diplomacy);
  }
  if (result.intelligenceDocuments?.length) {
    const documents = element('details', null, 'panel-detail');
    documents.append(element('summary', '情报文档'));
    for (const item of result.intelligenceDocuments) documents.append(link(item.title, item.address, false));
    sections.push(documents);
  }
  sections.push(evidence(country.evidence), element('p', result.address, 'entity-address'));
  container.replaceChildren(...sections);
}

const decisionStatuses = { pending: '待提交', received: '已收件', resolved: '已归档' };
const dispositions = { accepted: '采纳', modified: '修改后采纳', rejected: '驳回', deferred: '暂缓' };

function action(label, run, className) {
  const button = element('button', label, className);
  button.type = 'button';
  button.addEventListener('click', run);
  return button;
}

function rulingCard(ruling) {
  const card = element('article', null, 'record-ruling');
  const heading = element('header', null, 'record-card-heading');
  heading.append(element('h4', ruling.title));
  if (ruling.disposition) heading.append(element('span', dispositions[ruling.disposition], 'record-chip ' + ruling.disposition));
  card.append(heading, element('div', ruling.text, 'record-text'));
  return card;
}

export function renderDecisions(container, data, { link, showNode, showRound, decisionMakerId, fromRound }) {
  const content = [];
  let selectedDecision;
  for (const item of data.decisions) {
    const row = element('section', null, 'record-entry');
    const heading = element('header', null, 'record-card-heading');
    heading.append(element('h3', item.displayName), element('span', decisionStatuses[item.status], 'record-chip'));
    row.append(heading);
    if (item.receiptNode) {
      const receipt = element('div', null, 'record-meta');
      if (item.receivedAt != null) receipt.append(element('span', '实际收件 ' + new Date(item.receivedAt).toLocaleString('zh-CN')));
      receipt.append(action('查看收件节点', () => showNode(item.receiptNode), 'entity-link'));
      row.append(receipt);
    }
    if (item.proposal) {
      row.append(element('h4', '决策原文'), element('div', item.proposal.text, 'record-text'),
        element('h4', '理由'), element('div', item.proposal.rationale, 'record-text'));
      for (const move of item.proposal.movements || []) {
        row.append(element('p', '调动 · ' + move.unitId + ' → ' + [...(move.via || []), move.destinationId].join(' → '), 'record-meta'));
      }
      for (const report of item.proposal.reports || []) {
        row.append(element('p', report.contact ? '情报上报 · ' + report.contact.title : '撤回情报 · ' + report.id, 'record-meta'));
      }
      if (item.targetAddresses.length) {
        const links = element('div', null, 'panel-targets');
        links.append(element('span', '提案目标'));
        for (const address of item.targetAddresses) links.append(link(address, address));
        row.append(links);
      }
      row.append(element('h4', 'GM 批示'));
      if (item.rulings.length) row.append(...item.rulings.map(rulingCard));
      else row.append(element('p', data.resolvedAddress ? '无逐条批示记录。' : '等待 GM 裁定。', 'record-meta'));
    } else {
      row.append(element('p', '此节点尚无提交记录。', 'record-meta'));
    }
    if (item.id === decisionMakerId) selectedDecision = row;
    content.push(row);
  }
  if (!content.length) content.push(element('p', data.openingAddress ? '此回合未为该实体分配决策人。' : '此节点暂无回合。', 'record-meta'));
  if (data.openingAddress) content.unshift(action(fromRound ? '返回裁定列表' : '查看本回合全部裁定', showRound, 'record-nav'));
  container.replaceChildren(...content);
  selectedDecision?.scrollIntoView({block: 'start'});
}

export function renderRoundRulings(container, data, showDecision) {
  if (!data.openingAddress || !data.resolvedAddress) {
    container.replaceChildren(element('p', data.openingAddress ? '回合尚未发布裁定。' : '此节点暂无回合。', 'record-meta'));
    return;
  }
  const summary = element('section', null, 'record-entry');
  summary.append(element('h3', 'GM 总说明'), element('div', data.resolution, 'record-text'));
  if (!data.rulings.length) {
    container.replaceChildren(summary, element('p', '本回合只有总说明，无逐条裁定记录。', 'record-meta'));
    return;
  }
  const filter = element('input');
  filter.type = 'search';
  filter.placeholder = '按标题、决策人或正文筛选';
  filter.setAttribute('aria-label', '筛选裁定');
  const count = element('span', null, 'record-meta');
  const tools = element('div', null, 'record-filter');
  tools.append(filter, count);
  const list = element('ol', null, 'round-ruling-list');
  function showItems() {
    const term = filter.value.trim().toLocaleLowerCase();
    const matches = data.rulings.filter(({entry, displayName}) =>
      [entry.title, entry.text, displayName, dispositions[entry.disposition]].some(value => value?.toLocaleLowerCase().includes(term)));
    count.textContent = matches.length + ' / ' + data.rulings.length + ' 条';
    list.replaceChildren(...matches.map(({entry, subjectAddress, displayName}) => {
      const row = element('li');
      const card = rulingCard(entry);
      if (subjectAddress) card.append(action((displayName || '决策人') + ' · 查看原文',
        () => showDecision(subjectAddress, entry.decisionMakerId), 'entity-link'));
      row.append(card);
      return row;
    }));
  }
  filter.addEventListener('input', showItems);
  container.replaceChildren(summary, tools, list);
  showItems();
}

const censusBasis = { resident: '常住人口', census: '普查人口', present: '在场人口' };

export function renderPopulation(container, records, evidence) {
  if (!records.length) {
    container.replaceChildren(element('p', '人口未知', 'entity-source'));
    return;
  }
  const value = element('p');
  const source = element('div');
  const select = records.length > 1 ? element('select') : null;
  if (select) {
    select.setAttribute('aria-label', '选择人口资料日期和统计口径');
    select.replaceChildren(...records.map((record, index) => new Option(
      record.date + ' · ' + (censusBasis[record.basis] || record.basis), index)));
    select.addEventListener('change', show);
  }
  function show() {
    const record = records[select ? Number(select.value) : 0];
    value.textContent = number.format(record.people) + ' 人 · ' + record.date + ' · ' + (censusBasis[record.basis] || record.basis);
    source.replaceChildren(evidence(record.evidence));
  }
  container.replaceChildren(...(select ? [select, value, source] : [value, source]));
  show();
}

const railStatus = { open: '开通', closed: '停运', unknown: '运营状态未知' };
const riverStatus = { 'confirmed-crossing': '确认跨河', 'confirmed-clear': '确认不跨河',
  candidate: '河流参考候选', 'none-in-reference': '参考图未标记', unknown: '跨河情况未知' };

export function renderProvinceLogistics(container, data, { link, evidence }) {
  const sections = [];
  if (data.railways.length) {
    const list = element('ul', null, 'panel-list');
    for (const track of data.railways) {
      const row = element('li');
      row.append(link(track.id, track.address, false), element('p',
        '等级 ' + track.level + ' · ' + (railStatus[track.status] || track.status)
        + (track.tonnesPerDay == null ? '' : ' · ' + number.format(track.tonnesPerDay) + ' 吨／日')));
      if (track.evidence) row.append(evidence(track.evidence));
      list.append(row);
    }
    sections.push(element('h4', '铁路'), list);
  }
  if (data.hubs.length) {
    const list = element('ul', null, 'panel-list');
    for (const {address, hub} of data.hubs) {
      const row = element('li');
      row.append(link(hub.id, address, false), element('p', '等级 ' + hub.level));
      list.append(row);
    }
    sections.push(element('h4', '补给中心'), list);
  }
  if (data.borders.length) {
    const list = element('ul', null, 'panel-list');
    for (const border of data.borders) {
      const row = element('li');
      row.append(link(border.name, border.address, false),
        element('p', riverStatus[border.river.status] || border.river.status, 'entity-source'));
      if (border.river.evidence) row.append(evidence(border.river.evidence));
      else if (border.river.source) row.append(element('p', border.river.source, 'entity-source'));
      list.append(row);
    }
    sections.push(element('h4', '相邻陆地边界'), list);
  }
  container.replaceChildren(...(sections.length ? sections : [element('p', '暂无铁路、枢纽或陆地边界资料。', 'entity-source')]));
}
