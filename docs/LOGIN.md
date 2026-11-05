# Login por código de e-mail

O Java recebe as requisições `/enviar` e `/verificar` do frontend original e
encaminha os dados para o serviço Flask. Somente depois de um `ok=true` real do
serviço, na mesma sessão que solicitou aquele e-mail, o Java estabelece a sessão
autenticada. O cookie é HttpOnly, SameSite Strict e a sessão expira por
inatividade após 30 minutos. Os POSTs da aplicação usam proteção CSRF.

`auth-token/app.py` e `auth-token/index.html` são cópias exatas dos arquivos
fornecidos. `static/token-login.html` contém o mesmo HTML byte a byte.
`login.html` apenas o apresenta dentro de um iframe e `login-bridge.js`
acompanha a criação da sessão Java para abrir o menu. A identidade e o perfil
são decididos no servidor, não pela mensagem exibida pelo HTML original.

O único administrador inicial é `danielosterkamp38@gmail.com`, na lista
`src/main/java/com/example/demo/security/AdminAccounts.java`. Para incluir
administradores, edite essa lista, reconstrua Java e faça login novamente.
As outras contas Gmail recebem perfil de usuário. O domínio Gmail permanece
uma restrição do código original.

Usuários normais podem pesquisar e criar serviços. As páginas e todas as APIs
de dicionário e limpeza exigem ADMIN. A navegação também oculta os respectivos
atalhos. Isso não impede o uso interno do dicionário pelo motor de busca.

## Execução com Docker

Com Docker Desktop iniciado, execute na raiz:

```powershell
docker compose up -d --build
```

O Compose sobe `db`, `ai`, `auth` e `java`. `auth` fica na rede interna sem porta
publicada; abra `http://localhost:8080/`. Java usa `http://auth:5000`.
O healthcheck usa `/api/health`, que não revela dados do catálogo.

## Execução manual

Na raiz do projeto, prepare o serviço copiado uma vez:

```powershell
python -m venv auth-token/.venv
./auth-token/.venv/Scripts/python.exe -m pip install -r auth-token/requirements.txt
```

Inicie-o em um terminal separado:

```powershell
cd auth-token
./.venv/Scripts/python.exe -m waitress --listen=127.0.0.1:5000 --threads=1 app:app
```

Execute banco, API de embeddings e Java conforme o README. Por padrão Java
chama `http://127.0.0.1:5000`; `AUTH_TOKEN_URL` permite alterar esse endereço.
Entre sempre pelo Java em `http://localhost:8080/`, mantendo o mesmo hostname
durante todo o fluxo. A porta 5000 isolada continua sendo apenas a verificação
original e não cria a sessão Java.

O SMTP continua configurado exatamente como no código fornecido, inclusive a
credencial existente. Ela não é reproduzida na documentação. A validade do
remetente e a entrega real de mensagens dependem dessa configuração. Os limites,
armazenamento em memória e demais comportamentos do verificador original foram
preservados. Use somente um processo para esse serviço; o Docker usa um único
processo/thread e inicia sem o debug do executável original.

`app.auth.enabled=false` existe para os testes legados e não deve ser definido
na execução real. Os testes específicos de segurança habilitam explicitamente
a proteção e simulam o serviço de e-mail sem enviar mensagens.
