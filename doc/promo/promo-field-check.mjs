#!/usr/bin/env node
/**
 * 推广分析 · 字段数据检查（只读，不改任何数据）
 *
 * 逐个调用推广分析这次需求的接口，把每个指标 / 列 / 字段归成：
 *   有值 / 无数据（接口正常但值为空或全 0）/ 未接入（后端标 NO_SOURCE、PENDING_SOURCE 等）/
 *   口径待确认 / 不适用（NA、NO_SPEND）/ 接口报错
 * 输出 promo-field-report.md 与 promo-field-report.json（当前目录，或 OUT_DIR）。
 *
 * 用法（Node 18+，无依赖）：
 *   export PROMO_TOKEN='<TOKEN>'          # 登录后的 Bearer token，只从环境变量读，不写进文件、不打印
 *   BASE_URL=https://d3ajrxvlugsarb.cloudfront.net/prod-api SITE=SITE-IN node doc/promo/promo-field-check.mjs
 * 可选：TZ_OFFSET_MIN=330（站点时区，默认印度）、DAYS=7（概览 / 渠道表现取最近几天）、OUT_DIR=.
 */
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

const BASE = (process.env.BASE_URL || 'https://d3ajrxvlugsarb.cloudfront.net/prod-api').replace(/\/+$/, '');
const TOKEN = process.env.PROMO_TOKEN || process.env.TOKEN;
const SITE = process.env.SITE || 'SITE-IN';
const OFF = parseInt(process.env.TZ_OFFSET_MIN || '330', 10);
const DAYS = parseInt(process.env.DAYS || '7', 10);
const OUT = process.env.OUT_DIR || '.';
if (!TOKEN) {
  console.error('缺少环境变量 PROMO_TOKEN（登录后的 Bearer token）');
  process.exit(2);
}

// ---------------------------------------------------------------- 日期（站点时区）
const now = new Date(Date.now() + OFF * 60000);
const day = (n) => new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + n)).toISOString().slice(0, 10);
const T = { today: day(0), yesterday: day(-1), from: day(-DAYS), reg30: day(-30) };

// ---------------------------------------------------------------- 请求
async function call(method, path, body) {
  const t0 = Date.now();
  try {
    const res = await fetch(BASE + path, {
      method,
      headers: { Authorization: 'Bearer ' + TOKEN, 'X-Site-Code': SITE, 'Content-Type': 'application/json', 'Accept-Language': 'zh-TW' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const ct = res.headers.get('content-type') || '';
    const json = ct.includes('json') ? await res.json() : null;
    if (!json) await res.arrayBuffer();
    return { http: res.status, ct, json, ms: Date.now() - t0, summary: res.headers.get('export-summary') };
  } catch (e) {
    return { http: 0, error: String(e.message || e), ms: Date.now() - t0 };
  }
}
const okJson = (r) => r.json && r.json.code === 200;
const errText = (r) => r.error || (r.json ? `code=${r.json.code} ${r.json.msg || ''}` : `HTTP ${r.http}`);

// ---------------------------------------------------------------- 记录
const results = [];
const log = [];
function rec(area, field, status, detail = '') {
  results.push({ area, field, status, detail: String(detail || '').slice(0, 300) });
}
function step(name, r) {
  log.push({ name, http: r.http, code: r.json?.code ?? null, ms: r.ms, error: r.error || null });
  console.log(`${okJson(r) || (r.http === 200 && !r.json) ? 'OK ' : 'ERR'} ${String(r.ms).padStart(6)}ms  ${name}${okJson(r) ? '' : '  ← ' + errText(r)}`);
}
const empty = (v) => v === null || v === undefined || (Array.isArray(v) && v.length === 0);
const zero = (v) => v === 0 || v === '0' || v === '0.00';
/** 卡片 / 单元格（state + value + note）归类 */
function classify(state, value, note, prev) {
  if (state === 'NO_SOURCE') return /口徑待確認|口径待确认/.test(note || '') ? '口径待确认' : '未接入';
  if (state === 'PENDING') return '未到期';
  if (state === 'NA' || state === 'NO_SPEND') return '不适用';
  if (empty(value)) return '无数据';
  if (zero(value) && (empty(prev) || zero(prev))) return '无数据';
  return '有值';
}

// ---------------------------------------------------------------- 1. 选项
const opt = await call('GET', '/dashboard/promo/options');
step('推广分析选项 /dashboard/promo/options', opt);
const metricCodes = okJson(opt) ? opt.json.data.metrics.map((m) => m.code)
  : ['spend', 'budget', 'cpa', 'ftdCost', 'net', 'roi', 'lpUV', 'hUV', 'dl', 'act', 'reg', 'regR', 'ftd', 'ftdR', 'ftdAmt', 'rd'];
const metricName = Object.fromEntries((opt.json?.data?.metrics || []).map((m) => [m.code, m.name]));
const columns = okJson(opt) ? opt.json.data.columns : [];
const colName = Object.fromEntries(columns.map((c) => [c.code, c.name]));
if (okJson(opt)) {
  rec('选项', 'agents（推广商字典）', opt.json.data.agents?.length ? '有值' : '无数据', `${opt.json.data.agents?.length || 0} 个推广商`);
  rec('选项', 'terminals（终端字典）', opt.json.data.terminals?.length ? '有值' : '无数据', (opt.json.data.terminals || []).map((t) => t.code).join(','));
  rec('选项', 'siteLaunchDate', empty(opt.json.data.siteLaunchDate) ? '无数据' : '有值', opt.json.data.siteLaunchDate);
} else rec('选项', '/dashboard/promo/options', '接口报错', errText(opt));

// ---------------------------------------------------------------- 2. 概览指标卡（每次最多 10 张）
const ovBase = { scene: 'PROMO', slotFrom: `${T.from} 00:00`, slotTo: `${T.today} 00:00`, granularity: 'DAY', compareType: 'PREV_PERIOD', includeSeries: true };
for (let i = 0; i < metricCodes.length; i += 10) {
  const batch = metricCodes.slice(i, i + 10);
  const r = await call('POST', '/dashboard/metrics/overview', { ...ovBase, metrics: batch });
  step(`概览 ${batch.join(',')}`, r);
  if (!okJson(r)) { batch.forEach((c) => rec('概览指标卡', `${c} ${metricName[c] || ''}`, '接口报错', errText(r))); continue; }
  const blockErr = r.json.data.blockErrors?.METRICS;
  for (const c of r.json.data.blocks?.METRICS?.items || []) {
    const st = blockErr ? '接口报错' : classify(c.state, c.value, c.note, c.prevValue);
    const series = Array.isArray(c.series) ? `走势 ${c.series.filter((v) => !empty(v)).length}/${c.series.length} 点有值` : '';
    rec('概览指标卡', `${c.code} ${metricName[c.code] || c.label || ''}`, st,
      [c.state && `state=${c.state}`, c.note, `value=${c.value ?? 'null'} prev=${c.prevValue ?? 'null'}`, series].filter(Boolean).join('；'));
  }
  (r.json.data.notices || []).forEach((n) => rec('概览提示', n.code, '提示', `${n.title || ''} ${n.message || n.detail || ''}`));
}
// 变化归因：三个维度
for (const dim of ['terminal', 'agent', 'channel']) {
  for (let i = 0; i < metricCodes.length; i += 10) {
    const batch = metricCodes.slice(i, i + 10);
    const r = await call('POST', '/dashboard/metrics/overview', { ...ovBase, includeSeries: false, metrics: batch, attribution: { dimension: dim } });
    step(`概览归因 ${dim} ${batch.join(',')}`, r);
    if (!okJson(r)) { rec('变化归因', `${dim}`, '接口报错', errText(r)); continue; }
    for (const c of r.json.data.blocks?.METRICS?.items || []) {
      const a = c.attribution;
      if (!a) { rec(`变化归因·${dim}`, c.code, '无数据', '没有 attribution 结构（后端是旧版？）'); continue; }
      const st = !a.supported ? (a.reasonCode === 'NOT_CONFIGURED' ? '未配置' : a.reasonCode === 'PENDING_SOURCE' ? '未接入' : '不适用')
        : (a.rows?.length ? '有值' : '无数据');
      rec(`变化归因·${dim}`, `${c.code} ${metricName[c.code] || ''}`, st, a.supported ? `${a.rows?.length || 0} 个成员` : `${a.reasonCode}：${a.reason || ''}`);
    }
  }
}

// ---------------------------------------------------------------- 3. 渠道表现
const colCodes = columns.map((c) => c.code);
for (const rows of [['date', 'terminal'], ['agent', 'channel']]) {
  const r = await call('POST', '/dashboard/promo/channels/table', { slotFrom: `${T.from} 00:00`, slotTo: `${T.today} 00:00`, rows, columns: colCodes });
  step(`渠道表现 ${rows.join('›')}`, r);
  const area = `渠道表现·${rows.join('›')}`;
  if (!okJson(r)) { rec(area, '整表', '接口报错', errText(r)); continue; }
  const d = r.json.data;
  rec(area, '行数（第 1 层）', d.rows?.length ? '有值' : '无数据', `${d.rows?.length || 0} 行；dataPending=${d.dataPending}`);
  const cells = d.total?.cells || {};
  for (const code of colCodes) {
    const cell = cells[code] || {};
    const firstRow = d.rows?.[0]?.cells?.[code];
    rec(area, `${code} ${colName[code] || ''}`, classify(cell.state, cell.value, cell.note),
      [`合计 state=${cell.state ?? '-'} value=${cell.value ?? 'null'}`, cell.note, firstRow && `首行 state=${firstRow.state} value=${firstRow.value ?? 'null'}`].filter(Boolean).join('；'));
  }
}

// ---------------------------------------------------------------- 4. 获客漏斗、留存与回收
const fun = await call('POST', '/dashboard/promo/acquisition/funnel', { slotFrom: `${T.from} 00:00`, slotTo: `${T.today} 00:00` });
step('获客漏斗', fun);
if (okJson(fun)) {
  for (const x of fun.json.data.overall || []) rec('获客漏斗·整体', `${x.code} ${x.name || ''}`, classify(x.state, x.value, x.note, x.prev), `value=${x.value ?? 'null'}；${x.note || ''}`);
  for (const t of fun.json.data.terminals || []) {
    const num = (v) => (v && typeof v === 'object' ? v.value : v);
    rec('获客漏斗·终端', `${t.terminal} 注册 / 流失`, empty(num(t.reg)) ? '无数据' : '有值', `reg=${num(t.reg) ?? 'null'} lost=${num(t.lost) ?? 'null'}`);
    for (const s of t.steps || []) rec('获客漏斗·终端步骤', `${t.terminal} · ${s.code}`, classify(s.state, s.value, s.note), `value=${s.value ?? 'null'} rate=${s.rate ?? 'null'}；${s.note || ''}`);
  }
  rec('获客漏斗', 'regShare（各终端注册占比）', fun.json.data.regShare?.length ? '有值' : '无数据', JSON.stringify(fun.json.data.regShare || []).slice(0, 200));
} else rec('获客漏斗', '整页', '接口报错', errText(fun));

const coh = await call('POST', '/dashboard/promo/cohort/summary', { regFrom: T.reg30, regTo: T.today });
step('留存概要', coh);
if (okJson(coh)) for (const c of coh.json.data.cards || []) rec('留存与回收·概要', c.code, classify(c.state, c.value), `state=${c.state}`);
else rec('留存与回收', '概要', '接口报错', errText(coh));
for (const metric of ['regRet', 'ltv', 'roas']) {
  const r = await call('POST', '/dashboard/promo/cohort/batches', { regFrom: T.reg30, regTo: T.today, metric });
  step(`留存明细 ${metric}`, r);
  if (!okJson(r)) { rec('留存与回收·明细', metric, '接口报错', errText(r)); continue; }
  const vals = (r.json.data.rows || []).flatMap((x) => (x.values || x.cells || []));
  const has = vals.some((v) => !empty(v) && !empty(v.value ?? v));
  rec('留存与回收·明细', metric, has ? '有值' : (r.json.data.dataPending ? '未接入' : '无数据'), `${r.json.data.rows?.length || 0} 行；dataPending=${r.json.data.dataPending}`);
}

// ---------------------------------------------------------------- 5. 裂变层级
const fis = await call('POST', '/dashboard/promo/fission/agents', { slotFrom: `${T.from} 00:00`, slotTo: `${T.today} 00:00` });
step('裂变层级 顶层', fis);
if (okJson(fis)) {
  rec('裂变层级', '顶层一级代理清单', fis.json.data.rows?.length ? '有值' : (fis.json.data.dataPending ? '未接入' : '无数据'),
    `${fis.json.data.rows?.length || 0} 行；notices=${(fis.json.data.notices || []).map((n) => n.code).join(',')}`);
  const first = fis.json.data.rows?.[0];
  if (first) for (const k of Object.keys(first)) rec('裂变层级·行字段', k, empty(first[k]) ? '无数据' : '有值', `首行 ${k}=${first[k]}`);
} else rec('裂变层级', '顶层', '接口报错', errText(fis));

// ---------------------------------------------------------------- 6. 用户明细（终端 / 流失 / 新增 3 列）
const memOpt = await call('GET', '/dashboard/records/member/options');
step('用户明细选项', memOpt);
rec('用户明细', 'options.terminals / agents', okJson(memOpt) ? '有值' : '接口报错', okJson(memOpt) ? `${memOpt.json.data.terminals?.length} 个终端，${memOpt.json.data.agents?.length} 个推广商` : errText(memOpt));
const memBase = { registerTimeFrom: `${T.from} 00:00`, registerTimeTo: `${T.today} 00:00`, sortColumn: 'registerTime', sortDirection: 'desc', pageNum: 1, pageSize: 50 };
const memCases = [['默认（近 N 天注册）', {}], ['不限注册时间（看列）', { registerTimeFrom: null, registerTimeTo: null }], ['按终端 Web', { terminals: ['web'] }], ['流失 lost=true', { lost: true }], ['按推广商', { agents: [opt.json?.data?.agents?.[0]?.code || 'x'] }]];
for (const [name, extra] of memCases) {
  const r = await call('POST', '/dashboard/records/member', { ...memBase, ...extra });
  step(`用户明细 ${name}`, r);
  if (!okJson(r)) { rec('用户明细·筛选', name, '接口报错', errText(r)); continue; }
  const rows = r.json.data.page?.rows || [];
  rec('用户明细·筛选', name, rows.length ? '有值' : '无数据', `${rows.length} 行 / total=${r.json.data.page?.total}`);
  if (name.startsWith('不限') && rows.length) {
    const cols = (r.json.data.columns || []).map((c) => [c.code, c.label]);
    for (const [code, label] of cols) {
      const filled = rows.filter((x) => !empty(x[code])).length;
      rec('用户明细·列', `${code} ${label}`, filled ? '有值' : '无数据', `${filled}/${rows.length} 行有值`);
    }
  }
}

// ---------------------------------------------------------------- 7. 预算与消耗、投放日报
for (const kind of ['SPEND', 'BUDGET']) {
  const r = await call('GET', `/promo/budget/table?kind=${kind}&from=${T.from}&to=${T.yesterday}`);
  step(`预算与消耗 ${kind}`, r);
  if (!okJson(r)) { rec('预算与消耗', kind, '接口报错', errText(r)); continue; }
  const rows = r.json.data?.rows || r.json.data?.list || [];
  rec('预算与消耗', kind === 'SPEND' ? '消耗（UDS import_yata_promo_cost）' : '预算（UDS import_yata_promo_budget）', rows.length ? '有值' : '无数据', `${rows.length} 行（${T.from}~${T.yesterday}）`);
}
const dr = await call('POST', '/dashboard/promo/daily-report/export', { mode: 'yest' });
step('投放日报 昨日导出', dr);
rec('投放日报', '昨日导出', dr.http === 200 && !dr.json ? '有值' : '接口报错', dr.json ? errText(dr) : `export-summary=${dr.summary || '-'}`);

// ---------------------------------------------------------------- 8. 个人视图：本站不可用的可选项
const views = await call('GET', '/dashboard/views?page=PROMO');
step('个人视图 PROMO', views);
if (okJson(views)) for (const s of views.json.data.slots || []) {
  const na = (s.candidates || []).filter((c) => !c.available);
  rec('个人视图', `${s.slotCode}`, na.length ? '部分不可用' : '有值', na.length ? na.map((c) => `${c.code}(${c.reason || ''})`).join('、') : `groupAdjacent=${s.groupAdjacent}`);
} else rec('个人视图', '/dashboard/views', '接口报错', errText(views));

// ---------------------------------------------------------------- 输出
const order = ['接口报错', '未接入', '未配置', '口径待确认', '无数据', '未到期', '不适用', '部分不可用', '提示', '有值'];
const count = {};
results.forEach((x) => (count[x.status] = (count[x.status] || 0) + 1));
let md = `# 推广分析 · 字段数据检查\n\n- 环境：${BASE}（站点 ${SITE}）\n- 时间：${new Date().toISOString()}；取数区间 ${T.from} ~ ${T.today}（不含）\n- 汇总：${order.filter((k) => count[k]).map((k) => `${k} ${count[k]}`).join('，')}\n\n`;
for (const st of order.filter((k) => count[k] && k !== '有值')) {
  md += `## ${st}\n\n| 位置 | 字段 | 说明 |\n|---|---|---|\n`;
  results.filter((x) => x.status === st).forEach((x) => (md += `| ${x.area} | ${x.field} | ${x.detail.replace(/\|/g, '/').replace(/\n/g, ' ')} |\n`));
  md += '\n';
}
md += `## 有值\n\n${results.filter((x) => x.status === '有值').map((x) => `${x.area}：${x.field}`).join('；')}\n\n## 请求记录\n\n| 请求 | HTTP | code | 耗时 ms |\n|---|---|---|---|\n`;
log.forEach((l) => (md += `| ${l.name} | ${l.http} | ${l.code ?? ''} | ${l.ms} |\n`));
writeFileSync(join(OUT, 'promo-field-report.md'), md);
writeFileSync(join(OUT, 'promo-field-report.json'), JSON.stringify({ base: BASE, site: SITE, range: T, count, results, requests: log }, null, 2));
console.log('\n汇总：', count, '\n已写入', join(OUT, 'promo-field-report.md'), '与 .json');
