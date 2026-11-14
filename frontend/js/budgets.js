renderSidebar('budgets');
const CATS = ['Overall', 'Food', 'Shopping', 'Transportation', 'Education', 'Entertainment', 'Health', 'Bills', 'Others'];
const app = document.getElementById('app');
let month = new Date().toISOString().slice(0, 7);

app.innerHTML = `<h1>Budgets</h1>
  <div class="card"><form id="bform" class="form-row">
    <label>Month<input type="month" name="month" value="${month}" required></label>
    <label>Category<select name="category">${CATS.map(c => `<option>${c}</option>`).join('')}</select></label>
    <label>Budget (₹)<input name="amount" type="number" step="0.01" min="0.01" required></label>
    <button>Save budget</button></form>
    <div class="muted" style="margin-top:8px">"Overall" is the whole-month budget. Saving the same month and category again updates it.</div></div>
  <div id="blist" class="grid"></div>`;
const form = document.getElementById('bform'), list = document.getElementById('blist');

async function loadList() {
  list.innerHTML = '<div class="loading">Loading…</div>';
  try {
    const r = await api('/api/budgets?month=' + month);
    list.innerHTML = r.data.length ? r.data.map(b => `<div class="card">
      <div class="row-between"><h3>${esc(b.category)}</h3><button class="small danger" data-del="${b.id}">Delete</button></div>
      <div class="stat">${inr(b.spent)} <span class="muted">of ${inr(b.amount)}</span></div>
      <div class="progress ${b.status === 'exceeded' ? 'over' : b.status === 'warning' ? 'warn' : ''}"><div style="width:${Math.min(b.percent, 100)}%"></div></div>
      <div class="muted">${b.percent}% used · ${b.remaining >= 0 ? inr(b.remaining) + ' remaining' : inr(-b.remaining) + ' over'}</div>
      ${b.status === 'warning' ? '<div class="alert">Warning: 80% of this budget is used.</div>' : ''}
      ${b.status === 'exceeded' ? '<div class="alert neg">Budget exceeded!</div>' : ''}</div>`).join('')
      : '<div class="empty">No budgets for this month yet.</div>';
  } catch (e) { list.innerHTML = ''; toast(e.message, 'error'); }
}
form.month.onchange = () => { month = form.month.value; loadList(); };
form.onsubmit = async (e) => {
  e.preventDefault();
  try { toast((await api('/api/budgets', 'POST', Object.fromEntries(new FormData(form)))).message); month = form.month.value; loadList(); form.amount.value = ''; }
  catch (err) { toast(err.message, 'error'); }
};
list.onclick = async (e) => {
  const id = e.target.dataset.del;
  if (id && confirm('Delete this budget?')) { try { toast((await api('/api/budgets/' + id, 'DELETE')).message); loadList(); } catch (err) { toast(err.message, 'error'); } }
};
loadList();
