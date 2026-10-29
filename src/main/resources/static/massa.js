const byId = id => document.getElementById(id);
let activeBatch = null;
let activeItem = null;
let busy = false;
let generation = 0;
let suggestionsReady = false;
let separationReady = false;
let separationNeedsReview = false;

function notice(text, error = false) {
  byId('batch-notice').textContent = text;
  byId('batch-notice').className = `notice ${error ? 'error' : ''}`;
}
async function request(path, options = {}) {
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || data.detail || `Erro HTTP ${response.status}`);
  return data;
}
function post(data) { return {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(data)}; }
function cell(text) { const td = document.createElement('td'); td.textContent = text; return td; }
function setBusy(value) {
  busy = value;
  byId('create-request').disabled = value || !activeItem || !suggestionsReady || !separationReady;
  byId('next-request').disabled = value || !activeItem;
  byId('retry-request').disabled = value || !activeItem;
  document.querySelectorAll('#batch-results button').forEach(button => { button.disabled = value || !button.dataset.serviceId; });
  document.querySelectorAll('#batch-list button').forEach(button => { button.disabled = value; });
  for (const id of ['batch-upload-button', 'batch-text-button', 'source-excel', 'source-text', 'batch-text', 'batch-file']) byId(id).disabled = value;
}
function clearSeparation() {
  separationReady = false;
  separationNeedsReview = false;
  for (const id of ['request-separation', 'request-separation-status', 'request-separation-warning', 'request-analysis-tools']) byId(id).hidden = true;
  for (const id of ['request-standardized', 'request-activity', 'request-separation-status']) byId(id).textContent = '';
  byId('request-qualifiers').replaceChildren();
  byId('retry-request').textContent = 'Atualizar análise';
}
function separationStatus(text, error = false) {
  byId('request-separation-status').textContent = text;
  byId('request-separation-status').className = `separation-status ${error ? 'error' : ''}`;
  byId('request-separation-status').hidden = !text;
}
function renderSeparation(parts, original) {
  if (!parts || parts.original !== original.trim() || typeof parts.standardized !== 'string'
      || typeof parts.activity !== 'string' || typeof parts.needs_review !== 'boolean'
      || !Array.isArray(parts.qualifiers) || !parts.qualifiers.every(value => typeof value === 'string')
      || typeof parts.dictionary_version !== 'string' || !parts.dictionary_version) {
    separationStatus('Separação indisponível. Reinicie o Python com a versão atualizada e tente novamente antes de criar um código.', true);
    return false;
  }
  byId('request-standardized').textContent = parts.standardized;
  byId('request-activity').textContent = parts.needs_review ? 'Nenhuma atividade identificada' : parts.activity;
  const labels = parts.qualifiers.length ? parts.qualifiers : ['Nenhum qualificador identificado'];
  byId('request-qualifiers').replaceChildren(...labels.map(value => {
    const label = document.createElement('span');
    label.className = parts.qualifiers.length ? 'qualifier-chip' : 'separation-note';
    label.textContent = value;
    return label;
  }));
  separationReady = true;
  separationNeedsReview = parts.needs_review;
  byId('request-separation-warning').hidden = !parts.needs_review;
  byId('request-separation').hidden = false;
  separationStatus('');
  return true;
}
async function loadBatches() {
  const batches = await request('/api/catalog/batches');
  byId('batch-list').replaceChildren();
  if (!batches.length) byId('batch-list').textContent = 'Nenhum lote enviado ainda.';
  for (const batch of batches) {
    const li = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = `${batch.filename} · ${batch.pending} pendentes / ${batch.total} · ${batch.resolved} resolvidas · ${batch.invalid} inválidas`;
    button.disabled = busy;
    button.addEventListener('click', async () => {
      if (busy) return;
      activeBatch = batch.id;
      byId('review-section').hidden = false;
      byId('batch-export').href = `/api/catalog/batches/${encodeURIComponent(activeBatch)}/export`;
      await showPending(0);
    });
    li.append(button); byId('batch-list').append(li);
    if (batch.id === activeBatch) byId('batch-progress').textContent = `${batch.resolved} de ${batch.total} resolvidas; ${batch.pending} pendentes e ${batch.invalid} inválidas. Linhas inválidas aparecem no Excel de resultado.`;
  }
}
async function findPending(after) {
  while (true) {
    const rows = await request(`/api/catalog/batches/${encodeURIComponent(activeBatch)}/items?after=${after}&limit=100`);
    if (!rows.length) return null;
    const pending = rows.find(row => row.status === 'PENDING');
    if (pending) return pending;
    after = rows[rows.length - 1].row_number;
  }
}
async function showPending(after) {
  const ticket = ++generation;
  suggestionsReady = false;
  activeItem = null;
  clearSeparation();
  byId('request-original-label').hidden = true;
  byId('request-title').textContent = 'Carregando solicitação…';
  byId('request-description').textContent = '';
  byId('request-context').textContent = '';
  setBusy(true);
  byId('batch-results').replaceChildren();
  try {
    activeItem = await findPending(after);
    if (!activeItem && after > 0) activeItem = await findPending(0);
    await loadBatches();
    if (ticket !== generation) return;
    if (!activeItem) {
      byId('request-title').textContent = 'Revisão concluída';
      byId('request-description').textContent = 'Não há solicitações pendentes neste lote.';
      byId('request-context').textContent = 'Baixe o resultado para conferir as decisões e eventuais linhas inválidas.';
      notice('Todas as linhas válidas deste lote foram revisadas.');
      return;
    }
    const fields = activeItem.fields;
    byId('request-original-label').hidden = false;
    byId('request-analysis-tools').hidden = false;
    separationStatus('Separando atividade e qualificadores…');
    byId('request-title').textContent = `Linha ${activeItem.row_number} do lote`;
    byId('request-description').textContent = fields.descricao_item;
    byId('request-context').textContent = `Grupo: ${fields.grupo_servico || '—'} · Unidade: ${fields.unidade_medida || '—'} · Código solicitado: ${fields.codigo_servico || 'gerar LOCAL'}`;
    notice('Preparando os serviços semelhantes… A primeira sincronização pode demorar.');
    const data = await request('/api/search', post({query: fields.descricao_item}));
    if (ticket !== generation) return;
    suggestionsReady = true;
    renderSeparation(data.query_parts, fields.descricao_item);
    for (const result of data.results) {
      const tr = document.createElement('tr');
      const choose = document.createElement('button');
      choose.type = 'button'; choose.className = 'btn'; choose.textContent = 'Usar existente';
      choose.dataset.serviceId = result.catalog_id || '';
      choose.disabled = !result.catalog_id;
      choose.addEventListener('click', () => decide('EXISTING', result.catalog_id));
      const actionCell = document.createElement('td'); actionCell.append(choose);
      const match = result.correspondencia_exata ? 'Exato' : result.correspondencia_palavras ? 'contem' : `${(result.similaridade * 100).toFixed(1)}%`;
      tr.append(cell(`${result.codigo_servico} — ${result.descricao_item}`), cell(`${result.codigo_fornecedor || '—'} / ${result.unidade_medida || '—'}`), cell(match), actionCell);
      byId('batch-results').append(tr);
    }
    if (!data.results.length) {
      const tr = document.createElement('tr'); const td = cell('Nenhum serviço disponível no catálogo.'); td.colSpan = 4; tr.append(td); byId('batch-results').append(tr);
    }
    notice(separationNeedsReview ? 'A separação precisa de conferência. Leia o aviso antes de confirmar um novo cadastro.'
      : separationReady ? 'Confira a separação e as sugestões antes de escolher um serviço ou confirmar um novo cadastro.'
      : 'As sugestões estão disponíveis, mas a separação precisa ser carregada antes de criar um código.', !separationReady);
  } catch (error) {
    if (ticket !== generation) return;
    suggestionsReady = false;
    byId('batch-results').replaceChildren();
    clearSeparation();
    byId('request-analysis-tools').hidden = !activeItem;
    byId('retry-request').textContent = 'Tentar novamente';
    separationStatus('Não foi possível carregar a análise. Tente novamente para conferir esta solicitação.', true);
    notice(`${error.message} A solicitação continua pendente.`, true);
  } finally { if (ticket === generation) setBusy(false); }
}
async function decide(action, serviceId) {
  if (busy || !activeItem || !suggestionsReady || (action === 'CREATE' && !separationReady)) return;
  const confirmation = action === 'CREATE'
    ? (separationNeedsReview ? 'Nenhuma atividade principal foi identificada. Você conferiu a descrição original e deseja confirmar o novo cadastro?' : 'Confirmar um novo cadastro para esta linha?')
    : 'Vincular esta linha ao serviço selecionado?';
  if (!confirm(confirmation)) return;
  setBusy(true);
  try {
    await request(`/api/catalog/batches/${encodeURIComponent(activeBatch)}/items/${encodeURIComponent(activeItem.id)}/decision`, post({action, service_id: serviceId}));
    await showPending(activeItem.row_number);
  } catch (error) { notice(error.message, true); setBusy(false); }
}
byId('retry-request').addEventListener('click', () => { if (!busy && activeItem) showPending(activeItem.row_number - 1); });
byId('next-request').addEventListener('click', () => { if (!busy && activeItem) showPending(activeItem.row_number); });
byId('create-request').addEventListener('click', () => decide('CREATE'));
byId('batch-upload').addEventListener('submit', async event => {
  event.preventDefault(); if (busy) return;
  const file = byId('batch-file').files[0];
  if (!file || !file.name.toLowerCase().endsWith('.xlsx') || file.size > 15 * 1024 * 1024) return notice('Selecione um XLSX de até 15 MB.', true);
  setBusy(true);
  try {
    const form = new FormData(); form.append('file', file);
    const batch = await request('/api/catalog/batches', {method:'POST', body:form});
    activeBatch = batch.id;
    byId('review-section').hidden = false;
    byId('batch-export').href = `/api/catalog/batches/${encodeURIComponent(activeBatch)}/export`;
    await showPending(0);
  } catch (error) { notice(error.message, true); setBusy(false); }
});
loadBatches().catch(error => notice(error.message, true));

for (const source of ['excel', 'text']) {
  byId(`source-${source}`).addEventListener('click', () => {
    if (busy) return;
    for (const option of ['excel', 'text']) {
      const selected = source === option;
      byId(`${option}-panel`).hidden = !selected;
      byId(`source-${option}`).setAttribute('aria-pressed', String(selected));
      byId(`source-${option}`).className = `btn ${selected ? 'btn-primary' : ''}`;
    }
  });
}
byId('batch-text-form').addEventListener('submit', async event => {
  event.preventDefault(); if (busy) return;
  const text = byId('batch-text').value;
  if (!text.trim()) return notice('Cole ao menos uma solicitação.', true);
  setBusy(true);
  try {
    const batch = await request('/api/catalog/batches/text', post({text}));
    activeBatch = batch.id;
    byId('review-section').hidden = false;
    byId('batch-export').href = `/api/catalog/batches/${encodeURIComponent(activeBatch)}/export`;
    await showPending(0);
  } catch (error) { notice(error.message, true); setBusy(false); }
});
