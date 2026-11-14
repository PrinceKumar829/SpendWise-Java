// Monthly income history, shown below the table
async function loadMonthly() {
  let box = document.getElementById('monthly');
  if (!box) { box = document.createElement('div'); box.id = 'monthly'; box.className = 'card'; document.getElementById('app').appendChild(box); }
  const r = await api('/api/income/monthly');
  box.innerHTML = '<h3>Monthly income history</h3>' + (r.data.length
    ? '<table><tr><th>Month</th><th>Entries</th><th>Total</th></tr>' + r.data.map(m => `<tr><td>${m.month}</td><td>${m.entries}</td><td>${inr(m.total)}</td></tr>`).join('') + '</table>'
    : '<div class="empty">No income recorded yet.</div>');
}
initRecords({
  page: 'income', title: 'Income', endpoint: '/api/income', typeLabel: 'Source', descRequired: false,
  types: ['Salary','Pocket Money','Freelance','Scholarship','Gift','Others'], onLoaded: loadMonthly
});
