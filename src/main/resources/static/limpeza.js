const cleanupElement = id => document.getElementById(id);
let cleanup = null;
let cleanupBusy = false;
let pauseRequested = false;
let listButtons = [];
let decisionButtons = [];

function cleanupNotice(message, error = false) {
  cleanupElement('cleanup-notice').textContent = message;
  cleanupElement('cleanup-notice').className = `notice ${error ? 'error' : ''}`;
}
async function cleanupRequest(path, data) {
  const response = await fetch(path, data === undefined ? {} : {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)
  });
  let result;
  try { result = await response.json(); } catch (_) { throw new Error('O serviço retornou uma resposta inválida. Sua cópia continua salva; tente retomar a revisão.'); }
  if (!response.ok) {
    const message = result.error || result.detail || result.message || `Erro HTTP ${response.status}`;
    throw new Error(typeof message === 'string' ? message : 'Não foi possível concluir a operação. Sua cópia continua salva.');
  }
  return result;
}
function cleanupPath(suffix = '') { return `/api/cleanups/${encodeURIComponent(cleanup.id)}${suffix}`; }
function setCleanupBusy(value, canPause = false) {
  cleanupBusy = value;
  for (const id of ['start-cleanup','skip-verified','refresh-cleanups']) cleanupElement(id).disabled = value;
  listButtons.forEach(button => { button.disabled = value; });
  decisionButtons.forEach(button => { button.disabled = value; });
  cleanupElement('keep-distinct').disabled = value || !cleanup?.analysis_ready;
  cleanupElement('continue-cleanup').disabled = value || !cleanup || cleanup.status === 'APPLIED' || cleanup.pending === 0;
  cleanupElement('apply-cleanup').disabled = value || !cleanup || cleanup.status === 'APPLIED' || cleanup.pending !== 0;
  cleanupElement('pause-cleanup').hidden = !canPause;
  cleanupElement('pause-cleanup').disabled = pauseRequested;
}
function appendCleanupFields(container, item) {
  const fields = [['Código','codigo_servico'],['Fornecedor','codigo_fornecedor'],['Grupo','grupo_servico'],['Unidade','unidade_medida'],['Descrição da unidade','unidade_descricao']];
  container.replaceChildren(...fields.map(([label,key]) => {
    const group = document.createElement('div');
    const term = document.createElement('dt'); term.textContent = label;
    const value = document.createElement('dd'); value.textContent = item[key] || '—';
    group.append(term,value); return group;
  }));
}
function semanticLabel(result) {
  if (result.semantic_exact === true) return '100% · embeddings';
  const score = Number(result.similaridade_semantica);
  if (!Number.isFinite(score)) return 'Similaridade indisponível';
  // Um valor arredondado nunca é apresentado como a equivalência automática.
  return `${Math.min(99.9999, Math.max(0, score * 100)).toLocaleString('pt-BR', {maximumFractionDigits: 4})}% · embeddings`;
}
function renderCleanup(detail) {
  cleanup = detail;
  cleanupElement('cleanup-section').hidden = false;
  const applied = detail.status === 'APPLIED';
  cleanupElement('cleanup-status').textContent = applied ? 'Aplicada à base' : detail.pending === 0 ? 'Aguardando confirmação' : 'Cópia em revisão';
  cleanupElement('cleanup-date').textContent = `Cópia ${detail.id} · ${detail.created_at ? new Date(detail.created_at).toLocaleString('pt-BR') : 'salva localmente'}`;
  for (const key of ['total','pending','verified','replaced']) cleanupElement(`count-${key}`).textContent = String(detail[key] ?? 0);
  cleanupElement('cleanup-progress').max = Math.max(1, detail.total);
  cleanupElement('cleanup-progress').value = detail.total - detail.pending;
  cleanupElement('cleanup-step').textContent = applied ? 'A limpeza foi aplicada. As próximas pesquisas usam o catálogo atualizado.' : detail.pending === 0 ? 'Todos os registros foram analisados. Confira o resumo antes de confirmar.' : `${detail.total - detail.pending} de ${detail.total} registros analisados. A base permanente continua sem alterações desta limpeza.`;
  cleanupElement('continue-cleanup').hidden = applied || detail.pending === 0 || detail.analysis_ready === true;
  cleanupElement('cleanup-summary').hidden = detail.pending !== 0;
  cleanupElement('summary-title').textContent = applied ? 'Limpeza aplicada' : 'Cópia revisada';
  cleanupElement('summary-text').textContent = `${detail.verified} registros mantidos e ${detail.replaced} substituídos, de um total de ${detail.total}.`;
  cleanupElement('apply-cleanup').hidden = applied;
  cleanupElement('apply-help').hidden = applied;
  cleanupElement('cleanup-review').hidden = applied || !detail.current || detail.analysis_ready !== true;
  decisionButtons = [];
  cleanupElement('cleanup-candidates').replaceChildren();
  if (detail.current && detail.analysis_ready === true && !applied) {
    cleanupElement('current-description').textContent = detail.current.descricao_item;
    appendCleanupFields(cleanupElement('current-fields'), detail.current);
    for (const candidate of detail.results || []) {
      const card = document.createElement('article'); card.className = 'cleanup-candidate';
      const label = document.createElement('span'); label.className = 'review-label'; label.textContent = 'Já verificado';
      const title = document.createElement('h4'); title.textContent = candidate.descricao_item;
      const score = document.createElement('span'); score.className = 'cleanup-score'; score.textContent = semanticLabel(candidate);
      const fields = document.createElement('dl'); fields.className = 'cleanup-fields'; appendCleanupFields(fields,candidate);
      const actions = document.createElement('div'); actions.className = 'workflow-actions';
      for (const [action,text] of [['KEEP_VERIFIED','É o mesmo serviço — manter o já verificado'],['KEEP_CURRENT','É o mesmo serviço — manter o registro em análise']]) {
        const button = document.createElement('button'); button.type = 'button'; button.className = action === 'KEEP_VERIFIED' ? 'btn btn-primary' : 'btn'; button.textContent = text;
        button.addEventListener('click', () => cleanupDecision(action,candidate.catalog_id));
        decisionButtons.push(button); actions.append(button);
      }
      card.append(label,title,score,fields,actions); cleanupElement('cleanup-candidates').append(card);
    }
    if (!detail.results?.length) cleanupElement('cleanup-candidates').textContent = 'Nenhum semelhante foi retornado. Você pode manter o registro como distinto.';
  }
  setCleanupBusy(cleanupBusy);
}
function logAutomatic(automatic) {
  const item = document.createElement('li');
  const current = automatic.item || {};
  const kept = automatic.kept || current;
  item.textContent = current.catalog_id === kept.catalog_id
    ? `Mantido como referência: ${current.descricao_item || current.codigo_servico || 'registro'}.`
    : `100% pelos embeddings: ${current.descricao_item || current.codigo_servico} → mantido ${kept.codigo_servico || kept.descricao_item}.`;
  cleanupElement('automatic-log').append(item);
}
async function loadCleanups() {
  const summaries = await cleanupRequest('/api/cleanups');
  cleanupElement('cleanup-list').replaceChildren(); listButtons = [];
  for (const summary of summaries) {
    const li = document.createElement('li'); const button = document.createElement('button'); button.type = 'button';
    button.textContent = `${summary.status === 'APPLIED' ? 'Aplicada' : 'Retomar'} · ${summary.created_at ? new Date(summary.created_at).toLocaleString('pt-BR') : summary.id} · ${summary.pending} pendentes de ${summary.total}`;
    button.disabled = cleanupBusy;
    button.addEventListener('click', () => openCleanup(summary.id));
    li.append(button); cleanupElement('cleanup-list').append(li); listButtons.push(button);
  }
  if (!summaries.length) cleanupElement('cleanup-list').textContent = 'Nenhuma limpeza iniciada ainda.';
}
async function openCleanup(id) {
  if (cleanupBusy) return;
  setCleanupBusy(true); pauseRequested = false;
  try {
    renderCleanup(await cleanupRequest(`/api/cleanups/${encodeURIComponent(id)}`));
    cleanupElement('automatic-log').replaceChildren();
    cleanupNotice(cleanup.status === 'APPLIED' ? 'Esta cópia já foi aplicada à base permanente.' : 'Cópia recuperada. Suas decisões anteriores estão salvas.');
  } catch (error) { cleanupNotice(error.message,true); }
  finally { setCleanupBusy(false); }
}
async function advanceCleanup() {
  if (cleanupBusy || !cleanup || cleanup.pending === 0 || cleanup.status === 'APPLIED') return;
  pauseRequested = false; setCleanupBusy(true,true);
  try {
    while (!pauseRequested && cleanup.pending > 0) {
      cleanupNotice('Comparando o próximo registro somente com os já verificados…');
      const detail = await cleanupRequest(cleanupPath('/next'),{});
      renderCleanup(detail);
      if (detail.automatic) logAutomatic(detail.automatic);
      if (!detail.automatic || detail.pending === 0) break;
      setCleanupBusy(true,true);
      // Devolve o controle à página entre registros para permitir pausar.
      await new Promise(resolve => setTimeout(resolve,0));
    }
    cleanupNotice(pauseRequested ? 'Revisão pausada. O progresso está salvo na cópia local.' : cleanup.pending === 0 ? 'Cópia revisada. A base permanente só mudará após sua confirmação.' : 'Compare os registros e escolha qual manter. A decisão será salva somente na cópia.');
    await loadCleanups();
  } catch (error) { cleanupNotice(`${error.message} O progresso já confirmado está salvo; tente continuar.`,true); }
  finally { setCleanupBusy(false); }
}
async function cleanupDecision(action,candidateId) {
  if (cleanupBusy || !cleanup?.analysis_ready || !cleanup.current) return;
  setCleanupBusy(true);
  try {
    renderCleanup(await cleanupRequest(cleanupPath('/decision'),{action,candidate_id:candidateId,current_id:cleanup.current.catalog_id}));
    cleanupNotice('Decisão salva na cópia.');
    await loadCleanups();
  } catch (error) { cleanupNotice(error.message,true); return; }
  finally { setCleanupBusy(false); }
  await advanceCleanup();
}
cleanupElement('start-cleanup').addEventListener('click', async () => {
  if (cleanupBusy) return;
  setCleanupBusy(true);
  try {
    renderCleanup(await cleanupRequest('/api/cleanups',{skip_verified:cleanupElement('skip-verified').checked}));
    cleanupElement('automatic-log').replaceChildren();
    await loadCleanups();
  } catch (error) { cleanupNotice(error.message,true); return; }
  finally { setCleanupBusy(false); }
  await advanceCleanup();
});
cleanupElement('continue-cleanup').addEventListener('click',advanceCleanup);
cleanupElement('pause-cleanup').addEventListener('click', () => {
  pauseRequested = true;
  cleanupElement('pause-cleanup').disabled = true;
  cleanupNotice('A revisão será pausada assim que este registro terminar.');
});
cleanupElement('keep-distinct').addEventListener('click', () => cleanupDecision('DISTINCT'));
cleanupElement('refresh-cleanups').addEventListener('click', async () => {
  if (cleanupBusy) return;
  setCleanupBusy(true);
  try { await loadCleanups(); } catch (error) { cleanupNotice(error.message,true); }
  finally { setCleanupBusy(false); }
});
cleanupElement('apply-cleanup').addEventListener('click', async () => {
  if (cleanupBusy || !cleanup || cleanup.pending !== 0 || cleanup.status === 'APPLIED') return;
  if (!confirm(`Usar esta cópia como base permanente? Serão mantidos ${cleanup.verified} registros e vinculados ${cleanup.replaced} substituídos. Esta ação atualiza o catálogo usado nas pesquisas.`)) return;
  setCleanupBusy(true);
  try {
    const result = await cleanupRequest(cleanupPath('/apply'),{confirmed:true});
    renderCleanup(result);
    cleanupNotice(result.warning || 'Limpeza aplicada à base permanente. O histórico dos registros foi preservado.');
    await loadCleanups();
  } catch (error) { cleanupNotice(error.message,true); }
  finally { setCleanupBusy(false); }
});
setCleanupBusy(false);
loadCleanups().catch(error => cleanupNotice(error.message,true));
