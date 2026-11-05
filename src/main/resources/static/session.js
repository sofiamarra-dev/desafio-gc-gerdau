// Sessão e CSRF pertencem à aplicação Java, sem modificar o login fornecido.
(() => {
  const originalFetch=window.fetch.bind(window);
  let statePromise;
  const state=()=>statePromise ||= originalFetch('/api/auth/status',{cache:'no-store'}).then(r=>r.json());
  window.fetch=async (input,options={})=>{
    const url=new URL(typeof input==='string'?input:input.url,location.href);
    const method=(options.method || input.method || 'GET').toUpperCase();
    if(url.origin===location.origin && !['GET','HEAD','OPTIONS'].includes(method)) {
      const session=await state();
      const headers=new Headers(options.headers || input.headers || {});
      if(session.csrfToken) headers.set(session.csrfHeader,session.csrfToken);
      options={...options,headers};
    }
    const response=await originalFetch(input,options);
    if(url.origin===location.origin && response.status===401) location.replace('/login.html');
    return response;
  };
  document.addEventListener('DOMContentLoaded',async()=>{
    try {
      const session=await state();
      if(!session.authenticated) { location.replace('/login.html');return; }
      document.querySelectorAll('[data-admin-only]').forEach(element=>{element.hidden=!session.admin;});
      const panel=document.createElement('div');panel.className='session-user';panel.setAttribute('aria-label','Conta conectada');
      const avatar=document.createElement('span');avatar.className='session-avatar';avatar.setAttribute('aria-hidden','true');
      avatar.textContent=session.email.split('@')[0].slice(0,2).toUpperCase();
      const details=document.createElement('div');details.className='session-details';
      const label=document.createElement('span');label.className='session-email';label.textContent=session.email;label.title=session.email;
      const role=document.createElement('span');role.className='session-role';role.textContent=session.admin?'Administrador':'Usuário';
      const error=document.createElement('span');error.className='session-error';error.setAttribute('role','status');
      const logout=document.createElement('button');logout.type='button';logout.className='session-logout';logout.setAttribute('aria-label','Sair da conta');
      logout.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 4H5v16h4M11 12h10M17 8l4 4-4 4"/></svg><span>Sair</span>';
      logout.addEventListener('click',async()=>{
        logout.disabled=true;
        try { const r=await fetch('/api/auth/logout',{method:'POST'});if(!r.ok) throw Error();location.replace('/login.html'); }
        catch (_) { logout.disabled=false;error.textContent='Não foi possível sair. Tente novamente.'; }
      });
      details.append(label,role,error);panel.append(avatar,details,logout);
      const header=document.querySelector('.topo');
      if(header) header.append(panel); else document.body.prepend(panel);
    } catch (_) { location.replace('/login.html'); }
  });
})();
