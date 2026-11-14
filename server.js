/**
 * SpendWise – Node.js / SQLite server
 * Drop-in replacement for the Java backend.
 * Same REST URLs, same JSON shape, same frontend.
 * Uses Node 22+ built-in `node:sqlite` (no npm install needed).
 */

'use strict';

const http = require('node:http');
const fs   = require('node:fs');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');

// ─────────────────────────── Database setup ───────────────────────────

const DB_FILE = path.join(__dirname, 'spendwise.db');
const db = new DatabaseSync(DB_FILE);

// Enable foreign keys and WAL for speed
db.exec('PRAGMA foreign_keys = ON;');
db.exec('PRAGMA journal_mode = WAL;');

db.exec(`
CREATE TABLE IF NOT EXISTS expenses (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  amount REAL NOT NULL CHECK(amount > 0),
  category TEXT NOT NULL,
  description TEXT NOT NULL,
  expense_date TEXT NOT NULL,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_expense_date     ON expenses(expense_date);
CREATE INDEX IF NOT EXISTS idx_expense_category ON expenses(category);

CREATE TABLE IF NOT EXISTS income (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  amount REAL NOT NULL CHECK(amount > 0),
  source TEXT NOT NULL,
  description TEXT,
  income_date TEXT NOT NULL,
  created_at TEXT DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_income_date ON income(income_date);

CREATE TABLE IF NOT EXISTS groups_tbl (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS group_members (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_id INTEGER NOT NULL REFERENCES groups_tbl(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  UNIQUE(group_id, name)
);

CREATE TABLE IF NOT EXISTS shared_expenses (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_id INTEGER NOT NULL REFERENCES groups_tbl(id) ON DELETE CASCADE,
  paid_by INTEGER NOT NULL REFERENCES group_members(id) ON DELETE CASCADE,
  amount REAL NOT NULL CHECK(amount > 0),
  description TEXT NOT NULL,
  split_type TEXT NOT NULL DEFAULT 'equal',
  expense_date TEXT NOT NULL,
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS expense_participants (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  shared_expense_id INTEGER NOT NULL REFERENCES shared_expenses(id) ON DELETE CASCADE,
  member_id INTEGER NOT NULL REFERENCES group_members(id) ON DELETE CASCADE,
  share_amount REAL NOT NULL CHECK(share_amount >= 0),
  UNIQUE(shared_expense_id, member_id)
);

CREATE TABLE IF NOT EXISTS settlements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  group_id INTEGER NOT NULL REFERENCES groups_tbl(id) ON DELETE CASCADE,
  from_member INTEGER NOT NULL REFERENCES group_members(id) ON DELETE CASCADE,
  to_member INTEGER NOT NULL REFERENCES group_members(id) ON DELETE CASCADE,
  amount REAL NOT NULL CHECK(amount > 0),
  settled_on TEXT NOT NULL,
  request_token TEXT NOT NULL UNIQUE,
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS budgets (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  budget_month TEXT NOT NULL,
  category TEXT NOT NULL DEFAULT 'Overall',
  amount REAL NOT NULL CHECK(amount > 0),
  UNIQUE(budget_month, category)
);

CREATE TABLE IF NOT EXISTS savings_goals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  target_amount REAL NOT NULL CHECK(target_amount > 0),
  deadline TEXT,
  created_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS savings_contributions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  goal_id INTEGER NOT NULL REFERENCES savings_goals(id) ON DELETE CASCADE,
  amount REAL NOT NULL CHECK(amount > 0),
  contributed_on TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS challenges (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  challenge_type TEXT NOT NULL,
  start_date TEXT NOT NULL,
  end_date TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT DEFAULT (datetime('now'))
);
`);

// ─────────────────────────── DB helpers ───────────────────────────────

function query(sql, ...args) {
  try {
    return db.prepare(sql).all(...args);
  } catch (e) {
    throw apiError(500, e.message);
  }
}
function one(sql, ...args) {
  try {
    return db.prepare(sql).get(...args) || null;
  } catch (e) {
    throw apiError(500, e.message);
  }
}
function run(sql, ...args) {
  try {
    return db.prepare(sql).run(...args);
  } catch (e) {
    throw apiError(500, e.message);
  }
}
function insertId(sql, ...args) {
  return run(sql, ...args).lastInsertRowid;
}
function tx(fn) {
  const wrap = db.transaction(fn);
  return wrap();
}

// ─────────────────────────── Date helpers ─────────────────────────────

function today() { return new Date().toISOString().slice(0, 10); }
function curMonth() { return new Date().toISOString().slice(0, 7); }
function prevMonth() {
  const d = new Date();
  d.setDate(1); d.setMonth(d.getMonth() - 1);
  return d.toISOString().slice(0, 7);
}
function monthStart(m) { return m + '-01'; }
function monthEnd(m) {
  const [y, mo] = m.split('-').map(Number);
  return new Date(y, mo, 0).toISOString().slice(0, 10);
}
function validDate(s) { return /^\d{4}-\d{2}-\d{2}$/.test(s) && !isNaN(Date.parse(s)); }
function validMonth(s) { return /^\d{4}-\d{2}$/.test(s); }

// Last N days as 'YYYY-MM-DD' strings
function lastNDays(n) {
  const days = [];
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(); d.setDate(d.getDate() - i);
    days.push(d.toISOString().slice(0, 10));
  }
  return days;
}

function lastNMonths(n) {
  const months = [];
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - i);
    months.push(d.toISOString().slice(0, 7));
  }
  return months;
}

// ─────────────────────────── Error helper ─────────────────────────────

function apiError(status, message) {
  const e = new Error(message);
  e.status = status;
  return e;
}
function fail(status, message) { throw apiError(status, message); }

// ─────────────────────────── Static files ─────────────────────────────

const FRONTEND = path.join(__dirname, 'frontend');
const MIME = {
  '.html': 'text/html',
  '.css':  'text/css',
  '.js':   'application/javascript',
  '.json': 'application/json',
  '.png':  'image/png',
  '.ico':  'image/x-icon',
  '.svg':  'image/svg+xml',
};

// ─────────────────────────── Split logic ──────────────────────────────

function toPaise(rupees) { return Math.round(rupees * 100); }
function toRupees(paise) { return paise / 100; }

function equalSplit(totalPaise, memberIds) {
  const base = Math.floor(totalPaise / memberIds.length);
  let leftover = totalPaise - base * memberIds.length;
  return memberIds.map(id => ({ memberId: id, paise: base + (leftover-- > 0 ? 1 : 0) }));
}

function simplifyDebts(balances) {
  const creditors = balances.filter(b => b.net > 0).map(b => ({ ...b }));
  const debtors   = balances.filter(b => b.net < 0).map(b => ({ ...b, net: -b.net }));
  creditors.sort((a, b) => b.net - a.net);
  debtors.sort((a, b) => b.net - a.net);
  const debts = [];
  let ci = 0, di = 0;
  while (ci < creditors.length && di < debtors.length) {
    const cr = creditors[ci], dr = debtors[di];
    const amt = Math.min(cr.net, dr.net);
    debts.push({ from: dr.id, from_name: dr.name, to: cr.id, to_name: cr.name, amount: toRupees(amt) });
    cr.net -= amt; dr.net -= amt;
    if (cr.net === 0) ci++;
    if (dr.net === 0) di++;
  }
  return debts;
}

function groupBalances(groupId) {
  const rows = query(
    `SELECT m.id, m.name,
       COALESCE((SELECT SUM(amount) FROM shared_expenses WHERE paid_by = m.id), 0) AS paid,
       COALESCE((SELECT SUM(share_amount) FROM expense_participants WHERE member_id = m.id), 0) AS owed,
       COALESCE((SELECT SUM(amount) FROM settlements WHERE from_member = m.id), 0) AS settled_paid,
       COALESCE((SELECT SUM(amount) FROM settlements WHERE to_member = m.id), 0) AS settled_received
     FROM group_members m WHERE m.group_id = ? ORDER BY m.id`, groupId);
  return rows.map(r => ({
    id: r.id, name: r.name, paid: r.paid, owed: r.owed,
    net: toPaise(r.paid) - toPaise(r.owed) + toPaise(r.settled_paid) - toPaise(r.settled_received)
  }));
}

// ─────────────────────────── Insight logic ────────────────────────────

function clamp(n, lo, hi) { return Math.min(Math.max(n, lo), hi); }
function round1(n) { return Math.round(n * 10) / 10; }
function round2(n) { return Math.round(n * 100) / 100; }

const DISCRETIONARY = {
  Food: 'cook or carry lunch two more days a week',
  Shopping: 'wait 48 hours before any non-essential purchase',
  Entertainment: 'keep one paid outing or subscription a week',
  Others: 'review these entries and re-categorise or cut them',
};
const DAYS = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat'];

function computeInsights() {
  const cur = curMonth(), prev = prevMonth();
  const catRows = query(
    `SELECT strftime('%Y-%m', expense_date) AS month, category, SUM(amount) AS total, COUNT(*) AS entries
     FROM expenses WHERE expense_date >= ? GROUP BY month, category`,
    monthStart(prev));
  const thisM = {}, lastM = {};
  for (const r of catRows) {
    if (r.month === cur)  thisM[r.category] = r.total;
    if (r.month === prev) lastM[r.category] = r.total;
  }
  const thisTotal = Object.values(thisM).reduce((a,b)=>a+b,0);
  const lastTotal = Object.values(lastM).reduce((a,b)=>a+b,0);
  const change = lastTotal > 0 ? round1((thisTotal - lastTotal) / lastTotal * 100) : null;

  let topCat = null, topVal = 0;
  for (const [k,v] of Object.entries(thisM)) if (v > topVal) { topCat = k; topVal = v; }

  const allCats = [...new Set([...Object.keys(thisM), ...Object.keys(lastM)])];
  const comparison = allCats.map(c => {
    const t = thisM[c] || 0, l = lastM[c] || 0;
    const unusual = t - l >= 200 && (l === 0 ? t >= 500 : (t - l) / l >= 0.3);
    return { category: c, this_month: t, last_month: l,
             increase_pct: l !== 0 ? round1((t-l)/l*100) : null, unusual };
  }).sort((a,b) => b.this_month - a.this_month);
  const unusualList = comparison.filter(c => c.unusual);

  const freq = query(
    `SELECT category, COUNT(*) AS entries, SUM(amount) AS total FROM expenses
     WHERE expense_date >= date('now','-90 days') GROUP BY category ORDER BY entries DESC, total DESC`);
  const wd = query(
    `SELECT cast(strftime('%w', expense_date) AS INTEGER) + 1 AS dow, SUM(amount) AS total
     FROM expenses WHERE expense_date >= date('now','-90 days') GROUP BY dow`);
  const money = one(
    `SELECT
       (SELECT COALESCE(SUM(amount),0) FROM income WHERE income_date BETWEEN ? AND ?) AS income,
       (SELECT COALESCE(SUM(amount),0) FROM expenses WHERE expense_date BETWEEN ? AND ?) AS expenses`,
    monthStart(cur), monthEnd(cur), monthStart(cur), monthEnd(cur));

  const income = money.income, expenses = money.expenses;
  const savingsRate = income > 0 ? (income - expenses) / income * 100 : null;

  const budgets = query(
    `SELECT b.id, b.category, b.amount,
       COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.expense_date BETWEEN ? AND ?
         AND (b.category='Overall' OR e.category=b.category)), 0) AS spent
     FROM budgets b WHERE b.budget_month = ?`, monthStart(cur), monthEnd(cur), cur);
  const budget = budgets.find(b => b.category === 'Overall') || null;

  const goals = query(
    `SELECT g.id, g.name, g.target_amount, COALESCE(SUM(c.amount),0) AS saved
     FROM savings_goals g LEFT JOIN savings_contributions c ON c.goal_id = g.id GROUP BY g.id`);

  // Suggestions
  const suggestions = [];
  if (topCat) suggestions.push(`${topCat} is your biggest category this month: ${round1(topVal/thisTotal*100)}% of your spending.`);
  for (const u of unusualList) {
    const inc = u.increase_pct;
    suggestions.push(`${u.category} jumped ${inc==null?'from nothing':inc+'%'} (₹${u.last_month} to ₹${u.this_month}). Try keeping it near last month's level.`);
  }
  if (change !== null && change > 0) suggestions.push(`Spending so far this month is ${change}% above all of last month. Slow down before month end.`);
  if (savingsRate !== null && savingsRate < 20) suggestions.push(`You are saving ${round1(savingsRate)}% of this month's income. A common target is 20%.`);
  if (!budget) suggestions.push('You have no overall budget for this month. Set one on the Budgets page.');
  else if (budget.spent >= budget.amount * 0.8)
    suggestions.push(`You have used ${round1(budget.spent/budget.amount*100)}% of your monthly budget.`);
  if (freq.length) suggestions.push(`${freq[0].category} is your most frequent category (${freq[0].entries} entries in 90 days). Small repeat purchases add up.`);
  if (!suggestions.length) suggestions.push('Add some expenses and income to get personalised suggestions.');

  // Recommendations
  const recommendations = [];
  let potential = 0;
  const base = thisTotal > 0 ? thisM : lastM;
  for (const [cat, tip] of Object.entries(DISCRETIONARY)) {
    const cur2 = base[cat] || 0;
    if (cur2 > 0) {
      const cut = Math.round(cur2 * 0.15);
      recommendations.push({ category: cat, current: cur2, suggested_cut: cut, tip });
      potential += cut;
    }
  }

  // Health score
  const pct = budget ? budget.spent / budget.amount * 100 : null;
  const bp = pct == null ? 20 : pct <= 80 ? 40 : pct <= 100 ? 40-(pct-80) : pct <= 130 ? 20-(pct-100)*2/3 : 0;
  const rateP = savingsRate == null ? 0 : clamp(savingsRate/20,0,1)*20;
  let goalAvg = null;
  if (goals.length) {
    let s = 0;
    for (const g of goals) s += Math.min(g.saved / g.target_amount, 1);
    goalAvg = s / goals.length;
  }
  const goalP = goalAvg == null ? 5 : goalAvg * 10;
  const pp = change == null ? 20 : clamp(30*(1-Math.max(change,0)/50),0,30);
  const patP = Math.max(pp - unusualList.length*5, 0);
  const scoreTotal = Math.round(bp + rateP + goalP + patP);
  const label = scoreTotal >= 80 ? 'Excellent' : scoreTotal >= 60 ? 'Good' : scoreTotal >= 40 ? 'Fair' : 'Needs attention';
  const breakdown = [
    { name: 'Budget usage', points: round1(bp), max: 40, reason: pct==null?'No budget set, so you get a neutral 20.':`${round1(pct)}% of budget used.` },
    { name: 'Savings progress', points: round1(rateP+goalP), max: 30, reason: `Savings rate ${savingsRate==null?'unknown':round1(savingsRate)+'%'}; ${goalAvg==null?'no goals yet':round1(goalAvg*100)+'% average goal progress'}.` },
    { name: 'Spending pattern', points: round1(patP), max: 30, reason: `${change==null?'No last month to compare':change+'% vs last month'}; minus 5 for each unusual category (${unusualList.length}).` },
  ];

  const weekday = DAYS.map((day, i) => {
    const w = wd.find(r => r.dow === i+1);
    return { day, total: w ? w.total : 0 };
  });

  return {
    top: topCat ? { category: topCat, total: topVal } : null,
    this_total: thisTotal, last_total: lastTotal, change,
    comparison, unusual: unusualList, frequency: freq, weekday,
    suggestions, recommendations, potential_saving: potential,
    score: { total: scoreTotal, label, breakdown },
  };
}

// ─────────────────────────── PDF builder ──────────────────────────────
// Minimal single-page PDF (Helvetica, no external library)

function buildPdf(month) {
  const ms = monthStart(month), me = monthEnd(month);
  const totals = one(
    `SELECT COALESCE((SELECT SUM(amount) FROM income WHERE income_date BETWEEN ? AND ?),0) AS income,
            COALESCE((SELECT SUM(amount) FROM expenses WHERE expense_date BETWEEN ? AND ?),0) AS expenses`,
    ms, me, ms, me);
  const cats = query(
    `SELECT category, SUM(amount) AS total, COUNT(*) AS entries FROM expenses
     WHERE expense_date BETWEEN ? AND ? GROUP BY category ORDER BY total DESC`, ms, me);
  const g = one(`SELECT COALESCE(SUM(amount),0) AS saved FROM savings_contributions WHERE contributed_on BETWEEN ? AND ?`, ms, me);
  const b = one(`SELECT amount FROM budgets WHERE budget_month=? AND category='Overall'`, month);

  const rs = n => {
    const abs = Math.abs(n).toFixed(2);
    const [ip, dec] = abs.split('.');
    let grouped = ip;
    if (ip.length > 3) {
      const last3 = ip.slice(-3), rest = ip.slice(0, -3);
      let r = '';
      for (let i = rest.length; i > 0; i -= 2) r = ',' + rest.slice(Math.max(0,i-2),i) + r;
      grouped = r.replace(/^,/, '') + ',' + last3;
    }
    return 'Rs. ' + (n < 0 ? '-' : '') + grouped + '.' + dec;
  };

  // Build PDF content stream
  const lines = [];
  const pushText = (x, y, size, bold, text) => {
    lines.push(`BT /${bold?'F2':'F1'} ${size} Tf ${x} ${y} Td (${text.replace(/[()\\]/g,'\\$&')}) Tj ET`);
  };

  const pageH = 792;
  let y = pageH - 50;
  pushText(50, y, 22, true, 'SpendWise Monthly Report'); y -= 32;
  const label = new Date(month+'-01').toLocaleString('en-US',{month:'long',year:'numeric'}); 
  pushText(50, y, 12, false, label); y -= 36;
  pushText(50, y, 14, false, 'Summary'); y -= 24;
  const sumLines = [
    'Total income: '+rs(totals.income),
    'Total expenses: '+rs(totals.expenses),
    'Net savings: '+rs(totals.income - totals.expenses),
    'Added to savings goals: '+rs(g.saved),
    'Monthly budget: '+(b ? rs(b.amount)+' ('+Math.round(totals.expenses/b.amount*100)+'% used)' : 'Not set'),
  ];
  for (const l of sumLines) { pushText(50, y, 11, false, l); y -= 16; }
  y -= 20;
  pushText(50, y, 14, false, 'Expenses by category'); y -= 24;
  if (!cats.length) { pushText(50, y, 11, false, 'No expenses recorded this month.'); }
  for (const c of cats) {
    pushText(50, y, 11, false, c.category);
    pushText(200, y, 11, false, c.entries+' entries');
    pushText(300, y, 11, false, rs(c.total));
    if (totals.expenses) pushText(430, y, 11, false, (c.total/totals.expenses*100).toFixed(1)+'%');
    y -= 16;
  }
  y -= 40;
  pushText(50, y, 9, false, 'Generated by SpendWise on '+today());

  const content = lines.join('\n');
  const resources = '<< /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> /F2 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >> >> >>';
  const objs = [];
  objs.push('1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj');
  objs.push('2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj');
  objs.push(`3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources ${resources} >>\nendobj`);
  const stream = Buffer.from(content, 'latin1');
  objs.push(`4 0 obj\n<< /Length ${stream.length} >>\nstream\n${content}\nendstream\nendobj`);
  let pdf = '%PDF-1.4\n';
  const offsets = [];
  for (const obj of objs) { offsets.push(pdf.length); pdf += obj + '\n'; }
  const xrefOffset = pdf.length;
  pdf += `xref\n0 ${objs.length+1}\n0000000000 65535 f \n`;
  for (const o of offsets) pdf += String(o).padStart(10,'0')+' 00000 n \n';
  pdf += `trailer\n<< /Size ${objs.length+1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF`;
  return Buffer.from(pdf, 'latin1');
}

// ─────────────────────────── HTTP router ──────────────────────────────

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', chunk => { data += chunk; if (data.length > 1e6) req.destroy(); });
    req.on('end', () => {
      try { resolve(data ? JSON.parse(data) : {}); }
      catch { reject(apiError(400, 'Invalid JSON')); }
    });
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

function sendErr(res, e) {
  const status = e.status || 500;
  sendJson(res, status, { success: false, message: e.message || 'Internal server error' });
}

function getQuery(url) {
  const q = {};
  const u = new URL(url, 'http://x');
  u.searchParams.forEach((v, k) => { q[k] = v; });
  return q;
}

// Match /api/groups/:id/members  → params = {id}
function matchRoute(pattern, pathname) {
  const pp = pattern.split('/'), up = pathname.split('/');
  if (pp.length !== up.length) return null;
  const params = {};
  for (let i = 0; i < pp.length; i++) {
    if (pp[i].startsWith(':')) { params[pp[i].slice(1)] = up[i]; }
    else if (pp[i] !== up[i]) return null;
  }
  return params;
}

const routes = [];
function route(method, pattern, handler) {
  routes.push({ method, pattern, handler });
}

async function handleRequest(req, res) {
  const url = req.url;
  const pathname = url.split('?')[0];
  const method   = req.method.toUpperCase();

  // ── API routes ──
  if (pathname.startsWith('/api/')) {
    for (const r of routes) {
      if (r.method !== method) continue;
      const params = matchRoute(r.pattern, pathname);
      if (!params) continue;
      const query2 = getQuery(url);
      let body = {};
      if (['POST','PUT','PATCH'].includes(method)) {
        try { body = await readBody(req); } catch (e) { return sendErr(res, e); }
      }
      try {
        const result = await r.handler({ params, query: query2, body });
        const status = result._status || 200;
        delete result._status;
        sendJson(res, status, result);
      } catch (e) { sendErr(res, e); }
      return;
    }
    return sendErr(res, apiError(404, 'Route not found'));
  }

  // ── Static files ──
  let filePath = pathname === '/' ? '/index.html' : pathname;
  filePath = path.join(FRONTEND, filePath);
  fs.readFile(filePath, (err, data) => {
    if (err) {
      // Try index.html as fallback
      fs.readFile(path.join(FRONTEND, 'index.html'), (e2, d2) => {
        if (e2) { res.writeHead(404); res.end('Not found'); return; }
        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end(d2);
      });
      return;
    }
    const ext = path.extname(filePath);
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
    res.end(data);
  });
}

// ─────────────────────────── Route definitions ────────────────────────

// Health
route('GET', '/api/health', () => ({ success: true, database: 'connected' }));

// ── Expenses ──
const EXPENSE_TYPES = ['Food','Shopping','Transportation','Education','Entertainment','Health','Bills','Others'];
const INCOME_TYPES  = ['Salary','Pocket Money','Freelance','Scholarship','Gift','Others'];

function validateRecord(b, types, descRequired) {
  const amount = parseFloat(b.amount);
  if (!isFinite(amount) || amount <= 0 || amount > 99999999) return 'Amount must be a positive number.';
  if (!types.includes(b.type)) return `Type must be one of: ${types.join(', ')}.`;
  const desc = (b.description || '').trim();
  if (descRequired && !desc) return 'Description is required.';
  if (desc.length > 255) return 'Description is too long (max 255).';
  if (!validDate(b.date)) return 'Please choose a valid date.';
  return null;
}

function listRecords(table, typeCol, dateCol, q) {
  let sql = `SELECT * FROM ${table} WHERE 1=1`;
  const params = [];
  if (q.search) { sql += ` AND (description LIKE ? OR ${typeCol} LIKE ?)`; params.push('%'+q.search+'%','%'+q.search+'%'); }
  if (q.type)   { sql += ` AND ${typeCol} = ?`; params.push(q.type); }
  if (q.from)   { sql += ` AND ${dateCol} >= ?`; params.push(q.from); }
  if (q.to)     { sql += ` AND ${dateCol} <= ?`; params.push(q.to); }
  sql += ` ORDER BY ${dateCol} DESC, id DESC`;
  const data = query(sql, ...params);
  const total = data.reduce((s,r) => s + r.amount, 0);
  return { success: true, total: round2(total), count: data.length, data };
}

function monthlyRecords(table, dateCol) {
  return { success: true, data: query(
    `SELECT strftime('%Y-%m',${dateCol}) AS month, SUM(amount) AS total, COUNT(*) AS entries
     FROM ${table} GROUP BY month ORDER BY month DESC`) };
}

// Expenses
route('GET',    '/api/expenses',         ({ query: q }) => listRecords('expenses','category','expense_date', q));
route('GET',    '/api/expenses/monthly', () => monthlyRecords('expenses','expense_date'));
route('POST',   '/api/expenses',         ({ body: b }) => {
  const err = validateRecord(b, EXPENSE_TYPES, true);
  if (err) fail(400, err);
  const id = insertId('INSERT INTO expenses (amount,category,description,expense_date) VALUES (?,?,?,?)',
    parseFloat(b.amount), b.type, (b.description||'').trim(), b.date);
  return { success: true, id, message: 'Saved successfully.', _status: 201 };
});
route('PUT',    '/api/expenses/:id',     ({ params, body: b }) => {
  const err = validateRecord(b, EXPENSE_TYPES, true);
  if (err) fail(400, err);
  const r = run('UPDATE expenses SET amount=?,category=?,description=?,expense_date=? WHERE id=?',
    parseFloat(b.amount), b.type, (b.description||'').trim(), b.date, params.id);
  if (!r.changes) fail(404, 'Record not found.');
  return { success: true, message: 'Updated successfully.' };
});
route('DELETE', '/api/expenses/:id',     ({ params }) => {
  if (!run('DELETE FROM expenses WHERE id=?', params.id).changes) fail(404, 'Record not found.');
  return { success: true, message: 'Deleted.' };
});

// Income
route('GET',    '/api/income',           ({ query: q }) => listRecords('income','source','income_date', q));
route('GET',    '/api/income/monthly',   () => monthlyRecords('income','income_date'));
route('POST',   '/api/income',           ({ body: b }) => {
  const err = validateRecord(b, INCOME_TYPES, false);
  if (err) fail(400, err);
  const id = insertId('INSERT INTO income (amount,source,description,income_date) VALUES (?,?,?,?)',
    parseFloat(b.amount), b.type, (b.description||'').trim(), b.date);
  return { success: true, id, message: 'Saved successfully.', _status: 201 };
});
route('PUT',    '/api/income/:id',       ({ params, body: b }) => {
  const err = validateRecord(b, INCOME_TYPES, false);
  if (err) fail(400, err);
  const r = run('UPDATE income SET amount=?,source=?,description=?,income_date=? WHERE id=?',
    parseFloat(b.amount), b.type, (b.description||'').trim(), b.date, params.id);
  if (!r.changes) fail(404, 'Record not found.');
  return { success: true, message: 'Updated successfully.' };
});
route('DELETE', '/api/income/:id',       ({ params }) => {
  if (!run('DELETE FROM income WHERE id=?', params.id).changes) fail(404, 'Record not found.');
  return { success: true, message: 'Deleted.' };
});

// ── Dashboard ──
route('GET', '/api/dashboard', () => {
  const cur = curMonth(), s = monthStart(cur), e = monthEnd(cur);
  const totals = one(`SELECT
    (SELECT COALESCE(SUM(amount),0) FROM income) AS income,
    (SELECT COALESCE(SUM(amount),0) FROM expenses) AS expenses`);
  const month = one(`SELECT
    (SELECT COALESCE(SUM(amount),0) FROM income WHERE income_date BETWEEN ? AND ?) AS income,
    (SELECT COALESCE(SUM(amount),0) FROM expenses WHERE expense_date BETWEEN ? AND ?) AS expenses,
    (SELECT amount FROM budgets WHERE budget_month=? AND category='Overall') AS budget`,
    s, e, s, e, cur);
  const categories = query(
    `SELECT category, SUM(amount) AS total, COUNT(*) AS entries FROM expenses
     WHERE expense_date BETWEEN ? AND ? GROUP BY category ORDER BY total DESC`, s, e);
  const recent = query(
    `SELECT 'expense' AS kind, id, amount, category AS label, description, expense_date AS tx_date FROM expenses
     UNION ALL
     SELECT 'income', id, amount, source, description, income_date FROM income
     ORDER BY tx_date DESC, id DESC LIMIT 6`);

  const days7 = lastNDays(7);
  const expByDay = {};
  for (const r of query(`SELECT expense_date AS day, COALESCE(SUM(amount),0) AS total FROM expenses WHERE expense_date >= ? GROUP BY day`, days7[0]))
    expByDay[r.day] = r.total;
  const weekly = days7.map(day => ({ day, total: expByDay[day] || 0 }));

  const months6 = lastNMonths(6);
  const expByMonth = {}, incByMonth = {};
  for (const r of query(`SELECT strftime('%Y-%m',expense_date) AS m, SUM(amount) AS t FROM expenses WHERE expense_date >= ? GROUP BY m`, months6[0]+'-01'))
    expByMonth[r.m] = r.t;
  for (const r of query(`SELECT strftime('%Y-%m',income_date) AS m, SUM(amount) AS t FROM income WHERE income_date >= ? GROUP BY m`, months6[0]+'-01'))
    incByMonth[r.m] = r.t;
  const monthly = months6.map(m => ({ month: m, expenses: expByMonth[m]||0, income: incByMonth[m]||0 }));

  return {
    success: true,
    totals: { income: totals.income, expenses: totals.expenses, balance: totals.income - totals.expenses },
    month: { income: month.income, expenses: month.expenses, savings: month.income - month.expenses, budget: month.budget },
    categories, recent, weekly, monthly,
  };
});

// ── Groups ──
route('GET', '/api/groups', () => ({
  success: true,
  data: query(`SELECT g.id, g.name, g.created_at, COUNT(m.id) AS member_count
               FROM groups_tbl g LEFT JOIN group_members m ON m.group_id = g.id
               GROUP BY g.id ORDER BY g.id DESC`),
}));

route('POST', '/api/groups', ({ body: b }) => {
  const name = (b.name || '').trim();
  if (!name) fail(400, 'Group name is required.');
  const members = [...new Set((b.members || []).map(m => (m||'').trim()).filter(Boolean))];
  if (members.length < 2) fail(400, 'A group needs at least 2 different members.');
  const id = tx(() => {
    const gid = insertId('INSERT INTO groups_tbl (name) VALUES (?)', name);
    for (const m of members) insertId('INSERT INTO group_members (group_id, name) VALUES (?,?)', gid, m);
    return gid;
  });
  return { success: true, id, message: 'Group created.', _status: 201 };
});

route('GET', '/api/groups/:id', ({ params }) => {
  const g = one('SELECT * FROM groups_tbl WHERE id=?', params.id);
  if (!g) fail(404, 'Group not found.');
  const members = query('SELECT id, name FROM group_members WHERE group_id=? ORDER BY id', params.id);
  const bal = groupBalances(params.id);
  const balOut = bal.map(b => ({ id: b.id, name: b.name, paid: b.paid, owed: b.owed, net: round2(toRupees(b.net)) }));
  const debts = simplifyDebts(bal);
  const expenses = query(
    `SELECT se.id, se.description, se.amount, se.split_type, se.expense_date, payer.name AS paid_by_name,
       group_concat(pm.name||' ₹'||ep.share_amount) AS shares
     FROM shared_expenses se
     JOIN group_members payer ON payer.id = se.paid_by
     JOIN expense_participants ep ON ep.shared_expense_id = se.id
     JOIN group_members pm ON pm.id = ep.member_id
     WHERE se.group_id=? GROUP BY se.id ORDER BY se.expense_date DESC, se.id DESC`, params.id);
  const settlements = query(
    `SELECT s.id, s.amount, s.settled_on, f.name AS from_name, t.name AS to_name
     FROM settlements s JOIN group_members f ON f.id=s.from_member JOIN group_members t ON t.id=s.to_member
     WHERE s.group_id=? ORDER BY s.settled_on DESC, s.id DESC`, params.id);
  return { success: true, group: g, members, balances: balOut, debts, expenses, settlements };
});

route('DELETE', '/api/groups/:id', ({ params }) => {
  if (!run('DELETE FROM groups_tbl WHERE id=?', params.id).changes) fail(404, 'Group not found.');
  return { success: true, message: 'Group deleted.' };
});

route('POST', '/api/groups/:id/members', ({ params, body: b }) => {
  const name = (b.name || '').trim();
  if (!name) fail(400, 'Member name is required.');
  if (!one('SELECT id FROM groups_tbl WHERE id=?', params.id)) fail(404, 'Group not found.');
  try { insertId('INSERT INTO group_members (group_id, name) VALUES (?,?)', params.id, name); }
  catch (e) { if (e.message.includes('UNIQUE')) fail(409, 'That member is already in the group.'); throw e; }
  return { success: true, message: 'Member added.', _status: 201 };
});

route('POST', '/api/groups/:id/expenses', ({ params, body: b }) => {
  const members = query('SELECT id FROM group_members WHERE group_id=?', params.id);
  if (!members.length) fail(404, 'Group not found.');
  const ids = members.map(m => m.id);
  const total = toPaise(parseFloat(b.amount) || 0);
  const description = (b.description || '').trim();
  const paidBy = parseInt(b.paid_by);
  const splitType = b.split_type;
  const date = b.date || '';
  if (!description) fail(400, 'Description is required.');
  if (!(total > 0)) fail(400, 'Amount must be greater than zero.');
  if (!ids.includes(paidBy)) fail(400, 'Choose who paid.');
  if (!validDate(date)) fail(400, 'Choose a valid date.');
  if (!['equal','custom'].includes(splitType)) fail(400, 'Invalid split type.');
  if (!Array.isArray(b.participants) || !b.participants.length) fail(400, 'Select at least one participant.');

  let shares;
  if (splitType === 'equal') {
    const pids = [...new Set(b.participants.map(p => parseInt(p)).filter(p => ids.includes(p)))];
    if (pids.length !== b.participants.length && b.participants.some(p => !ids.includes(parseInt(p))))
      fail(400, 'Participants must be group members.');
    shares = equalSplit(total, pids);
  } else {
    const seen = new Set();
    shares = b.participants.map(p => ({ memberId: parseInt(p.member_id), paise: toPaise(parseFloat(p.amount)||0) }));
    for (const s of shares) { if (seen.has(s.memberId)) fail(400, 'A member appears twice.'); seen.add(s.memberId); }
    for (const s of shares) if (!ids.includes(s.memberId) || s.paise <= 0) fail(400, 'Every custom amount must be above zero.');
    const sum = shares.reduce((a,s) => a+s.paise, 0);
    if (sum !== total) fail(400, `Custom amounts add up to ₹${toRupees(sum).toFixed(2)} but the expense is ₹${toRupees(total).toFixed(2)}.`);
  }
  tx(() => {
    const eid = insertId('INSERT INTO shared_expenses (group_id,paid_by,amount,description,split_type,expense_date) VALUES (?,?,?,?,?,?)',
      params.id, paidBy, toRupees(total), description, splitType, date);
    for (const s of shares) insertId('INSERT INTO expense_participants (shared_expense_id,member_id,share_amount) VALUES (?,?,?)',
      eid, s.memberId, toRupees(s.paise));
  });
  return { success: true, message: 'Shared expense recorded.', _status: 201 };
});

route('POST', '/api/groups/:id/settlements', ({ params, body: b }) => {
  const from = parseInt(b.from), to2 = parseInt(b.to);
  const paise = toPaise(parseFloat(b.amount)||0);
  const date = b.date || '', token = b.token || '';
  if (from === to2) fail(400, 'Payer and receiver must be different people.');
  if (!(paise > 0)) fail(400, 'Amount must be greater than zero.');
  if (!validDate(date)) fail(400, 'Choose a valid date.');
  if (!/^[\w-]{8,64}$/.test(token)) fail(400, 'Missing request token.');
  try {
    tx(() => {
      const bal = groupBalances(params.id);
      const a = bal.find(x => x.id === from), r = bal.find(x => x.id === to2);
      if (!a || !r) fail(400, 'Both people must be members of this group.');
      const max = Math.min(-a.net, r.net);
      if (max <= 0) fail(400, `${a.name} does not owe ${r.name} anything right now.`);
      if (paise > max) fail(400, `${a.name} only owes ${r.name} up to ₹${toRupees(max).toFixed(2)}.`);
      insertId('INSERT INTO settlements (group_id,from_member,to_member,amount,settled_on,request_token) VALUES (?,?,?,?,?,?)',
        params.id, from, to2, toRupees(paise), date, token);
    });
  } catch (e) {
    if (e.message && e.message.includes('UNIQUE')) fail(409, 'This settlement was already recorded.');
    throw e;
  }
  return { success: true, message: 'Settlement recorded.', _status: 201 };
});

// ── Budgets ──
const BUDGET_CATS = ['Overall','Food','Shopping','Transportation','Education','Entertainment','Health','Bills','Others'];

route('GET', '/api/budgets', ({ query: q }) => {
  if (!validMonth(q.month)) fail(400, 'Month must look like 2026-11.');
  const s = monthStart(q.month), e = monthEnd(q.month);
  const rows = query(
    `SELECT b.id, b.category, b.amount,
       COALESCE((SELECT SUM(ex.amount) FROM expenses ex WHERE ex.expense_date BETWEEN ? AND ?
         AND (b.category='Overall' OR ex.category=b.category)), 0) AS spent
     FROM budgets b WHERE b.budget_month=? ORDER BY b.category='Overall' DESC, b.category`, s, e, q.month);
  for (const r of rows) {
    r.remaining = round2(r.amount - r.spent);
    r.percent   = round2(r.spent / r.amount * 100);
    r.status    = r.spent/r.amount >= 1 ? 'exceeded' : r.spent/r.amount >= 0.8 ? 'warning' : 'ok';
  }
  return { success: true, data: rows };
});

route('POST', '/api/budgets', ({ body: b }) => {
  if (!validMonth(b.month)) fail(400, 'Choose a valid month.');
  if (!BUDGET_CATS.includes(b.category)) fail(400, 'Choose a valid category.');
  if (!(parseFloat(b.amount) > 0)) fail(400, 'Budget must be greater than zero.');
  run('INSERT INTO budgets (budget_month,category,amount) VALUES (?,?,?) ON CONFLICT(budget_month,category) DO UPDATE SET amount=excluded.amount',
    b.month, b.category, parseFloat(b.amount));
  return { success: true, message: 'Budget saved.', _status: 201 };
});

route('DELETE', '/api/budgets/:id', ({ params }) => {
  if (!run('DELETE FROM budgets WHERE id=?', params.id).changes) fail(404, 'Budget not found.');
  return { success: true, message: 'Budget deleted.' };
});

// ── Goals ──
route('GET', '/api/goals', () => {
  const rows = query(
    `SELECT g.id, g.name, g.target_amount, g.deadline, COALESCE(SUM(c.amount),0) AS saved
     FROM savings_goals g LEFT JOIN savings_contributions c ON c.goal_id=g.id GROUP BY g.id ORDER BY g.id DESC`);
  for (const r of rows) {
    r.remaining = round2(Math.max(r.target_amount - r.saved, 0));
    r.percent   = Math.min(round2(r.saved / r.target_amount * 100), 100);
  }
  return { success: true, data: rows };
});

route('POST', '/api/goals', ({ body: b }) => {
  const name = (b.name||'').trim(), deadline = (b.deadline||'').trim();
  if (!name) fail(400, 'Goal name is required.');
  if (!(parseFloat(b.target_amount) > 0)) fail(400, 'Target must be greater than zero.');
  if (deadline && !validDate(deadline)) fail(400, 'Deadline is not a valid date.');
  insertId('INSERT INTO savings_goals (name,target_amount,deadline) VALUES (?,?,?)',
    name, parseFloat(b.target_amount), deadline || null);
  return { success: true, message: 'Goal created.', _status: 201 };
});

route('POST', '/api/goals/:id/contributions', ({ params, body: b }) => {
  const amount = parseFloat(b.amount) || 0;
  const g = one(
    `SELECT g.id, g.target_amount, COALESCE(SUM(c.amount),0) AS saved
     FROM savings_goals g LEFT JOIN savings_contributions c ON c.goal_id=g.id WHERE g.id=? GROUP BY g.id`, params.id);
  if (!g) fail(404, 'Goal not found.');
  if (!(amount > 0)) fail(400, 'Amount must be greater than zero.');
  const left = round2(g.target_amount - g.saved);
  if (amount > left) fail(400, left > 0 ? `Only ₹${left.toFixed(2)} is left to reach this goal.` : 'This goal is already complete.');
  insertId('INSERT INTO savings_contributions (goal_id,amount,contributed_on) VALUES (?,?,?)', params.id, amount, today());
  return { success: true, message: 'Contribution added.', _status: 201 };
});

route('DELETE', '/api/goals/:id', ({ params }) => {
  if (!run('DELETE FROM savings_goals WHERE id=?', params.id).changes) fail(404, 'Goal not found.');
  return { success: true, message: 'Goal deleted.' };
});

// ── Insights ──
route('GET', '/api/insights', () => ({ success: true, ...computeInsights() }));

// ── Challenges ──
const CHALLENGE_DAYS = { '7-day': 7, '30-day': 30 };
const UNNECESSARY = "('Shopping','Entertainment','Others')";

route('GET', '/api/challenges', () => {
  const rows = query(
    `SELECT c.id, c.challenge_type, c.start_date, c.end_date, c.status,
       COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.expense_date BETWEEN c.start_date AND c.end_date AND e.category IN ${UNNECESSARY}),0) AS unnecessary,
       (SELECT COUNT(DISTINCT e.expense_date) FROM expenses e WHERE e.expense_date BETWEEN c.start_date AND c.end_date AND e.category IN ${UNNECESSARY}) AS spend_days,
       (CAST(julianday(MIN(date('now'), c.end_date)) - julianday(c.start_date) AS INTEGER) + 1) AS days_done,
       (CAST(julianday(c.end_date) - julianday(c.start_date) AS INTEGER) + 1) AS total_days,
       CASE WHEN date('now') > c.end_date THEN 1 ELSE 0 END AS finished
     FROM challenges c ORDER BY c.id DESC`);
  for (const c of rows) {
    const finished = c.finished === 1;
    const status = c.unnecessary > 0 ? 'failed' : finished ? 'completed' : 'active';
    if (status !== c.status) { run('UPDATE challenges SET status=? WHERE id=?', status, c.id); c.status = status; }
    c.percent = Math.min(Math.round(c.days_done / c.total_days * 100), 100);
  }
  return { success: true, unnecessary_categories: ['Shopping','Entertainment','Others'], data: rows };
});

route('POST', '/api/challenges', ({ body: b }) => {
  const days = CHALLENGE_DAYS[b.type];
  if (!days) fail(400, 'Choose a 7-day or 30-day challenge.');
  if (one("SELECT id FROM challenges WHERE status='active' LIMIT 1")) fail(409, 'You already have an active challenge. Finish or delete it first.');
  const t = today();
  const end = new Date(t); end.setDate(end.getDate() + days - 1);
  insertId('INSERT INTO challenges (challenge_type,start_date,end_date) VALUES (?,?,?)', b.type, t, end.toISOString().slice(0,10));
  return { success: true, message: `${b.type} no-spend challenge started. Good luck!`, _status: 201 };
});

route('DELETE', '/api/challenges/:id', ({ params }) => {
  if (!run('DELETE FROM challenges WHERE id=?', params.id).changes) fail(404, 'Challenge not found.');
  return { success: true, message: 'Challenge removed.' };
});

// ── Calendar ──
route('GET', '/api/calendar', ({ query: q }) => {
  if (!validMonth(q.month)) fail(400, 'Month must look like 2026-11.');
  const s = monthStart(q.month), e = monthEnd(q.month);
  const expRows = query(`SELECT expense_date AS day, SUM(amount) AS total, COUNT(*) AS entries FROM expenses WHERE expense_date BETWEEN ? AND ? GROUP BY day`, s, e);
  const incRows = query(`SELECT income_date AS day, SUM(amount) AS total FROM income WHERE income_date BETWEEN ? AND ? GROUP BY day`, s, e);
  const days = {};
  for (const r of expRows) days[r.day] = { expenses: r.total, entries: r.entries, income: 0 };
  for (const r of incRows) {
    if (days[r.day]) days[r.day].income = r.total;
    else days[r.day] = { expenses: 0, entries: 0, income: r.total };
  }
  return { success: true, days };
});

// ── Reports (PDF) ──
route('GET', '/api/reports/monthly', ({ query: q, params }, res) => {
  if (!validMonth(q.month)) fail(400, 'Month must look like 2026-11.');
  const pdf = buildPdf(q.month);
  // Signal to the response handler to send bytes
  return { _pdf: true, _data: pdf, _month: q.month };
});

// ─────────────────────────── Server start ─────────────────────────────

const PORT = 3000;

const server = http.createServer(async (req, res) => {
  // Special PDF handling
  const pathname = req.url.split('?')[0];
  if (pathname === '/api/reports/monthly') {
    const q = getQuery(req.url);
    if (!validMonth(q.month)) { sendErr(res, apiError(400, 'Month must look like 2026-11.')); return; }
    try {
      const pdf = buildPdf(q.month);
      res.writeHead(200, {
        'Content-Type': 'application/pdf',
        'Content-Disposition': `attachment; filename="SpendWise-${q.month}.pdf"`,
        'Content-Length': pdf.length,
      });
      res.end(pdf);
    } catch (e) { sendErr(res, e); }
    return;
  }
  await handleRequest(req, res);
});

server.listen(PORT, () => {
  console.log(`\n✅ SpendWise running at http://localhost:${PORT}`);
  console.log(`   Health check: http://localhost:${PORT}/api/health`);
  console.log(`   Press Ctrl+C to stop.\n`);
});

server.on('error', err => {
  if (err.code === 'EADDRINUSE') {
    console.error(`\n❌ Port ${PORT} is already in use. Kill the other process and try again.\n`);
  } else {
    console.error(err);
  }
  process.exit(1);
});

