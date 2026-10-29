const menuNotice = document.getElementById('menu-notice');
async function databaseStatus() {
  const response = await fetch('/api/catalog/status');
  if (!response.ok) throw new Error('Não foi possível acessar o PostgreSQL. Verifique se o banco e o Java estão ativos.');
  const data = await response.json();
  document.getElementById('database-state').textContent = `${Number(data.size).toLocaleString('pt-BR')} serviços salvos no banco.`;
}
document.getElementById('migrate-catalog').addEventListener('click', async (event) => {
  const button = event.currentTarget;
  button.disabled = true;
  menuNotice.textContent = 'Recuperando o catálogo anterior…';
  try {
    const response = await fetch('/api/catalog/migrate-python', {method: 'POST'});
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Não foi possível recuperar a base. Reinicie a API Python com a versão atualizada.');
    menuNotice.textContent = `${data.imported} registros adicionados; ${data.existing} já existiam. Nenhum registro SQL foi sobrescrito. ${data.warning || ''}`;
    await databaseStatus();
  } catch (error) { menuNotice.textContent = error.message; }
  finally { button.disabled = false; }
});
databaseStatus().catch(error => { menuNotice.textContent = error.message; });
