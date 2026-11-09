(() => {
  const el=id=>document.getElementById(id);
  const cleaning=document.body.dataset.browser==='limpeza';
  let analysis=null,page=1,pages=1,generation=0,timer=null,busy=false,acknowledged=!cleaning,dirty=false,version=null;
  const message=(text,error=false)=>{el('browser-message').textContent=text;el('browser-message').className=`notice ${error?'error':''}`;};
  async function request(url,data){
    const response=await fetch(url,data===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
    const result=await response.json();if(!response.ok)throw Error(result.error||result.message||result.detail||`Erro HTTP ${response.status}`);return result;
  }
  const post=(url,data={})=>request(url,data);
  function parameters(){
    const params=new URLSearchParams(new FormData(el('filters')));params.set('page',page);params.set('size',el('page-size').value);
    params.set('sort',el('sort').value);params.set('order',el('order').value);
    if(cleaning)params.set('pending_only',el('pending-only').checked);
    if(analysis)params.set('analysis',analysis);return params;
  }
  function controls(value){
    busy=value;
    for(const id of ['previous','back-five','back-ten','back-fifty'])el(id).disabled=value||page<=1;
    for(const id of ['next','forward-five','forward-ten','forward-fifty'])el(id).disabled=value||page>=pages;
    if(cleaning){el('retry-cleanup').disabled=value;el('discard-cleanup').disabled=value;el('save-cleanup').disabled=value||!dirty||!acknowledged;}
    else el('analyze').disabled=value;
    document.querySelectorAll('#code-rows button').forEach(button=>button.disabled=value||!acknowledged);
  }
  function updateState(data){
    if(!cleaning)return;
    dirty=data.dirty===true;acknowledged=data.acknowledged===true;
    if(typeof data.version==='number')version=data.version;
    window.cleanupGuard.update(data);
    el('unsaved-status').textContent=dirty?`Há alterações pendentes${Number(data.standardized)>0?` · ${data.standardized} descrição(ões) padronizada(s) pelo dicionário`:''}. O banco ainda não foi alterado.`:'Nenhuma alteração pendente.';
  }
  async function acknowledge(data){
    updateState(data);
    if(!acknowledged){const result=await window.cleanupGuard.acknowledge(data);Object.assign(data,result);updateState(result);}
  }
  function stat(value,label){const box=document.createElement('div');box.className='browser-stat';const number=document.createElement('strong');number.textContent=String(value);const text=document.createElement('span');text.textContent=label;box.append(number,text);return box;}
  function cell(text){const td=document.createElement('td');td.textContent=text;return td;}
  function label(row){return row.contains?'contém':typeof row.similarity==='number'?`${(row.similarity*100).toFixed(2)}%`:'Sem par / não analisado';}
  async function openCode(row){
    if(busy||!acknowledged)return;
    if(cleaning){window.cleanupGuard.allowInternalNavigation();location.href=`revisao.html?selected=${encodeURIComponent(row.catalog_id)}`;return;}
    try{controls(true);if(!analysis)analysis=(await post('/api/catalog/dashboard/analysis')).id;
      location.href=`dashboard-detalhe.html?analysis=${encodeURIComponent(analysis)}&selected=${encodeURIComponent(row.catalog_id)}`;
    }catch(error){message(error.message,true);controls(false);}
  }
  async function keepCode(row){
    if(busy||!acknowledged||version===null)return;
    if(!confirm('Manter este código e aumentar sua verificação, sem excluir semelhantes? A alteração só será gravada ao salvar.'))return;
    controls(true);
    try{
      updateState(await post('/api/cleanups/session/decision',{selected:row.catalog_id,candidate:null,action:'KEEP',version}));
      await load();
    }catch(error){message(error.message,true);controls(false);}
  }
  function render(data){
    const summary=data.summary||{};page=data.page||1;pages=data.pages||1;
    if(cleaning)updateState(data);
    el('stats').replaceChildren(...(cleaning?[stat(data.pending??0,'Pendentes de revisão'),stat(data.removed??0,'Exclusões pendentes de salvar')]:
      [stat(summary.total??summary.TOTAL??data.total??0,'Códigos no catálogo / seleção'),stat(summary.verified??summary.VERIFIED??'—','Verificados'),stat(summary.partial??summary.PARTIAL??'—','Verificação parcial')]));
    el('code-rows').replaceChildren();
    for(const row of data.items||[]){
      const tr=document.createElement('tr');
      const match=cell('');const tag=document.createElement('span');tag.className=`browser-match ${row.contains?'contains':''}`;tag.textContent=label(row);match.append(tag);
      if(row.pair_id && row.pair_id!==row.catalog_id){const reference=document.createElement('div');reference.className='request-context';reference.textContent=`Par: ${row.pair_code||'—'} · ${row.pair_description||''} · Fornecedor ${row.pair_supplier||'—'}`;match.append(reference);}
      const action=cell('');const button=document.createElement('button');button.type='button';button.className='btn';button.textContent=cleaning?'Revisar 20 semelhantes':'Ver semelhantes';button.disabled=!acknowledged;button.addEventListener('click',()=>openCode(row));action.append(button);
      if(cleaning){action.className='browser-row-actions';const keep=document.createElement('button');keep.type='button';keep.className='btn';keep.textContent='Manter código';keep.title='Manter este código sem excluir semelhantes e aumentar sua verificação';keep.disabled=!acknowledged;keep.addEventListener('click',()=>keepCode(row));action.append(keep);}
      const verification=row.verification_status==='VERIFIED'?'Verificado':row.verification_status==='PARTIAL'?'Parcial':'Pendente';
      tr.append(cell(`${row.codigo_servico} / ${row.codigo_fornecedor||'—'}`),cell(row.descricao_item),cell(`${row.grupo_servico||'—'} / ${row.unidade_medida||'—'}`),cell(`${verification} · ${row.cleanup_passes}/${row.required_cleanup_passes}${row.verification_pending?' · pendente de salvar':''}`),match,action);el('code-rows').append(tr);
    }
    if(!data.items?.length){const tr=document.createElement('tr');const td=cell('Nenhum código nesta seleção.');td.colSpan=6;td.className='browser-empty';tr.append(td);el('code-rows').append(tr);}
    const total=cleaning?data.catalog_total??0:data.total??0;
    el('page-status').textContent=`Página ${page} de ${pages} · ${total} códigos na seleção`;
  }
  async function load(){
    clearTimeout(timer);const ticket=++generation;controls(true);el('code-rows').replaceChildren();el('page-status').textContent='Carregando…';
    try{
      const data=await request(cleaning?`/api/cleanups/session?${parameters()}`:`/api/catalog/dashboard?${parameters()}`);
      if(ticket!==generation)return;
      if(cleaning){await acknowledge(data);if(ticket!==generation)return;}
      const state=data.analysis_status;
      if(state&&state.status!=='READY'){
        if(state.status==='ERROR')throw Error(state.error||'A análise falhou. Tente atualizar a análise.');
        message(`Analisando em segundo plano… ${state.progress||0} códigos processados. A primeira análise pode demorar.`);
        timer=setTimeout(load,1800);return;
      }
      if(cleaning)data.acknowledged=acknowledged;
      render(data);message(cleaning?'“Contém” aparece antes dos pares semânticos. Exclusões e verificações só são gravadas ao salvar.':'Catálogo carregado.');
    }catch(error){if(ticket===generation){message(error.message,true);el('page-status').textContent='Não foi possível carregar. Use Atualizar análise ou Filtrar para tentar novamente.';}}
    finally{if(ticket===generation)controls(false);}
  }
  async function analyze(){
    controls(true);try{analysis=(await post('/api/catalog/dashboard/analysis')).id;el('sort').value='similarity';el('order').disabled=true;page=1;await load();}
    catch(error){message(error.message,true);controls(false);}
  }
  el('filters').addEventListener('submit',event=>{event.preventDefault();page=1;load();});
  for(const id of ['page-size','order'])el(id).addEventListener('change',()=>{page=1;load();});
  el('sort').addEventListener('change',()=>{page=1;el('order').disabled=el('sort').value==='similarity';if(!cleaning&&el('sort').value==='similarity'&&!analysis)analyze();else load();});
  for(const [id,step] of [['previous',-1],['back-five',-5],['back-ten',-10],['back-fifty',-50],['next',1],['forward-five',5],['forward-ten',10],['forward-fifty',50]]){
    el(id).addEventListener('click',()=>{
      if(busy)return;
      const target=Math.max(1,Math.min(pages,page+step));
      if(target!==page){page=target;load();}
    });
  }
  if(cleaning){
    el('sort').value='similarity';el('order').disabled=true;el('pending-label').hidden=false;
    el('pending-only').addEventListener('change',()=>{page=1;load();});
    el('retry-cleanup').addEventListener('click',()=>{if(!busy)initialize();});
    el('discard-cleanup').addEventListener('click',async()=>{
      if(busy||!confirm('Descartar as alterações ainda não salvas e iniciar uma limpeza com a base atual?'))return;
      controls(true);try{updateState(await post('/api/cleanups/session/discard',{}));page=1;await initialize();}catch(error){message(error.message,true);controls(false);}
    });
    el('save-cleanup').addEventListener('click',async()=>{
      if(busy||!dirty||!acknowledged)return;controls(true);
      try{await window.cleanupGuard.save();}catch(error){message(error.message,true);}finally{controls(false);}
    });
    window.addEventListener('cleanup-saved',event=>{updateState(event.detail);page=1;load();});
    window.addEventListener('pageshow',event=>{
      if(!event.persisted)return;
      // Não exibe linhas restauradas do cache que já saíram da revisão.
      el('code-rows').replaceChildren();page=1;initialize();
    });
    async function initialize(){
      controls(true);
      try{
        // Recupera o aviso de saída mesmo quando a sessão de revisão ficou desatualizada.
        try{updateState(await request('/api/cleanups/session/status'));}catch(_){}
        const data=await post('/api/cleanups/session/open',{});await acknowledge(data);await load();
      }catch(error){message(error.message,true);controls(false);}
    }
    initialize();
  }else{el('analyze').addEventListener('click',analyze);load();}
})();
