(() => {
  let summary = {dirty:false,version:0}, allowExit = false, pendingChoice = null;
  const internal = url => url.origin === location.origin && ['/limpeza.html','/revisao.html'].includes(url.pathname);
  const differentInternal = url => {
    const current=new URL(location.href);
    return internal(url)&&(url.pathname!==current.pathname||url.search!==current.search);
  };
  const statusEvent = () => window.dispatchEvent(new CustomEvent('cleanup-state', {detail:summary}));
  function update(next) {
    if(next && typeof next.dirty === 'boolean') summary={...summary,...next};
    if(summary.dirty)allowExit=false;
    statusEvent();
  }
  async function post(path,data) {
    const response=await fetch(`/api/cleanups/session/${path}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)});
    const result=await response.json();
    if(!response.ok) throw Error(result.error||result.detail||result.message||'Não foi possível concluir a operação.');
    update(result);return result;
  }
  async function save() {
    const result=await post('save',{version:summary.version});
    window.dispatchEvent(new CustomEvent('cleanup-saved',{detail:result}));
    return result;
  }
  function choose() {
    if(pendingChoice) return pendingChoice;
    pendingChoice=new Promise(resolve=>{
      const dialog=document.createElement('dialog');dialog.className='cleanup-dialog';
      const title=document.createElement('h2');title.textContent='Salvar alterações antes de sair?';
      const description=document.createElement('p');description.textContent='Existem padronizações, exclusões ou verificações pendentes. Escolha salvar, sair sem salvar ou continuar na limpeza.';
      const error=document.createElement('p');error.className='cleanup-dialog-error';error.setAttribute('role','status');
      const actions=document.createElement('div');actions.className='workflow-actions';
      const buttons=[];
      const finish=value=>{dialog.close();dialog.remove();resolve(value);};
      for(const [action,label] of [['save','Salvar e sair'],['discard','Sair sem salvar'],['cancel','Continuar revisando']]) {
        const button=document.createElement('button');button.type='button';button.className=`btn ${action==='save'?'btn-primary':''}`;button.textContent=label;buttons.push(button);
        button.addEventListener('click',async()=>{
          if(action==='cancel'){finish(false);return;}
          buttons.forEach(item=>item.disabled=true);
          try { if(action==='save') await save();else await post('discard',{});allowExit=true;finish(true); }
          catch(failure){error.textContent=failure.message;buttons.forEach(item=>item.disabled=false);}
        });actions.append(button);
      }
      dialog.addEventListener('cancel',event=>{event.preventDefault();finish(false);});
      dialog.append(title,description,error,actions);document.body.append(dialog);dialog.showModal();
    }).finally(()=>{pendingChoice=null;});
    return pendingChoice;
  }
  async function beforeLeave(){if(!summary.dirty)return true;return choose();}
  async function acknowledge(next){
    update(next);if(next.acknowledged)return next;
    if(next.automatic_removed===0)return post('acknowledge',{version:summary.version});
    return new Promise(resolve=>{
      const dialog=document.createElement('dialog');dialog.className='cleanup-dialog';
      const title=document.createElement('h2');title.textContent='Verificação de registros idênticos concluída';
      const text=document.createElement('p');const count=Number(next.automatic_removed||0);
      text.textContent=`${count} registro(s) 100% idêntico(s) retirado(s) automaticamente da revisão. Essas exclusões só serão gravadas no banco quando você clicar em Salvar alterações.`;
      const note=document.createElement('p');note.textContent='A igualdade é dos dados do registro; uma nota de 100% dos embeddings não exclui códigos automaticamente.';
      const error=document.createElement('p');error.className='cleanup-dialog-error';error.setAttribute('role','status');
      const button=document.createElement('button');button.type='button';button.className='btn btn-primary';button.textContent='Entendi · iniciar revisão';
      button.addEventListener('click',async()=>{button.disabled=true;try{const result=await post('acknowledge',{version:summary.version});dialog.close();dialog.remove();resolve(result);}catch(failure){error.textContent=failure.message;button.disabled=false;}});
      const leave=document.createElement('button');leave.type='button';leave.className='btn';leave.textContent='Descartar alterações e voltar';
      leave.addEventListener('click',async()=>{leave.disabled=true;button.disabled=true;try{await post('discard',{});allowExit=true;dialog.close();dialog.remove();location.href='catalogo.html';}catch(failure){error.textContent=failure.message;leave.disabled=false;button.disabled=false;}});
      const actions=document.createElement('div');actions.className='workflow-actions';actions.append(button,leave);
      dialog.addEventListener('cancel',event=>event.preventDefault());dialog.append(title,text,note,error,actions);document.body.append(dialog);dialog.showModal();
    });
  }
  window.cleanupGuard={update,save,acknowledge,isDirty:()=>summary.dirty,allowInternalNavigation:()=>{allowExit=true;}};
  window.cleanupBeforeLeave=beforeLeave;
  document.addEventListener('click',async event=>{
    const link=event.target.closest?.('a[href]');
    if(!link || event.defaultPrevented || event.button>0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || link.target==='_blank' || link.hasAttribute('download'))return;
    const target=new URL(link.href,location.href);
    if(internal(target)){if(differentInternal(target))allowExit=true;return;}
    if(!summary.dirty)return;
    event.preventDefault();if(await beforeLeave()){allowExit=true;location.href=target.href;}
  });
  window.addEventListener('beforeunload',event=>{if(summary.dirty&&!allowExit){event.preventDefault();event.returnValue='';}});
  window.navigation?.addEventListener('navigate',event=>{
    if(event.navigationType!=='reload'&&differentInternal(new URL(event.destination.url)))allowExit=true;
  });
  // Uma entrada auxiliar permite perguntar antes de sair pelo botão Voltar do navegador.
  // Voltar da comparação à lista mantém a mesma sessão de limpeza e dispensa a pergunta.
  let referrerIsInternal=false;
  try{referrerIsInternal=!!document.referrer&&internal(new URL(document.referrer));}catch(_){}
  if(!referrerIsInternal){
    history.pushState({...history.state,cleanupNavigationGuard:true},'',location.href);
    window.addEventListener('popstate',async event=>{
      if(allowExit)return;
      if(event.state?.cleanupNavigationGuard)return;
      history.pushState({...history.state,cleanupNavigationGuard:true},'',location.href);
      if(await beforeLeave()){allowExit=true;history.go(-2);}
    });
  }
  window.addEventListener('pageshow',async event=>{
    if(!event.persisted)return;allowExit=false;
    try{const response=await fetch('/api/cleanups/session/status',{cache:'no-store'});if(response.ok)update(await response.json());}catch(_){}
  });
})();
