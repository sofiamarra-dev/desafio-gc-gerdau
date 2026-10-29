# Como executar e como montar uma configuração Docker sozinho

Este projeto tem três processos: Spring Boot (Java 21), FastAPI com MiniLM
(Python 3.11/PyTorch CPU) e PostgreSQL 17. O navegador conversa com Java;
Java conversa com o banco e com Python. O frontend estático acompanha o Java.

## Usar a configuração pronta

1. Abra o Docker Desktop e aguarde o mecanismo de contêineres Linux iniciar.
2. Abra um terminal na pasta do projeto que contém `compose.yml` e `gradlew`.
3. Se o Java da IDE e o Uvicorn local estiverem ligados nas portas 8080/8001,
   pare esses processos antes de usar as mesmas portas pelo Docker.
4. Execute:

```powershell
docker compose up -d --build --wait --wait-timeout 900
docker compose ps
```

5. Abra http://localhost:8080/menu.html. O status da IA está em
   http://localhost:8001/status; a documentação Python está em
   http://localhost:8001/docs.
6. Para acompanhar o início, execute `docker compose logs -f ai java`.
   Ctrl+C sai da visualização dos logs, sem parar os serviços.

O primeiro início demora mais: as imagens, as bibliotecas e o modelo precisam
ser baixados. Depois, as camadas das imagens e o volume do modelo são reutilizados.
O valor `ready=false` no status Python pode significar índice pendente. O serviço
HTTP está funcionando; a próxima busca pelo Java sincroniza o SQL e o reconstrói.

Para mudar as portas, copie `.env.example` para `.env` se esse arquivo ainda não
existir e ajuste `JAVA_PORT`, `AI_PORT` e `DB_PORT`. Os valores padrão são 8080,
8001 e 5433. As credenciais locais padrão estão no arquivo de exemplo.

```powershell
docker compose stop          # parar
docker compose up -d         # iniciar outra vez, sem compilar código novo
docker compose up -d --build # atualizar depois de mudar código
docker compose down         # remover contêineres/rede, preservando os dados
```

`restart` reinicia a imagem existente; não incorpora alterações do código-fonte.
Para isso, use `up -d --build`. `down --volumes` também remove os volumes e seus
dados; não faz parte da atualização normal.

## O que foi feito, na ordem para repetir em outro projeto

### 1. Identificar os processos e o que cada um precisa

Antes dos Dockerfiles, liste linguagem, versão, comando de início, portas e dados
gravados em disco. Aqui:

| Serviço | Ambiente | Comando principal | Dados persistentes |
|---|---|---|---|
| java | Java 21 | `java -jar /app/app.jar` | PostgreSQL |
| ai | Python 3.11 + bibliotecas CPU | `python -m uvicorn ai-python.main:app ...` | índice e cache do modelo |
| db | PostgreSQL 17 | fornecido pela imagem oficial | diretório do PostgreSQL |

Em outro projeto, comece fazendo essa tabela. Isso revela quais processos
precisam de imagens próprias e quais podem usar uma imagem pronta.

### 2. Criar o Dockerfile do Java

`Dockerfile`, na raiz, usa duas etapas:

- `build`: começa com JDK 21, copia o Gradle Wrapper e o código, executa `bootJar`.
- `runtime`: começa com JRE 21 e copia somente o JAR produzido.

O JDK tem as ferramentas de compilação; o JRE executa o programa. A segunda etapa
evita levar Gradle, testes e código-fonte para a imagem de execução. O build usa
o Wrapper do próprio projeto, mantendo a versão de Gradle. O script é convertido
para finais de linha Linux e ganha permissão de execução.

`WORKDIR` escolhe o diretório; `COPY` inclui arquivos; `RUN` executa comandos na
construção; `ENTRYPOINT` inicia Java quando o contêiner é criado. `EXPOSE` documenta
a porta interna, mas não a publica no Windows.

### 3. Criar o Dockerfile do Python

`ai-python/Dockerfile` parte de Python 3.11 em Linux, instala bibliotecas de sistema,
instala PyTorch pelo índice de versões CPU e instala as dependências da API.
`requirements-docker.txt` fixa as versões principais já utilizadas no projeto.

Não copiamos a `.venv` do Windows: os pacotes e DLLs dela não são o ambiente Linux
do contêiner. As dependências são instaladas novamente dentro da imagem.

O comando Uvicorn usa `--host 0.0.0.0`, para receber conexões pela rede Docker,
e um único worker, pois o índice atual fica em memória nesse processo.
O modelo é baixado no início e mantido em volume, em vez de incorporado à imagem.
Os dois serviços de aplicação executam com um usuário próprio, sem ser root.

### 4. Excluir arquivos desnecessários do contexto

`.dockerignore` exclui `.venv`, `.git`, `.gradle`, `build`, `.env`, índices locais,
arquivos temporários e saídas da avaliação. Isso reduz o envio de arquivos ao
construtor e evita embutir dados locais ou configurações pessoais na imagem.

As dependências são copiadas antes do código Python. Assim, mudar apenas um
arquivo `.py` não invalida a etapa de instalação das bibliotecas. Também usamos
cache de build para os downloads do pip e do Gradle.

### 5. Reunir os serviços no Compose

`compose.yml` descreve `db`, `ai` e `java`. Para aplicações próprias, `build` aponta
ao Dockerfile; para PostgreSQL, `image` aponta à imagem oficial pronta.

`ports` liga a porta do Windows à porta interna, por exemplo `8080:8080`.
As publicações foram limitadas a `127.0.0.1`, como já era feito no banco local.

O Compose cria uma rede para o projeto. Nela, os nomes dos serviços funcionam
como endereços:

- Java → PostgreSQL: `jdbc:postgresql://db:5432/gerdau`.
- Java → Python: `http://ai:8001`.
- Navegador no Windows → Java: `http://localhost:8080`.

Dentro do contêiner Java, `localhost` é o próprio Java, não o Windows e não o
Python. Essa distinção é a razão de trocar os endereços internos.
O navegador continua usando rotas relativas `/api/...` no Java; não precisa
conhecer o nome interno `ai`.

### 6. Separar configurações do código

O Spring já lê `DB_URL`, `DB_USER`, `DB_PASSWORD` e `PYTHON_AI_URL` do ambiente.
O Compose fornece os endereços internos e as credenciais; as opções de porta e
credenciais podem vir do `.env`.

Um `.env` não é carregado automaticamente pelo Spring quando você usa a IDE.
Por isso, `.env.example` distingue variáveis de execução local das configurações
internas do Compose. Não é preciso trocar a configuração do código para alternar.

### 7. Persistir o que não pode desaparecer com o contêiner

Contêiner pode ser removido e recriado. Os volumes ficam separados dele:

- `postgres_data`: catálogo, dicionário, lotes, decisões e histórico SQL.
- `ai_data`: arquivos derivados usados na busca.
- `model_cache`: download do MiniLM, evitando baixar novamente a cada recriação.

O nome `postgres_data` original foi mantido. No projeto `gerdau-spring`, o volume
continua associado ao mesmo nome de projeto. Mudar a pasta ou o parâmetro `-p`
pode criar outro conjunto de volumes e dar a impressão de banco vazio.

O diretório Windows `ai-python/data` não é apagado nem copiado para a imagem.
Com o catálogo já no SQL, a primeira busca reconstrói o índice no volume novo.
Se ainda houver dados somente no Python local, recupere-os pelo menu na execução
manual antes de parar esse serviço. Essa recuperação não é feita automaticamente.

### 8. Esperar serviços responderem, não apenas iniciarem

O banco usa `pg_isready`; Python usa uma chamada HTTP ao `/status`; Java usa
uma chamada HTTP ao `/api/catalog/status`. `depends_on` com `service_healthy`
faz Java aguardar banco e Python.

O healthcheck Python não exige `ready=true`: um índice antigo pode precisar do
Java para ser reconstruído. Exigir essa flag antes de iniciar Java criaria uma
dependência circular. O teste HTTP confirma que o modelo carregou e a API responde.

`restart: unless-stopped` tenta recuperar os contêineres se eles encerrarem.
Ele não cria serviços que nunca foram iniciados pelo Compose. Após o primeiro
`up`, o Docker Desktop passa a mostrar os três contêineres no projeto.

### 9. Testar o fluxo e a persistência

O script `scripts/test-docker.ps1` cria outro projeto, com portas 18080/18001/15433
e volumes próprios. Ele testa menu, modelo real, cadastro, busca via Java,
separação de siglas, texto UTF-8, download do XLSX, lote por texto, reutilização do código e histórico de correções.
Depois executa `down` e `up` e confirma que cadastro e decisão continuam salvos.
No final remove somente os contêineres e volumes do projeto de teste.

```powershell
.\scripts\test-docker.ps1
```

Para investigar uma falha sem remover os contêineres de teste, acrescente
`-KeepContainers`. Use `-SkipBuild` se já tiver construído as imagens desse
mesmo projeto de teste. O nome precisa começar com `gerdau-smoke-`; o script
recusa o nome do projeto de desenvolvimento.

Em outro projeto, mantenha esse mesmo raciocínio: primeiro valide cada imagem,
depois a comunicação entre serviços e, por último, recrie contêineres e confira
se os dados realmente persistem.

## Referências oficiais

- [Build em múltiplas etapas](https://docs.docker.com/build/building/multi-stage/)
- [Rede e nomes de serviços no Compose](https://docs.docker.com/compose/how-tos/networking/)
- [Ordem de inicialização e healthchecks](https://docs.docker.com/compose/how-tos/startup-order/)
