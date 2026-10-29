# Desafio GC - Gerdau

Projeto desenvolvido durante o desafio proposto pela Gerdau, através do programa Geração Caldeira.

## Sobre o desafio
(Qual era o problema proposto?)

## Nossa solução
(O que vocês criaram e como funciona?)

## Tecnologias utilizadas


## Como executar

O catálogo agora é persistido no PostgreSQL. O Python mantém um índice derivado, usando MiniLM local.
As telas estão separadas em pesquisa, cadastro unitário e revisão de solicitações em massa.

### Execução completa com Docker (recomendada para iniciar tudo junto)

Requer Docker Desktop ativo em modo de contêineres Linux. Na pasta que contém
`compose.yml`, execute:

```powershell
docker compose up -d --build
docker compose ps
docker compose logs -f ai java
```

Abra http://localhost:8080/menu.html. Python: http://localhost:8001/status.
O primeiro início baixa as dependências das imagens e o modelo MiniLM; aguarde
os serviços ficarem healthy. Para aguardar pelo terminal:

```powershell
docker compose up -d --build --wait --wait-timeout 900
```

Pare antes o Java da IDE e o Uvicorn local se estiverem usando 8080/8001.
Alternativamente, defina JAVA_PORT e AI_PORT no .env para usar outras portas.
O PostgreSQL existente em 5433 e o volume postgres_data foram preservados.
Não mude o nome do projeto Compose sem planejar os volumes: outro nome cria
outro banco separado. As configurações padrão funcionam sem .env; para
personalizar, copie .env.example para .env somente se ele ainda não existir.

Dentro do Docker, Java usa `db:5432` e `ai:8001`; localhost identifica o próprio
contêiner. Os DB_URL/PYTHON_AI_URL do .env para execução pela IDE não substituem
esses endereços internos no Compose.

Volumes:
- postgres_data: catálogo, lotes, dicionário e histórico SQL.
- ai_data: índice derivado e dicionário usado pelo Python.
- model_cache: modelo baixado, reutilizado ao recriar contêineres.

O índice local em ai-python/data não é copiado para a imagem. O SQL é a fonte
principal; a primeira busca no Docker reconstrói o índice no volume. Se ainda
houver registros somente no Python local, use Recuperar catálogo anterior na
execução manual antes de parar o serviço local. O Docker não importa esses
arquivos automaticamente nem apaga o diretório local.

```powershell
docker compose stop          # para os três serviços
docker compose up -d         # inicia novamente com as imagens já construídas
docker compose up -d --build # use após alterar o código
docker compose down          # remove contêineres/rede e preserva volumes
```

`docker compose down --volumes` apaga os dados dos volumes. Não é necessário
para atualizar o código. O Compose espera os healthchecks do banco e da IA
antes de iniciar Java; o healthcheck da IA testa HTTP, não exige índice pronto,
pois o Java precisa iniciar para sincronizá-lo.

Teste isolado, com outro banco e portas:

```powershell
.\scripts\test-docker.ps1
```

O script só aceita nomes gerdau-smoke-..., cria dados sintéticos, recria os
contêineres para verificar persistência e remove seus próprios volumes no fim.

O passo a passo comentado está em [docs/DOCKER.md](docs/DOCKER.md).

### Execução manual (opcional)

### 1. Banco de dados

Com Docker Desktop ativo, na pasta que contém `build.gradle`:

```powershell
docker compose up -d db
docker compose ps
```

O banco fica em `127.0.0.1:5433`, database `gerdau`, usuário `gerdau`, senha local `gerdau_local`.
Um volume Docker conserva os registros entre reinícios. `docker compose down` não apaga esse volume.
Na execução manual desta seção, Java e Python ficam fora dos contêineres. A execução completa por Docker está descrita acima.

Também é possível usar um PostgreSQL já instalado: crie banco e usuário e configure `DB_URL`, `DB_USER` e `DB_PASSWORD` nas variáveis da IDE ou do terminal Java.
O arquivo `.env` é lido pelo Compose; o Spring não o carrega automaticamente.
Flyway cria as tabelas e a sequência na primeira inicialização. Evoluções devem usar novas migrações em `src/main/resources/db/migration/`.

### 2. Python

Requer Python 3.11 recomendado. Se o ambiente ainda não existir:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --index-url https://download.pytorch.org/whl/cpu torch
.\.venv\Scripts\python.exe -m pip install -r ai-python/requirements.txt
```

```powershell
.\.venv\Scripts\python.exe -m uvicorn ai-python.main:app --host 127.0.0.1 --port 8001
```

No Windows, PyTorch pode exigir o Visual C++ Redistributable x64 atualizado:
https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist
O modelo é baixado no primeiro uso. Execute com um único processo Uvicorn nesta versão; o índice é mantido em memória e em `ai-python/data/`.

### 3. Java e menu

Requer JDK 21 disponível para o Gradle.

```powershell
.\gradlew.bat bootRun
```

Ou execute `DemoApplication` pela IDE, com o PostgreSQL ativo. Abra http://localhost:8080/.
É necessário reiniciar o Python e recompilar/reiniciar o Java depois desta atualização.

### 4. Primeira utilização e preservação da base

Se já há um catálogo no Python, use **Recuperar catálogo anterior** no menu antes de pesquisar.
Essa operação copia os registros para o SQL e sincroniza os identificadores do índice.
As cargas originais são arquivadas em `catalog_import_history`, inclusive os registros repetidos que não sobrescrevem o catálogo.
Não é automática e pode demorar na primeira carga. Código + fornecedor identifica uma combinação existente: registros repetidos são informados, não sobrescritos.
Se o Python estiver vazio, use **Importar base de referência**, com a estrutura XLSX já utilizada (as cinco colunas do catálogo).
A carga de referência é aditiva: nunca apaga a base SQL. Não use essa opção para enviar solicitações novas.
Uma busca bloqueia a substituição silenciosa de um catálogo Python de outra origem e solicita a recuperação explícita.

### 5. Cadastro unitário

Preencha a descrição e consulte os semelhantes antes de confirmar novo cadastro.
O identificador é separado da descrição montada pelo formulário. Se não for informado, será gerado um identificador `LOCAL-00000001`.
Ele é interno ao protótipo, não é um código emitido pelo SAP.
O pedido possui `request_id` para que repetir a mesma confirmação não duplique o cadastro.
O serviço é salvo no banco antes da sincronização. Na próxima busca, somente os textos novos/alterados geram embeddings; os anteriores são reaproveitados.

### 6. Criação em massa

Na tela **Criação em massa**, escolha **Planilha Excel** e clique em **Baixar planilha modelo**.
O arquivo `modelo-solicitacoes.xlsx` contém os seis cabeçalhos aceitos, sem linhas de exemplo.
Preencha a partir da linha 2, mantenha os cabeçalhos e envie o XLSX. Somente `Descrição do Item` é obrigatória.
São opcionais `Grupo de Serviço`, `Código do Fornecedor`, `Código do Serviço`, `Unidade de medida` e `Descrição da Unidade`.
Uma aba auxiliar `Unidades de Medida` pode expandir as siglas, como antes.

O arquivo cria um lote, sem inserir serviços imediatamente. Cada linha válida fica pendente:

1. **Usar existente** vincula a solicitação ao serviço escolhido sem criar outro.
2. **Confirmar novo cadastro** cria um serviço e registra a decisão de forma transacional.
3. **Revisar depois** deixa a linha pendente e navega para outra.

As decisões e as linhas originais ficam no banco; retome o lote pelo menu da tela.
As sugestões são recalculadas ao avançar, incluindo serviços recém-criados no próprio lote.
A revisão mostra descrição original, descrição padronizada, atividade principal e
qualificadores (inclusive percentuais). A explicação vem em `query_parts` na resposta
de `/search`, sob a mesma versão do dicionário usada nos resultados, mesmo com catálogo vazio.
Um aviso destaca descrições sem atividade principal e é repetido na confirmação de criação.
O cadastro mantém a descrição original. **Atualizar análise** refaz a consulta depois
de alterações no dicionário. Se a separação não estiver disponível, é necessário
carregá-la antes de criar um novo código; a solicitação continua pendente.
Linhas inválidas ficam identificadas, sem cadastro; corrija-as no Excel e envie um novo lote se necessário.
Até 50.000 solicitações por lote, 50.000 serviços no catálogo e upload de 15 MB nesta versão.

### 7. Excel

**Exportar catálogo** baixa um novo XLSX com os serviços SQL. **Baixar resultado do lote** inclui linha de origem, situação, dados solicitados e código final.
O sistema não altera o arquivo original no computador do usuário. Não existem vínculos com contratos nem login implementados nesta etapa.

### 8. Testes

```powershell
.\gradlew.bat test
node --test src/test/javascript/massa.test.cjs src/test/javascript/siglas.test.cjs
.\.venv\Scripts\python.exe -m pip install -r ai-python/requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest ai-python/tests -q
```

Os testes Java usam H2 isolado em modo PostgreSQL com as migrações Flyway, incluindo fluxo HTTP, concorrência e exportação XLSX.
Eles não acessam o banco de desenvolvimento nem substituem a validação no PostgreSQL real.
Os testes Python verificam cache de embeddings, revisão persistida, identificadores e sincronização usando vetores controlados.

### Rotas principais

| Rota Java | Função |
|---|---|
| `POST /api/import` | Acrescentar a base de referência ao SQL |
| `POST /api/search` | Sincronizar a revisão SQL se necessário e buscar no Python |
| `GET /api/catalog/status` | Quantidade e revisão do catálogo SQL |
| `POST /api/catalog/migrate-python` | Recuperar explicitamente o catálogo anterior |
| `POST /api/catalog/services` | Cadastro unitário com `request_id` |
| `GET /api/catalog/export` | Exportar o catálogo SQL |
| `POST /api/catalog/batches` | Enviar solicitações em XLSX |
| `GET /api/catalog/batches` | Retomar lotes e consultar progresso |
| `GET /api/catalog/batches/{id}/items` | Consultar linhas por número de origem |
| `POST /api/catalog/batches/{id}/items/{item}/decision` | Confirmar `CREATE` ou `EXISTING` |
| `GET /api/catalog/batches/{id}/export` | Exportar decisões |

Os endpoints Python `/sync` e `/catalog` servem à sincronização e migração. O Java é o responsável pela gravação dos serviços no SQL.

## Busca por atividade e qualificadores (integração search-7)

O Python aplica o mesmo separador à descrição de cada serviço e à consulta.
Exemplo: `PINTOR HE 50%` gera `PINTOR` e `HE 50%`. O MiniLM produz vetores
separados; a nota semântica é 0,90 × cosseno das atividades + 0,10 × cosseno
dos qualificadores. Grupo e unidade não entram mais no segundo vetor.

Se a consulta só contém atividade, o peso efetivo é 100% atividade. Se só contém
qualificadores, é 100% qualificadores. Um campo ausente no item usa vetor zero,
sem redistribuição do peso por item. Textos vazios não são enviados ao modelo.
Código/descrição exatos e correspondência de todas as palavras continuam tendo
prioridade sobre a ordenação semântica. Portanto, a posição final não depende
somente do 90/10. `similaridade_semantica` expõe a nota antes da prioridade exata.

Os resultados incluem `atividade`, `qualificadores`, `pesos_aplicados`,
`similaridade_atividade` e `similaridade_qualificadores`. Os campos antigos
`similaridade_descricao` e `similaridade_contexto` são aliases das duas notas.
`/status` informa `search_strategy`, `dictionary_version` e os pesos nominais.

ESP/ADM permanecem na atividade. Expressões negativas conhecidas, como
`SEM INSALUBRIDADE`, são preservadas; isso não garante que o modelo compreenda
todas as negações. Percentuais explícitos continuam ligados aos qualificadores.
Descrições sem atividade recebem `separacao_requer_revisao=true`.

Após atualizar, reinicie Python e Java. O índice antigo é marcado para reconstrução;
os registros e a revisão de origem são preservados. Na primeira pesquisa Java,
o SQL é sincronizado mesmo se a revisão não mudou. Essa pesquisa pode demorar
durante a geração dos novos vetores. Catálogos Python de outra origem continuam
exigindo a recuperação explícita para SQL. Não é necessário reanexar a planilha.
No uso direto do Python, envie os registros a `/sync` ou `/index` antes de buscar
quando `requires_reindex=true`. O formato 4 registra versão do dicionário e uma
identificação comum ao JSON e NPZ para detectar gravações incompletas.

Esta integração não altera as descrições originais no SQL. O reconhecimento
continua conservador e o ganho de relevância deve ser medido com consultas revisadas.

## Criação em massa por texto

Na tela **Criação em massa**, escolha **Colar texto**. Use uma solicitação por
linha, sem cabeçalho, na ordem:
`Descrição do Item;Grupo de Serviço;Código do Fornecedor;Código do Serviço;Unidade de medida;Descrição da Unidade`.
Somente a descrição é obrigatória. Campos vazios são mantidos e colunas finais
opcionais podem ser omitidas. Também é possível enviar uma descrição por linha.
Campos com `;` precisam de aspas duplas; uma aspa dentro deles é escrita como `""`.
Não são aceitas quebras de linha dentro de campos. O limite é de 50.000
solicitações e 2 milhões de caracteres. Erros de formato identificam a linha e
impedem o envio inteiro; erros de dados ficam como linhas inválidas no lote.

POST `/api/catalog/batches/text` recebe `{"text":"..."}`. O texto original é
preservado; as solicitações passam pela mesma revisão, busca com siglas salvas,
decisão individual e exportação do Excel. Enviar um lote não cria serviços.

## Edição de siglas

No menu, abra **Gerenciar siglas** (`/siglas.html`). A lista permite buscar por
sigla, significado e variantes, filtrar ativas/inativas e cadastrar novas siglas.
As variantes são informadas uma por linha; a prévia usa somente o dicionário salvo.
Na edição comum, sigla e significado oficial ficam protegidos. Use Corrigir cadastro para corrigir significado e variantes com motivo e histórico. É possível editar
variantes e desativar uma sigla, preservando a reserva de suas expressões.

A migração V3 cria o dicionário SQL e inicializa as sete siglas existentes.
Cada sigla aceita até 100 variantes de até 80 caracteres. Código e significado
também reservam suas formas normalizadas. Duplicidades entre siglas retornam
409 com o proprietário da expressão, inclusive se ele estiver inativo. Edições
concorrentes usam uma versão e não sobrescrevem silenciosamente alterações.

GET/POST `/api/abbreviations`, PUT `/api/abbreviations/{code}` e POST
`/api/abbreviations/preview` são as rotas da tela. Salvar não exige Python ativo.
Antes de pesquisar, Java envia o dicionário atual para PUT `/dictionary` e
reconstrói o índice quando necessário. O Python mantém `dictionary.json`, invalida
os vetores antigos e usa a mesma versão para consulta e indexação. O teste de
descrição POST `/dictionary/preview` não altera o dicionário nem os vetores.

Reinicie Java e Python para carregar esta implementação. A primeira busca após
editar o dicionário pode demorar pela reconstrução dos embeddings. Não são
alteradas as descrições originais. A tela cadastra qualificadores; ESP/ADM ainda
não são cadastrados automaticamente. Percentuais continuam associados somente
a HE, NOT, PERI e INSA. Não há aprovação por papéis nem autenticação nesta versão;
a edição fica disponível a quem acessa a aplicação. A unicidade impede conflitos
de cadastro, mas não identifica semanticamente uma variante incorreta: cadastre
apenas expressões com o mesmo significado oficial.

## Equipe
- Nome 1 Sofia Marra
- Nome 2 Renan Macedo
- Nome 3 Guilherme Barros
- Nome 4 Nathallya Fontoura  
- Nome 5 Daniel Osterkamp


## Avaliação da qualidade da busca

O avaliador usa MiniLM real, uma cópia do catálogo/vetores/dicionário e os pesos
80/20, 90/10 e 95/5, sem alterar o índice original ou as regras do aplicativo.
O modelo precisa estar no cache local; o avaliador bloqueia downloads de rede.
Os 33 casos iniciais incluem nove controles técnicos e 24 consultas semânticas.
Os candidatos semânticos são hipóteses para aprovação humana, não gabaritos.

```powershell
.\.venv\Scripts\python.exe -m ai-python.evaluate_search --data-dir ai-python/data --cases ai-python/evaluation_cases.json --output outputs/avaliacao-busca
```

Abra `review.html` na pasta de saída. Confira a intenção, avalie os primeiros
resultados e aprove os códigos adequados antes de interpretar métricas semânticas.
Exporte a revisão JSON; os julgamentos não são enviados ao sistema. Os resultados
incluem hashes da base, código, revisão do modelo/dicionário e comparação amostral
dos vetores salvos com embeddings atuais. Se os vetores divergirem ou a base mudar
durante a execução, o avaliador interrompe e não reconstrói a base original.
Use uma pasta de saída nova quando mudar o catálogo, o código ou os casos.
Consultas sem qualificadores usam 100% atividade, independentemente do peso nominal.
Hit@k mede presença de pelo menos um alvo; não é recall completo. Controles exatos
não comprovam qualidade semântica. Calibração/validação estão separados por família
de consulta para uma futura mudança, que ainda exige revisão humana.


## Regras de ESP/ADM e correções do dicionário

ESP/ADM isolados (inclusive com pontos) são rejeitados no cadastro de códigos,
significados e variantes, tanto no Java quanto no Python. Permanecem na atividade.
Não foram cadastradas expansões presumidas: AUX ADM faz parte da profissão;
ESP junto de medidas pode indicar espessura; H ESP e o sufixo -ADM precisam de
confirmação de significado/contexto. Cadastre apenas expressões completas de
qualificadores confirmados; não remova o sentido principal da descrição.

No editor, Corrigir cadastro permite corrigir significado e variantes com motivo
de 10 a 500 caracteres, confirmação de revisão e versão atual. O código e o estado
ativo permanecem iguais. GET/POST /api/abbreviations/{code}/corrections consulta e
registra o histórico anterior/posterior de forma transacional (migração V4).
A correção libera expressões removidas, sem transferir variantes de outra sigla.
Conflitos e versões antigas não gravam mudanças nem histórico parcial.
Para desfazer, registre uma nova correção com os dados anteriores e um motivo.
Na próxima busca, o dicionário atualizado exige reconstrução do índice.

Um erro simples de variante pode ser removido pela edição comum. Para preservar o
antes/depois de um erro, use Corrigir cadastro. A confirmação humana não demonstra
semanticamente que as variantes são sinônimos: o sistema impede duplicidades,
mas depende da revisão de significado. Login e permissões por usuário seguem
como etapa posterior; este histórico não identifica um autor autenticado.


## Variantes pontuadas de HE e percentuais

A migração V5 acrescenta H. EXTRA/H.EXTRA e H. EXTRAS/H.EXTRAS a HE quando
HE está ativo e seu significado continua sendo hora extra. Não sobrescreve
variantes de outra sigla nem reativa HE. A variante é cadastrada uma vez por
chave normalizada; espaços junto ao ponto não criam outra expressão.
Se o cadastro de HE tiver sido corrigido para outro significado, o usuário
deve conferir as variantes pelo editor; a migração preserva essa decisão.

Expressões completas de hora extra com percentual podem ser associadas mesmo
após outro adicional. ASG com insalubridade hora extra 50% separa INSA e HE 50%,
preservando ASG com na atividade. Na forma padronizada, usa INSA (HE 50%) para
manter a associação inequívoca ao reler. HE NOT 50% permanece ambíguo e o número
fica na atividade. Percentuais técnicos sem adicional e expressões negativas
conhecidas continuam preservados. Os percentuais são descritores para a busca,
não filtros rígidos de aceitação de resultados.

Reinicie Java e Python. A versão do separador é salva no índice; índices antigos
exigem reconstrução mesmo quando a revisão do SQL não mudou. A próxima busca
Java sincroniza o catálogo e recria os vetores necessários. As descrições
originais são preservadas. O peso nominal permanece 90/10.


## Correspondência parcial da atividade

A busca mantém o MiniLM e o 90/10 na similaridade semântica. Depois das prioridades
de código/descrição exatos e todas as palavras presentes, a ordenação combina essa
nota com um bônus de até 0,25 para palavras inteiras da atividade (BM25 normalizado
por consulta). Termos raros recebem mais importância; preposições/artigos e números
isolados não geram bônus. Fornecedores repetidos não alteram as estatísticas de
frequência das atividades. Não há correspondência por prefixo: motor não coincide
com motorista e maca não coincide com macaco.

Sem termos reconhecidos, a ordenação anterior é preservada; embeddings continuam
cobrindo sinônimos e erros de escrita. Qualificadores isolados não recebem bônus.
Percentuais, turno e negação não viraram filtros rígidos. Grupo e unidade continuam
fora dos embeddings e do índice textual da atividade.

similaridade mantém o comportamento anterior (inclusive os atalhos exatos);
similaridade_semantica expõe a nota 90/10. Nenhuma delas inclui o bônus. pontuacao_ordenacao não é percentual/probabilidade; os campos
pontuacao_textual_atividade e termos_atividade_encontrados explicam a contribuição
textual. /status expõe ranking_strategy e activity_text_bonus.

O índice textual é reconstruído em memória ao carregar/substituir o catálogo ou
trocar o dicionário. Não há novo modelo, dependência ou reindexação de embeddings
por causa deste ajuste. Reinicie o Python para ativar a nova ordenação.

## Limpeza de dados

Acesse **Limpeza de dados** no menu. A revisão usa uma cópia local persistente, compara somente com os registros já verificados e permite escolher qual equivalente manter. A base permanente só muda após concluir e confirmar **Usar como base permanente**. Veja [docs/LIMPEZA.md](docs/LIMPEZA.md) para armazenamento, retomada, marcações e regras do 100% semântico.

No Docker, o novo volume `cleanup_data` guarda essas cópias. Na execução manual, configure `CLEANUP_DIRECTORY` ou use `data/cleanups`. Depois de atualizar, reconstrua Java e Python com `docker compose up -d --build`. Não remova os volumes.
