renderSidebar('insights');
const app = document.getElementById('app');
app.innerHTML = '<h1>Insights</h1><div class="loading">Analysing your spending…</div>';

async function load() {
  try {
    const [i, c] = await Promise.all([api('/api/insights'), api('/api/challenges')]);
    const s = i.score, color = s.total >= 60 ? 'pos' : s.total >= 40 ? '' : 'neg';
    app.innerHTML = `<h1>Insights</h1>
      <div class="card"><h3>Financial health score</h3>
        <div class="stat ${color}" style="font-size:2.6rem">${s.total}<span class="muted"> / 100 · ${s.label}</span></div>
        <div class="progress ${s.total < 40 ? 'over' : s.total < 60 ? 'warn' : ''}"><div style="width:${s.total}%"></div></div>
        <p class="muted">Score = budget usage (40) + savings progress (30) + spending pattern (30). Here is how yours was worked out:</p>
        <div class="table-wrap"><table><tr><th>Part</th><th>Points</th><th>Why</th></tr>${s.breakdown.map(p =>
          `<tr><td>${p.name}</td><td>${p.points} / ${p.max}</td><td class="muted">${esc(p.reason)}</td></tr>`).join('')}</table></div></div>

      <h2>Smart Spending Detective</h2>
      <div class="grid">
        <div class="card"><div class="muted">Highest category (this month)</div><div class="stat">${i.top ? esc(i.top.category) : '–'}</div><div class="muted">${i.top ? inr(i.top.total) : 'No expenses yet'}</div></div>
        <div class="card"><div class="muted">This month vs last month</div><div class="stat ${i.change > 0 ? 'neg' : 'pos'}">${i.change === null ? 'n/a' : (i.change > 0 ? '+' : '') + i.change + '%'}</div>
          <div class="muted">${inr(i.this_total)} vs ${inr(i.last_total)} (month to date vs full month)</div></div>
        <div class="card"><div class="muted">Unusual increases</div><div class="stat">${i.unusual.length}</div><div class="muted">${i.unusual.map(u => esc(u.category)).join(', ') || 'None detected'}</div></div>
      </div>
      <div class="card"><h3>Suggestions based on your data</h3><ul>${i.suggestions.map(x => `<li>${esc(x)}</li>`).join('')}</ul></div>
      <div class="grid charts">
        <div class="card table-wrap"><h3>Category comparison</h3>${i.comparison.length ? `<table><tr><th>Category</th><th>Last month</th><th>This month</th><th></th></tr>${i.comparison.map(x =>
          `<tr><td>${esc(x.category)}</td><td>${inr(x.last_month)}</td><td>${inr(x.this_month)}</td><td>${x.unusual ? '<span class="badge neg">unusual</span>' : ''}</td></tr>`).join('')}</table>` : '<div class="empty">No data yet.</div>'}</div>
        <div class="card table-wrap"><h3>Most-used categories (90 days)</h3>${i.frequency.length ? `<table><tr><th>Category</th><th>Entries</th><th>Total</th></tr>${i.frequency.map(x =>
          `<tr><td>${esc(x.category)}</td><td>${x.entries}</td><td>${inr(x.total)}</td></tr>`).join('')}</table>` : '<div class="empty">No data yet.</div>'}</div>
        <div class="card"><h3>Spending by day of week (90 days)</h3><div class="chart-box"><canvas id="wdChart"></canvas></div></div>
      </div>

      <div class="card"><h3>Smart savings recommendations</h3>${i.recommendations.length ? `<p>Trimming these everyday categories by about 15% could save <b>${inr(i.potential_saving)}</b> a month.</p><table>
        ${i.recommendations.map(r => `<tr><td><b>${esc(r.category)}</b></td><td>cut ~${inr(r.suggested_cut)} from ${inr(r.current)}</td><td class="muted">Tip: ${esc(r.tip)}</td></tr>`).join('')}</table>`
        : '<div class="empty">No spending in everyday categories to analyse yet.</div>'}</div>

      <div class="card"><div class="row-between"><h3>No-spend challenge</h3><div>
        <button class="small" data-start="7-day">Start 7-day</button> <button class="small" data-start="30-day">Start 30-day</button></div></div>
        <p class="muted">Any spending in ${c.unnecessary_categories.join(', ')} during a challenge counts as unnecessary and ends it.</p>
        ${c.data.length ? c.data.map(x => `<div class="debt" style="display:block"><div class="row-between"><b>${x.challenge_type} challenge</b>
          <span><span class="badge ${x.status === 'failed' ? 'neg' : x.status === 'completed' ? 'pos' : ''}">${x.status}</span> <button class="small ghost" data-delch="${x.id}">Remove</button></span></div>
          <div class="progress ${x.status === 'failed' ? 'over' : ''}"><div style="width:${x.percent}%"></div></div>
          <div class="muted">Day ${Math.min(x.days_done, x.total_days)} of ${x.total_days} · ${x.start_date} to ${x.end_date} · unnecessary spending ${inr(x.unnecessary)} on ${x.spend_days} day(s)</div></div>`).join('')
          : '<div class="empty">No challenges yet.</div>'}</div>

      <div class="card"><h3>Monthly PDF report</h3><div class="form-row"><label>Month<input type="month" id="repMonth" value="${new Date().toISOString().slice(0, 7)}"></label>
        <button id="repBtn">Download PDF</button></div></div>`;

    Chart.defaults.color = getComputedStyle(document.body).getPropertyValue('--muted'); Chart.defaults.maintainAspectRatio = false;
    new Chart('wdChart', { type: 'bar', data: { labels: i.weekday.map(x => x.day), datasets: [{ data: i.weekday.map(x => x.total), backgroundColor: '#4f46e5', borderRadius: 6 }] }, options: { plugins: { legend: { display: false } } } });
    document.getElementById('repBtn').onclick = () => { location.href = '/api/reports/monthly?month=' + document.getElementById('repMonth').value; };
  } catch (e) { app.innerHTML = '<h1>Insights</h1><div class="empty">Could not load insights.</div>'; toast(e.message, 'error'); }
}
app.onclick = async (e) => {
  try {
    if (e.target.dataset.start) { toast((await api('/api/challenges', 'POST', { type: e.target.dataset.start })).message); load(); }
    if (e.target.dataset.delch && confirm('Remove this challenge?')) { toast((await api('/api/challenges/' + e.target.dataset.delch, 'DELETE')).message); load(); }
  } catch (err) { toast(err.message, 'error'); }
};
window.addEventListener('themechange', load);
load();
