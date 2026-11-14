renderSidebar('groups');
const app = document.getElementById('app');
const today = new Date().toISOString().slice(0, 10);
const newToken = () => (crypto.randomUUID ? crypto.randomUUID() : Date.now() + '-' + Math.random().toString(16).slice(2));

// ---------- Group list ----------
async function showList() {
  app.innerHTML = `<h1>Groups</h1>
    <div class="card"><form id="gform" class="form-row">
      <label>Group name<input name="name" required maxlength="100" placeholder="Goa trip"></label>
      <label>Members (comma separated)<input name="members" required placeholder="Asha, Ravi, Meera"></label>
      <button>Create group</button></form></div>
    <div id="glist" class="grid"><div class="loading">Loading…</div></div>`;
  document.getElementById('gform').onsubmit = async (e) => {
    e.preventDefault();
    const f = e.target;
    try {
      const r = await api('/api/groups', 'POST', { name: f.name.value, members: f.members.value.split(',') });
      toast(r.message); showGroup(r.id);
    } catch (err) { toast(err.message, 'error'); }
  };
  try {
    const r = await api('/api/groups');
    document.getElementById('glist').innerHTML = r.data.length
      ? r.data.map(g => `<div class="card open-card" data-open="${g.id}"><h3>${esc(g.name)}</h3><div class="muted">${g.member_count} members</div></div>`).join('')
      : '<div class="empty">No groups yet. Create one above.</div>';
    document.getElementById('glist').onclick = (e) => { const c = e.target.closest('[data-open]'); if (c) showGroup(c.dataset.open); };
  } catch (e) { toast(e.message, 'error'); }
}

// ---------- One group ----------
async function showGroup(id) {
  app.innerHTML = '<div class="loading">Loading…</div>';
  let d;
  try { d = await api('/api/groups/' + id); } catch (e) { toast(e.message, 'error'); return showList(); }
  const memberOpts = d.members.map(m => `<option value="${m.id}">${esc(m.name)}</option>`).join('');

  app.innerHTML = `
    <div class="row-between"><h1>${esc(d.group.name)}</h1>
      <div><button class="ghost" id="back">← All groups</button> <button class="danger" id="delGroup">Delete group</button></div></div>

    <div class="grid" style="grid-template-columns:repeat(auto-fit,minmax(320px,1fr))">
      <div class="card"><h3>Balances</h3><table><tr><th>Member</th><th>Paid</th><th>Status</th></tr>
        ${d.balances.map(b => `<tr><td>${esc(b.name)}</td><td>${inr(b.paid)}</td><td class="${b.net > 0 ? 'pos' : b.net < 0 ? 'neg' : 'muted'}">
          ${b.net > 0 ? 'gets back ' + inr(b.net) : b.net < 0 ? 'owes ' + inr(-b.net) : 'settled up'}</td></tr>`).join('')}</table></div>
      <div class="card"><h3>Who owes whom</h3>${d.debts.length ? d.debts.map(x => `<div class="debt">
        <span><b>${esc(x.from_name)}</b> owes <b>${esc(x.to_name)}</b> ${inr(x.amount)}</span>
        <button class="small" data-settle data-from="${x.from}" data-to="${x.to}" data-amount="${x.amount}" data-token="${newToken()}"
          data-label="${esc(x.from_name)} paid ${esc(x.to_name)}">Settle</button></div>`).join('')
        : '<div class="empty">Everyone is settled up 🎉</div>'}</div>
    </div>

    <div class="card"><h3>Add shared expense</h3><form id="xform" class="form-row">
      <label>Description<input name="description" required maxlength="255" placeholder="Dinner"></label>
      <label>Amount (₹)<input name="amount" type="number" step="0.01" min="0.01" required></label>
      <label>Paid by<select name="paid_by">${memberOpts}</select></label>
      <label>Date<input name="date" type="date" value="${today}" required></label>
      <label>Split<select name="split_type"><option value="equal">Equally</option><option value="custom">Custom amounts</option></select></label>
      <div class="parts">${d.members.map(m => `<div class="part" data-id="${m.id}"><input type="checkbox" checked>
        <span>${esc(m.name)}</span><input class="cust" type="number" step="0.01" min="0" placeholder="₹ share" hidden></div>`).join('')}</div>
      <button>Add expense</button></form></div>

    <div class="card"><h3>Add member</h3><form id="mform" class="form-row">
      <label>Name<input name="name" required maxlength="100"></label><button>Add</button></form></div>

    <div class="card table-wrap"><h3>Expense history</h3>${d.expenses.length ? `<table><tr><th>Date</th><th>Description</th><th>Paid by</th><th>Amount</th><th>Split</th></tr>
      ${d.expenses.map(x => `<tr><td>${x.expense_date}</td><td>${esc(x.description)}</td><td>${esc(x.paid_by_name)}</td><td>${inr(x.amount)}</td>
      <td class="muted">${esc(x.shares)}</td></tr>`).join('')}</table>` : '<div class="empty">No shared expenses yet.</div>'}</div>

    <div class="card table-wrap"><h3>Settlements</h3>${d.settlements.length ? `<table><tr><th>Date</th><th>Payment</th><th>Amount</th></tr>
      ${d.settlements.map(s => `<tr><td>${s.settled_on}</td><td>${esc(s.from_name)} → ${esc(s.to_name)}</td><td>${inr(s.amount)}</td></tr>`).join('')}</table>`
      : '<div class="empty">No settlements recorded.</div>'}</div>`;

  const $ = (sel) => app.querySelector(sel), xform = $('#xform');
  $('#back').onclick = showList;
  $('#delGroup').onclick = async () => {
    if (!confirm('Delete this group and all its expenses? This cannot be undone.')) return;
    try { toast((await api('/api/groups/' + id, 'DELETE')).message); showList(); } catch (e) { toast(e.message, 'error'); }
  };
  // show per-person amount boxes only for custom splits
  xform.split_type.onchange = () => xform.querySelectorAll('.cust').forEach(i => i.hidden = xform.split_type.value !== 'custom');

  xform.onsubmit = async (e) => {
    e.preventDefault();
    const type = xform.split_type.value;
    const picked = [...xform.querySelectorAll('.part')].filter(p => p.querySelector('input[type=checkbox]').checked);
    const participants = type === 'equal' ? picked.map(p => Number(p.dataset.id))
      : picked.map(p => ({ member_id: Number(p.dataset.id), amount: p.querySelector('.cust').value }));
    try {
      const r = await api(`/api/groups/${id}/expenses`, 'POST', { description: xform.description.value, amount: xform.amount.value,
        paid_by: xform.paid_by.value, date: xform.date.value, split_type: type, participants });
      toast(r.message); showGroup(id);
    } catch (err) { toast(err.message, 'error'); }
  };
  $('#mform').onsubmit = async (e) => {
    e.preventDefault();
    try { toast((await api(`/api/groups/${id}/members`, 'POST', { name: e.target.name.value })).message); showGroup(id); }
    catch (err) { toast(err.message, 'error'); }
  };
  app.onclick = async (e) => {
    const b = e.target.closest('[data-settle]');
    if (!b) return;
    const entered = prompt(`${b.dataset.label}. Amount (₹), up to ${inr(b.dataset.amount)}:`, b.dataset.amount);
    if (entered === null) return;   // cancelled
    b.disabled = true; // stops double-clicks; the server also rejects a repeated token
    try {
      const r = await api(`/api/groups/${id}/settlements`, 'POST',
        { from: b.dataset.from, to: b.dataset.to, amount: entered, date: today, token: b.dataset.token });
      toast(r.message); showGroup(id);
    } catch (err) { toast(err.message, 'error'); b.disabled = false; }
  };
}
showList();
