// Integração externa ao HTML original do login, que permanece intacto.
async function checkLogin() {
  try {
    const response=await fetch('/api/auth/status',{cache:'no-store'});
    if(response.ok && (await response.json()).authenticated) { location.replace('/menu.html');return; }
    document.getElementById('status').textContent='';
  } catch (_) { document.getElementById('status').textContent='Aguardando conexão com o sistema…'; }
  setTimeout(checkLogin,1200);
}
checkLogin();
