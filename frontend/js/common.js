// Shared helpers used by every page.
const NAV = [['dashboard','Dashboard','layout-dashboard'],['expenses','Expenses','receipt'],['income','Income','wallet'],
  ['groups','Groups','users'],['budgets','Budgets','pie-chart'],['goals','Goals','target'],['insights','Insights','search'],['calendar','Calendar','calendar']];

// Escape text before putting it in HTML (prevents script injection)
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const inr = (n) => '₹' + Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

// fetch wrapper: throws an Error with the server's message on failure
async function api(url, method = 'GET', body) {
  const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(json.message || 'Request failed');
  return json;
}

function toast(msg, type = 'success') {
  let box = document.getElementById('toasts');
  if (!box) { box = document.createElement('div'); box.id = 'toasts'; document.body.appendChild(box); }
  const t = document.createElement('div'); t.className = 'toast ' + type; t.textContent = msg;
  box.appendChild(t); setTimeout(() => t.remove(), 3000);
}

function setTheme(t) { document.documentElement.dataset.theme = t; localStorage.setItem('theme', t); window.dispatchEvent(new Event('themechange')); }
setTheme(localStorage.getItem('theme') || 'light'); // localStorage only remembers the theme, never data

function renderSidebar(active) {
  document.body.insertAdjacentHTML('afterbegin', `<nav class="sidebar"><div class="logo">SpendWise</div>
    ${NAV.map(([id, label, icon]) => `<a href="${id}.html" class="${id === active ? 'active' : ''}"><i data-lucide="${icon}"></i><span>${label}</span></a>`).join('')}
    <a href="#" class="theme-btn" id="themeBtn"><i data-lucide="moon"></i><span>Dark / Light</span></a></nav>`);
  document.getElementById('themeBtn').onclick = (e) => { e.preventDefault(); setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'); };
  if (window.lucide) lucide.createIcons();
}

// Builds a complete list + form page for expenses or income.
function initRecords({ page, title, endpoint, types, typeLabel, descRequired, onLoaded }) {
  renderSidebar(page);
  const today = new Date().toISOString().slice(0, 10);
  const opts = (extra = '') => extra + types.map(t => `<option>${t}</option>`).join('');
  document.getElementById('app').innerHTML = `
    <h1>${title}</h1>
    <div class="card"><div class="muted">Total (current filter)</div><div class="stat" id="total">–</div></div>
    <div class="card"><form id="form" class="form-row">
      <label>Amount (₹)<input name="amount" type="number" step="0.01" min="0.01" required></label>
      <label>${typeLabel}<select name="type" required>${opts()}</select></label>
      <label>Description<input name="description" maxlength="255" ${descRequired ? 'required' : ''}></label>
      <label>Date<input name="date" type="date" value="${today}" required></label>
      <button id="saveBtn">Add</button><button type="button" class="ghost" id="cancelBtn" hidden>Cancel</button></form></div>
    <div class="card"><div class="form-row">
      <label>Search<input id="fSearch" placeholder="Search..."></label>
      <label>${typeLabel}<select id="fType">${opts('<option value="">All</option>')}</select></label>
      <label>From<input id="fFrom" type="date"></label><label>To<input id="fTo" type="date"></label>
      <button class="ghost" id="clearBtn">Clear</button></div></div>
    <div class="card table-wrap"><table><thead><tr><th>Date</th><th>${typeLabel}</th><th>Description</th><th>Amount</th><th></th></tr></thead>
    <tbody id="rows"></tbody></table></div>`;

  const $ = (id) => document.getElementById(id), form = $('form');
  let editingId = null, current = [];

  async function load() {
    $('rows').innerHTML = '<tr><td colspan="5" class="loading">Loading…</td></tr>';
    const q = new URLSearchParams({ search: $('fSearch').value, type: $('fType').value, from: $('fFrom').value, to: $('fTo').value });
    try {
      const r = await api(`${endpoint}?${q}`);
      current = r.data; $('total').textContent = inr(r.total);
      $('rows').innerHTML = r.data.length ? r.data.map(x => `<tr><td>${x.expense_date || x.income_date}</td>
        <td><span class="badge">${esc(x.category || x.source)}</span></td><td>${esc(x.description)}</td><td>${inr(x.amount)}</td>
        <td><button class="small ghost" data-edit="${x.id}">Edit</button> <button class="small danger" data-del="${x.id}">Delete</button></td></tr>`).join('')
        : '<tr><td colspan="5" class="empty">No records found. Add one above.</td></tr>';
      if (onLoaded) onLoaded();
    } catch (e) { $('rows').innerHTML = ''; toast(e.message, 'error'); }
  }

  function resetForm() { editingId = null; form.reset(); form.date.value = today; $('saveBtn').textContent = 'Add'; $('cancelBtn').hidden = true; }

  form.onsubmit = async (e) => {
    e.preventDefault();
    const body = Object.fromEntries(new FormData(form));
    try {
      const r = editingId ? await api(`${endpoint}/${editingId}`, 'PUT', body) : await api(endpoint, 'POST', body);
      toast(r.message); resetForm(); load();
    } catch (err) { toast(err.message, 'error'); }
  };
  $('cancelBtn').onclick = resetForm;
  $('rows').onclick = async (e) => {
    const edit = e.target.dataset.edit, del = e.target.dataset.del;
    if (edit) {
      const x = current.find(r => r.id == edit); editingId = x.id;
      form.amount.value = x.amount; form.type.value = x.category || x.source; form.description.value = x.description;
      form.date.value = x.expense_date || x.income_date; $('saveBtn').textContent = 'Update'; $('cancelBtn').hidden = false; scrollTo(0, 0);
    }
    if (del && confirm('Delete this record? This cannot be undone.')) {
      try { toast((await api(`${endpoint}/${del}`, 'DELETE')).message); load(); } catch (err) { toast(err.message, 'error'); }
    }
  };
  ['fSearch', 'fType', 'fFrom', 'fTo'].forEach(id => $(id).addEventListener('input', load));
  $('clearBtn').onclick = () => { ['fSearch', 'fType', 'fFrom', 'fTo'].forEach(id => $(id).value = ''); load(); };
  load();
}
