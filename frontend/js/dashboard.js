renderSidebar('dashboard');
const app = document.getElementById('app');
app.innerHTML = '<h1>Dashboard</h1><div class="loading">Loading your finances…</div>';

const dayLabel = (d) => new Date(d + 'T00:00:00').toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric' });
const monthLabel = (m) => new Date(m + '-01T00:00:00').toLocaleDateString('en-IN', { month: 'short', year: '2-digit' });
const COLORS = ['#4f46e5', '#06b6d4', '#f59e0b', '#10b981', '#ef4444', '#8b5cf6', '#ec4899', '#64748b'];

function budgetCard(m) {
  if (!m.budget) return `<div class="card"><div class="muted">Monthly budget</div>
    <div class="empty">No budget set for this month. You can create one on the Budgets page.</div></div>`;
  const pct = m.expenses / m.budget * 100;
  const cls = pct >= 100 ? 'over' : pct >= 80 ? 'warn' : '';
  const msg = pct >= 100 ? `<div class="alert neg">Budget exceeded by ${inr(m.expenses - m.budget)}.</div>`
    : pct >= 80 ? `<div class="alert">Warning: ${pct.toFixed(0)}% of your budget is used.</div>` : '';
  return `<div class="card"><div class="muted">Monthly budget</div>
    <div class="stat">${inr(m.expenses)} <span class="muted">of ${inr(m.budget)}</span></div>
    <div class="progress ${cls}"><div style="width:${Math.min(pct, 100)}%"></div></div>
    <div class="muted">${pct.toFixed(0)}% used · ${inr(Math.max(m.budget - m.expenses, 0))} remaining</div>${msg}</div>`;
}

function overview(d) {
  const rate = d.month.income > 0 ? (d.month.savings / d.month.income * 100).toFixed(0) + '%' : 'n/a';
  const top = d.categories[0];
  return `<div class="card"><h3>Financial overview</h3>
    <p>This month you earned <b>${inr(d.month.income)}</b> and spent <b>${inr(d.month.expenses)}</b>, a savings rate of <b>${rate}</b>.</p>
    <p>${top ? `Your biggest category is <b>${esc(top.category)}</b> at ${inr(top.total)}.` : 'No expenses recorded this month yet.'}</p></div>`;
}

function drawCharts(d) {
  Chart.defaults.color = getComputedStyle(document.body).getPropertyValue('--muted');
  Chart.defaults.maintainAspectRatio = false;
  new Chart('weeklyChart', { type: 'bar', data: { labels: d.weekly.map(x => dayLabel(x.day)),
    datasets: [{ label: 'Spent (₹)', data: d.weekly.map(x => x.total), backgroundColor: COLORS[0], borderRadius: 6 }] },
    options: { plugins: { legend: { display: false } } } });
  new Chart('monthlyChart', { type: 'line', data: { labels: d.monthly.map(x => monthLabel(x.month)),
    datasets: [{ label: 'Expenses (₹)', data: d.monthly.map(x => x.expenses), borderColor: COLORS[4], tension: .35, fill: false }] },
    options: { plugins: { legend: { display: false } } } });
  new Chart('incomeExpenseChart', { type: 'bar', data: { labels: d.monthly.map(x => monthLabel(x.month)),
    datasets: [{ label: 'Income', data: d.monthly.map(x => x.income), backgroundColor: COLORS[3], borderRadius: 4 },
               { label: 'Expenses', data: d.monthly.map(x => x.expenses), backgroundColor: COLORS[4], borderRadius: 4 }] } });
  if (d.categories.length)
    new Chart('categoryChart', { type: 'doughnut', data: { labels: d.categories.map(x => x.category),
      datasets: [{ data: d.categories.map(x => x.total), backgroundColor: COLORS }] }, options: { plugins: { legend: { position: 'right' } } } });
}

async function load() {
  try {
    const d = await api('/api/dashboard');
    const sav = d.month.savings;
    app.innerHTML = `<h1>Dashboard</h1>
      <div class="grid">
        <div class="card"><div class="muted">Total income</div><div class="stat pos">${inr(d.totals.income)}</div></div>
        <div class="card"><div class="muted">Total expenses</div><div class="stat neg">${inr(d.totals.expenses)}</div></div>
        <div class="card"><div class="muted">Available balance</div><div class="stat">${inr(d.totals.balance)}</div></div>
        <div class="card"><div class="muted">Savings this month</div><div class="stat ${sav >= 0 ? 'pos' : 'neg'}">${inr(sav)}</div></div>
      </div>
      <div class="grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr))">${budgetCard(d.month)}${overview(d)}</div>
      <div class="grid charts">
        <div class="card"><h3>Weekly spending</h3><div class="chart-box"><canvas id="weeklyChart"></canvas></div></div>
        <div class="card"><h3>Monthly spending</h3><div class="chart-box"><canvas id="monthlyChart"></canvas></div></div>
        <div class="card"><h3>Expenses by category (this month)</h3>
          <div class="chart-box">${d.categories.length ? '<canvas id="categoryChart"></canvas>' : '<div class="empty">No expenses this month.</div>'}</div></div>
        <div class="card"><h3>Income vs expenses</h3><div class="chart-box"><canvas id="incomeExpenseChart"></canvas></div></div>
      </div>
      <div class="card table-wrap"><h3>Recent transactions</h3>${d.recent.length
        ? '<table><tr><th>Date</th><th>Type</th><th>Description</th><th>Amount</th></tr>' + d.recent.map(r =>
          `<tr><td>${r.tx_date}</td><td><span class="badge">${esc(r.label)}</span></td><td>${esc(r.description)}</td>
           <td class="${r.kind === 'income' ? 'pos' : 'neg'}">${r.kind === 'income' ? '+' : '-'}${inr(r.amount)}</td></tr>`).join('') + '</table>'
        : '<div class="empty">No transactions yet. Add an expense or income to get started.</div>'}</div>`;
    drawCharts(d);
  } catch (e) { app.innerHTML = '<h1>Dashboard</h1><div class="empty">Could not load data.</div>'; toast(e.message, 'error'); }
}
load();
window.addEventListener('themechange', load); // redraw charts with the right text colour
