renderSidebar('goals');
const app = document.getElementById('app');
app.innerHTML = `<h1>Savings goals</h1>
  <div class="card"><form id="gform" class="form-row">
    <label>Goal<input name="name" required maxlength="100" placeholder="New laptop"></label>
    <label>Target (₹)<input name="target_amount" type="number" step="0.01" min="0.01" required></label>
    <label>Deadline (optional)<input name="deadline" type="date"></label><button>Create goal</button></form></div>
  <div id="glist" class="grid"></div>`;
const list = document.getElementById('glist');

async function load() {
  list.innerHTML = '<div class="loading">Loading…</div>';
  try {
    const r = await api('/api/goals');
    list.innerHTML = r.data.length ? r.data.map(g => `<div class="card">
      <div class="row-between"><h3>${esc(g.name)}</h3><button class="small danger" data-del="${g.id}">Delete</button></div>
      <div class="stat">${inr(g.saved)} <span class="muted">of ${inr(g.target_amount)}</span></div>
      <div class="progress"><div style="width:${g.percent}%"></div></div>
      <div class="muted">${g.percent}% saved · ${inr(g.remaining)} to go${g.deadline ? ' · by ' + g.deadline : ''}</div>
      ${g.remaining > 0 ? `<form class="form-row" data-contrib="${g.id}" style="margin-top:10px"><label>Add savings (₹)<input name="amount" type="number" step="0.01" min="0.01" required></label><button class="small">Add</button></form>`
        : '<div class="alert pos">Goal reached! 🎉</div>'}</div>`).join('')
      : '<div class="empty">No goals yet. Create your first one above.</div>';
  } catch (e) { list.innerHTML = ''; toast(e.message, 'error'); }
}
document.getElementById('gform').onsubmit = async (e) => {
  e.preventDefault();
  try { toast((await api('/api/goals', 'POST', Object.fromEntries(new FormData(e.target)))).message); e.target.reset(); load(); }
  catch (err) { toast(err.message, 'error'); }
};
list.onsubmit = async (e) => {
  e.preventDefault();
  try { toast((await api(`/api/goals/${e.target.dataset.contrib}/contributions`, 'POST', { amount: e.target.amount.value })).message); load(); }
  catch (err) { toast(err.message, 'error'); }
};
list.onclick = async (e) => {
  const id = e.target.dataset.del;
  if (id && confirm('Delete this goal and its contributions?')) { try { toast((await api('/api/goals/' + id, 'DELETE')).message); load(); } catch (err) { toast(err.message, 'error'); } }
};
load();
