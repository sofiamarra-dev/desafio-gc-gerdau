'use strict';
const $ = id => document.getElementById(id);
let correcting = false;
let entries = [], selectedCode = null, originalDraft = '', loaded = false, saving = false, previewing = false, previewGeneration = 0;
const normalize = text => String(text).normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
const variants = () => $('entry-variants').value.split(/\r?\n/).map(value => value.trim()).filter(Boolean);
const draft = () => ({code:$('entry-code').value.trim().toUpperCase(), meaning:$('entry-meaning').value.trim(), variants:variants(), active:$('entry-active').checked});
const dirty = () => !$('entry-form').hidden && (correcting || JSON.stringify(draft()) !== originalDraft);
function message(id, text, type = '') { const el = $(id); el.textContent = text; el.className = `message ${type}`; el.hidden = !text; }
function node(tag, text, className) { const el = document.createElement(tag); if (text !== undefined) el.textContent = text; if (className) el.className = className; return el; }
async function request(path, options = {}) {
  const response = await fetch(path, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) { const error = new Error(data.error || data.detail || `Não foi possível concluir (HTTP ${response.status}).`); error.status = response.status; throw error; }
  return data;
}
function jsonRequest(method, body) { return {method, headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}; }
function canLeave() { return !dirty() || confirm('Há alterações não salvas. Descartar essas alterações?'); }
function setBusy(value) {
  saving = value;
  $('new-entry').disabled = value || !loaded;
  $('discard-entry').disabled = value;
  $('start-correction').disabled = $('show-history').disabled = value;
  for (const el of $('entry-form').querySelectorAll('input, textarea')) el.disabled = value || (correcting && el.id === 'entry-active');
  for (const el of $('dictionary-list').querySelectorAll('button')) el.disabled = value || (correcting && el.id === 'entry-active');
  $('preview-button').disabled = value || previewing || !loaded;
  updateDraft();
}
function updateDraft() {
  const count = variants().length;
  $('variant-count').textContent = `${count} ${count === 1 ? 'variante' : 'variantes'}`;
  $('variant-chips').replaceChildren(...variants().slice(0, 12).map(value => node('span', value, 'chip')));
  if (count > 12) $('variant-chips').append(node('span', `+${count - 12}`, 'chip'));
  const changed = dirty();
  $('dirty-state').textContent = changed ? 'Alterações ainda não salvas' : 'Nenhuma alteração pendente';
  $('save-entry').disabled = saving || !changed;
  $('save-entry').textContent = saving ? 'Salvando…' : correcting ? 'Salvar correção' : selectedCode ? 'Salvar alterações' : 'Cadastrar sigla';
  $('editor-status').textContent = $('entry-active').checked ? 'Ativa' : 'Inativa';
  $('editor-status').className = `status-pill ${$('entry-active').checked ? '' : 'off'}`;
}
function renderList() {
  const search = normalize($('dictionary-search').value.trim()), filter = $('dictionary-filter').value;
  const visible = entries.filter(entry => (filter === 'all' || entry.active === (filter === 'active')) && normalize([entry.code, entry.meaning, ...entry.variants].join(' ')).includes(search));
  $('total-count').textContent = entries.length;
  $('visible-count').textContent = `${visible.length} ${visible.length === 1 ? 'resultado' : 'resultados'}`;
  $('dictionary-list').replaceChildren();
  $('list-state').hidden = visible.length > 0;
  $('list-state').textContent = entries.length ? 'Nenhuma sigla encontrada. Tente outra busca.' : 'Seu dicionário está vazio. Comece em Nova sigla.';
  for (const entry of visible) {
    const li = node('li'), button = node('button', undefined, `entry-button ${selectedCode === entry.code ? 'selected' : ''}`);
    button.type = 'button'; button.disabled = saving; button.setAttribute('aria-pressed', String(selectedCode === entry.code));
    button.setAttribute('aria-label', `${entry.code}, ${entry.meaning}, ${entry.active ? 'ativa' : 'inativa'}`);
    const info = node('span', undefined, 'entry-info');
    info.append(node('span', entry.meaning, 'entry-name'), node('span', `${entry.variants.length} ${entry.variants.length === 1 ? 'variante' : 'variantes'}`, 'entry-meta'));
    button.append(node('span', entry.code, 'entry-code'), info);
    if (!entry.active) button.append(node('span', 'Inativa', 'inactive-dot'));
    button.addEventListener('click', () => { if (!saving && canLeave()) selectEntry(entry.code); });
    li.append(button); $('dictionary-list').append(li);
  }
}
function selectEntry(code) {
  const entry = entries.find(item => item.code === code);
  selectedCode = entry ? entry.code : null;
  correcting = false;
  $('correction-actions').hidden = !entry;
  $('correction-fields').hidden = true;
  $('correction-reason').value = '';
  $('correction-reason').required = false;
  $('correction-confirmed').checked = false;
  $('correction-history').hidden = true;
  $('editor-empty').hidden = true; $('entry-form').hidden = false;
  $('entry-code').value = entry?.code || ''; $('entry-meaning').value = entry?.meaning || '';
  $('entry-variants').value = entry?.variants.join('\n') || ''; $('entry-active').checked = entry?.active ?? true;
  $('entry-code').readOnly = $('entry-meaning').readOnly = Boolean(entry);
  $('entry-active').disabled = false;
  $('editor-kicker').textContent = entry ? 'Sigla cadastrada' : 'Novo cadastro';
  $('editor-title').textContent = entry ? `Editar ${entry.code}` : 'Nova sigla';
  $('identity-note').textContent = entry ? 'Para mudar um significado incorreto, use Corrigir cadastro. A sigla padronizada permanece fixa.' : 'Confira o significado: a sigla e o significado oficial ficam protegidos após o cadastro.';
  originalDraft = JSON.stringify(draft());
  message('form-message', ''); updateDraft(); renderList();
}
async function load() {
  loaded = false; $('retry-load').hidden = true; $('list-state').hidden = false; $('list-state').textContent = 'Carregando dicionário…';
  try {
    const data = await request('/api/abbreviations');
    entries = data.entries.sort((a,b) => a.code.localeCompare(b.code, 'pt-BR'));
    loaded = true; $('dictionary-search').disabled = $('dictionary-filter').disabled = false;
    $('new-entry').disabled = false; $('preview-button').disabled = false;
    if (entries.length) selectEntry(entries[0].code); else renderList();
    message('page-message', '');
  } catch (error) { $('list-state').textContent = 'Não foi possível carregar suas siglas.'; $('retry-load').hidden = false; message('page-message', `${error.message} Verifique se a aplicação e o banco estão disponíveis.`, 'error'); }
}
$('start-correction').addEventListener('click', () => {
  if (saving || !selectedCode || !canLeave()) return;
  selectEntry(selectedCode);
  correcting = true;
  $('correction-fields').hidden = false;
  $('correction-reason').required = true;
  $('entry-meaning').readOnly = false;
  $('entry-active').disabled = true;
  $('identity-note').textContent = 'Corrija o significado e/ou as variantes. O estado ativo permanece igual; explique o motivo e confira antes de salvar.';
  updateDraft();
});
$('show-history').addEventListener('click', async () => {
  if (saving || !selectedCode) return;
  const code = selectedCode;
  $('history-list').textContent = 'Carregando correções…'; $('correction-history').hidden = false;
  try {
    const rows = await request(`/api/abbreviations/${encodeURIComponent(code)}/corrections`);
    if (code !== selectedCode) return;
    $('history-list').replaceChildren();
    if (!rows.length) $('history-list').textContent = 'Nenhuma correção registrada para esta sigla.';
    for (const row of rows) {
      const item = node('div', undefined, 'history-item');
      item.append(node('h3', `${code} · ${new Date(row.created_at).toLocaleString('pt-BR')}`), node('p', `Motivo: ${row.reason}`), node('p', `Antes: ${row.before.meaning} · Variantes: ${row.before.variants.join(', ') || 'nenhuma'}`), node('p', `Depois: ${row.after.meaning} · Variantes: ${row.after.variants.join(', ') || 'nenhuma'}`));
      $('history-list').append(item);
    }
  } catch (error) { if (code === selectedCode) $('history-list').textContent = error.message; }
});
$('entry-form').addEventListener('input', () => { updateDraft(); message('form-message', ''); });
$('entry-form').addEventListener('submit', async event => {
  event.preventDefault(); if (saving || !dirty() || !$('entry-form').reportValidity()) return;
  const value = draft();
  if (!value.meaning) return message('form-message', 'Informe o significado oficial.', 'error');
  if (value.variants.length > 100 || value.variants.some(v => v.length > 80)) return message('form-message', 'Use até 100 variantes, com no máximo 80 caracteres em cada linha.', 'error');
  if (correcting && (!$('correction-confirmed').checked || $('correction-reason').value.trim().length < 10)) return message('form-message', 'Informe o motivo e confirme a revisão do significado e das variantes.', 'error');
  const previous = entries.find(item => item.code === selectedCode);
  if (!correcting && (!previous || value.variants.some(v => !previous.variants.includes(v)))) {
    if (!confirm(`Todas as variantes informadas significam “${value.meaning}”? Não inclua expressões de outro significado.`)) return;
  }
  setBusy(true); message('form-message', '');
  ++previewGeneration;
  try {
    const entry = entries.find(item => item.code === selectedCode);
    const body = correcting ? {meaning:value.meaning, variants:value.variants, reason:$('correction-reason').value.trim(), version:entry.version, confirmed:$('correction-confirmed').checked} : entry ? {variants:value.variants, active:value.active, version:entry.version} : value;
    const path = correcting ? `/api/abbreviations/${encodeURIComponent(entry.code)}/corrections` : entry ? `/api/abbreviations/${encodeURIComponent(entry.code)}` : '/api/abbreviations';
    const data = await request(path, jsonRequest(correcting || !entry ? 'POST' : 'PUT', body));
    entries = data.entries.sort((a,b) => a.code.localeCompare(b.code, 'pt-BR'));
    selectEntry(value.code);
    $('preview-result').hidden = true; message('preview-message', '');
    message('page-message', `${value.code} salva. As alterações serão aplicadas na próxima busca; a primeira atualização pode levar mais tempo.`, 'success');
  } catch (error) {
    message('form-message', error.status === 409 ? `${error.message} Suas alterações continuam aqui para conferência.` : error.message, 'error');
  } finally { setBusy(false); }
});
$('new-entry').addEventListener('click', () => { if (!saving && canLeave()) { selectEntry(null); $('entry-code').focus(); } });
$('discard-entry').addEventListener('click', () => { if (!saving && canLeave()) { if (selectedCode) selectEntry(selectedCode); else if (entries.length) selectEntry(entries[0].code); else { $('entry-form').hidden = true; $('editor-empty').hidden = false; } } });
$('dictionary-search').addEventListener('input', renderList); $('dictionary-filter').addEventListener('change', renderList);
$('retry-load').addEventListener('click', load);
$('preview-form').addEventListener('submit', async event => {
  event.preventDefault(); if (previewing || saving || !loaded || !$('preview-form').reportValidity()) return;
  const text = $('preview-text').value.trim(); if (!text) return;
  previewing = true; $('preview-button').disabled = true; $('preview-button').textContent = 'Verificando…';
  const ticket = ++previewGeneration;
  $('preview-result').hidden = true; message('preview-message', '');
  try {
    const result = await request('/api/abbreviations/preview', jsonRequest('POST', {text}));
    if (ticket !== previewGeneration) return;
    $('preview-activity').textContent = result.activity || 'Nenhuma atividade identificada';
    $('preview-standardized').textContent = result.standardized;
    $('preview-qualifiers').replaceChildren(...(result.qualifiers.length ? result.qualifiers.map(value => node('span', value, 'chip')) : [node('span', 'Nenhum qualificador', 'muted')]));
    $('preview-result').hidden = false;
    if (result.needs_review) message('preview-message', 'Esta descrição precisa de uma atividade principal. Confira o texto antes de usar.', 'error');
  } catch (error) { message('preview-message', error.message, 'error'); }
  finally { previewing = false; $('preview-button').disabled = saving || !loaded; $('preview-button').textContent = 'Ver separação →'; }
});
window.addEventListener('beforeunload', event => { if (dirty() || saving) { event.preventDefault(); event.returnValue = ''; } });
for (const link of document.querySelectorAll('a[href="menu.html"]')) link.addEventListener('click', event => { if (saving || !canLeave()) event.preventDefault(); });
load();
