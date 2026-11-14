renderSidebar('calendar');
const app = document.getElementById('app');
const pad = (n) => String(n).padStart(2, '0');
let view = new Date(); view.setDate(1);

app.innerHTML = `<h1>Expense calendar</h1><div class="card"><div class="row-between"><button class="ghost" id="prev">←</button><h3 id="title"></h3><button class="ghost" id="next">→</button></div>
  <div class="cal" id="cal"></div></div><div class="card" id="detail"><div class="empty">Select a date to see its transactions.</div></div>`;
const $ = (id) => document.getElementById(id);

async function load() {
  const y = view.getFullYear(), m = view.getMonth(), month = `${y}-${pad(m + 1)}`;
  $('title').textContent = view.toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
  $('cal').innerHTML = '<div class="loading" style="grid-column:1/-1">Loading…</div>';
  try {
    const { days } = await api('/api/calendar?month=' + month);
    let html = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map(d => `<div class="cal-head">${d}</div>`).join('');
    html += '<div class="cal-cell blank"></div>'.repeat(new Date(y, m, 1).getDay());
    for (let d = 1; d <= new Date(y, m + 1, 0).getDate(); d++) {
      const key = `${month}-${pad(d)}`, x = days[key];
      html += `<div class="cal-cell" data-day="${key}"><b>${d}</b>${x && x.expenses ? `<div class="neg">-${inr(x.expenses).replace('.00', '')}</div>` : ''}${x && x.income ? `<div class="pos">+${inr(x.income).replace('.00', '')}</div>` : ''}</div>`;
    }
    $('cal').innerHTML = html;
  } catch (e) { $('cal').innerHTML = ''; toast(e.message, 'error'); }
}
$('prev').onclick = () => { view.setMonth(view.getMonth() - 1); load(); };
$('next').onclick = () => { view.setMonth(view.getMonth() + 1); load(); };
$('cal').onclick = async (e) => {
  const cell = e.target.closest('[data-day]'); if (!cell) return;
  document.querySelectorAll('.cal-cell.sel').forEach(c => c.classList.remove('sel')); cell.classList.add('sel');
  const day = cell.dataset.day;
  try {
    const [ex, inc] = await Promise.all([api(`/api/expenses?from=${day}&to=${day}`), api(`/api/income?from=${day}&to=${day}`)]);
    const rows = [...ex.data.map(x => `<tr><td><span class="badge">${esc(x.category)}</span></td><td>${esc(x.description)}</td><td class="neg">-${inr(x.amount)}</td></tr>`),
                  ...inc.data.map(x => `<tr><td><span class="badge">${esc(x.source)}</span></td><td>${esc(x.description)}</td><td class="pos">+${inr(x.amount)}</td></tr>`)];
    $('detail').innerHTML = `<h3>${day}</h3>` + (rows.length ? `<table>${rows.join('')}</table>` : '<div class="empty">No transactions on this date.</div>');
  } catch (err) { toast(err.message, 'error'); }
};
load();
