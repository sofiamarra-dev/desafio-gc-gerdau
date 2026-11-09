(() => {
  const el=id=>document.getElementById(id),params=new URLSearchParams(location.search);
  const readonly=document.body.dataset.readonly==='true',selected=params.get('selected'),analysis=params.get('analysis');
  let version=null,busy=false,dirty=false,acknowledged=readonly;
  el('back-to-list').href=readonly?'dashboard.html':'limpeza.html';
  function message(text,error=false){el('review-message').textContent=text;el('review-message').className=`notice ${error?'error':''}`;}
  async function request(url,data){const response=await fetch(url,data===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});const result=await response.json();if(!response.ok)throw Error(result.error||result.detail||result.message||'Falha na operação.');return result;}
  function field(label,value){const group=document.createElement('div'),dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=value||'—';group.append(dt,dd);return group;}
  function update(data){
    if(readonly)return;
    window.cleanupGuard.update(data);dirty=data.dirty===true;acknowledged=data.acknowledged===true;
    if(typeof data.version==='number')version=data.version;
    el('unsaved-status').textContent=dirty?'Há exclusões ou verificações pendentes de salvar.':'Nenhuma alteração pendente.';
    el('save-cleanup').disabled=busy||!dirty||!acknowledged;
  }
  function controls(value){busy=value;document.querySelectorAll('#similar-codes button, #keep-code').forEach(button=>button.disabled=value||!acknowledged);if(!readonly)el('save-cleanup').disabled=value||!dirty||!acknowledged;}
  async function decide(action,candidate){
    if(busy||version===null||!acknowledged)return;
    const question=action==='KEEP'?'Manter este código e aumentar sua verificação, sem excluir semelhantes?':action==='KEEP_SELECTED'?'Manter o código inicial, aumentar sua verificação e retirar o semelhante da revisão?':'Manter o semelhante e retirar o código inicial da revisão?';
    if(!confirm(question))return;controls(true);
    try{const data=await request('/api/cleanups/session/decision',{selected,candidate,action,version});update(data);window.cleanupGuard.allowInternalNavigation();location.href=el('back-to-list').href;}
    catch(error){message(`${error.message} Atualize a página para conferir o estado atual.`,true);}
  }
  async function load(){
    if(!selected||(readonly&&!analysis)){message('Selecione um código na lista primeiro.',true);return;}
    controls(true);
    try{
      if(!readonly){const state=await request('/api/cleanups/session/status');update(state);if(!state.acknowledged)update(await window.cleanupGuard.acknowledge(state));}
      const url=readonly?`/api/catalog/dashboard/${encodeURIComponent(analysis)}/items/${encodeURIComponent(selected)}`:`/api/cleanups/session/items/${encodeURIComponent(selected)}`;
      const data=await request(url);
      if(data.analysis_status){if(data.analysis_status.status==='ERROR')throw Error(data.analysis_status.error);message('Preparando os semelhantes…');setTimeout(load,1800);return;}
      if(!readonly)update(data);
      version=data.version;el('selected-description').textContent=data.selected.descricao_item;
      el('selected-context').textContent=`Código ${data.selected.codigo_servico} · Fornecedor ${data.selected.codigo_fornecedor||'—'} · Grupo ${data.selected.grupo_servico||'—'} · Unidade ${data.selected.unidade_medida||'—'} · Verificação ${data.selected.cleanup_passes}/${data.selected.required_cleanup_passes}${data.selected.verification_pending?' (pendente de salvar)':''}`;
      el('keep-code').hidden=readonly;el('similar-codes').replaceChildren();
      for(const row of data.results){
        const card=document.createElement('article');card.className='similar-card';
        const match=document.createElement('span');match.className=`browser-match ${row.contains?'contains':''}`;match.textContent=row.contains?`contém · ${(row.similarity*100).toFixed(2)}% semântico`:`${(row.similarity*100).toFixed(2)}% de similaridade`;
        const title=document.createElement('h3');title.textContent=row.descricao_item;
        const fields=document.createElement('dl');fields.append(field('Código',row.codigo_servico),field('Fornecedor',row.codigo_fornecedor),field('Grupo',row.grupo_servico),field('Unidade',row.unidade_medida),field('Descrição da unidade',row.unidade_descricao),field('Verificação',`${row.cleanup_passes}/${row.required_cleanup_passes}${row.verification_pending?' · pendente de salvar':''}`));
        card.append(match,title,fields);
        if(!readonly){const actions=document.createElement('div');actions.className='merge-actions';for(const [action,label]of[['KEEP_SELECTED','Manter inicial · aumentar verificação · retirar este semelhante'],['KEEP_SIMILAR','Manter este semelhante · retirar inicial']]){const button=document.createElement('button');button.type='button';button.className='btn btn-danger';button.textContent=label;button.addEventListener('click',()=>decide(action,row.catalog_id));actions.append(button);}card.append(actions);}
        el('similar-codes').append(card);
      }
      if(!data.results.length)el('similar-codes').textContent='Não existem outros códigos ativos para comparar.';
      message(readonly?'Consulta de semelhantes. Para alterar registros, use a limpeza com uma conta de administrador.':'Escolha qual código manter. A verificação aumenta na revisão e só é gravada quando você salvar.');
      controls(false);
    }catch(error){message(error.message,true);el('keep-code').disabled=true;if(!readonly)el('save-cleanup').disabled=!dirty;busy=false;}
  }
  if(!readonly){
    el('save-cleanup').addEventListener('click',async()=>{if(busy||!dirty)return;controls(true);try{await window.cleanupGuard.save();}catch(error){message(error.message,true);controls(false);}});
    window.addEventListener('cleanup-saved',event=>{update(event.detail);load();});
  }
  el('keep-code').addEventListener('click',()=>decide('KEEP',null));load();
})();
