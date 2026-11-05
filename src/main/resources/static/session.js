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
      const panel=document.createElement('div');panel.style.cssText='display:flex;justify-content:flex-end;align-items:center;gap:12px;padding:12px 24px;font:14px system-ui;background:#eef3fa';
      const label=document.createElement('span');label.textContent=`${session.email} · ${session.admin?'Administrador':'Usuário'}`;
      const logout=document.createElement('button');logout.type='button';logout.textContent='Sair';
      logout.addEventListener('click',async()=>{
        logout.disabled=true;
        try { const r=await fetch('/api/auth/logout',{method:'POST'});if(!r.ok) throw Error();location.replace('/login.html'); }
        catch (_) { logout.disabled=false;label.textContent='Não foi possível sair. Tente novamente.'; }
      });
      panel.append(label,logout);document.body.prepend(panel);
    } catch (_) { location.replace('/login.html'); }
  });
})();
