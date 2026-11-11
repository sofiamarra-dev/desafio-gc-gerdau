# Desafio GC - Gerdau

Sistema de gestão e padronização de códigos de serviços, desenvolvido pelo Grupo 23 durante o desafio proposto pela Gerdau no programa Geração Caldeira.

## Sobre o desafio

O desafio surgiu da falta de padronização no catálogo de serviços utilizado nas contratações. Uma mesma atividade pode ser cadastrada com descrições diferentes, abreviações variadas ou informações organizadas de formas distintas. Isso dificulta a pesquisa e pode levar à criação de códigos duplicados.

Para os contratantes, esse cenário aumenta o tempo necessário para encontrar um serviço e decidir se um novo cadastro realmente precisa ser criado. Para quem administra a base, torna mais difícil organizar os registros e manter um padrão ao longo do tempo.

A proposta do projeto é apoiar essas duas necessidades: facilitar o uso diário do catálogo e melhorar a qualidade dos dados, sem tornar o processo de cadastro mais trabalhoso. Os benefícios esperados são a redução de duplicidades, o reaproveitamento de códigos existentes e a diminuição do trabalho de conferência.

## Nossa solução

Criamos uma plataforma web que reúne pesquisa, criação unitária, criação em massa, dashboard, dicionário de siglas e limpeza de dados. O sistema compara as solicitações com o catálogo existente antes da criação de novos registros, ajudando o usuário a tomar uma decisão.

A pesquisa considera tanto as palavras da descrição quanto a proximidade de significado entre os textos. O dicionário de siglas ajuda a padronizar diferentes formas de escrever uma mesma expressão. O banco de dados centraliza os cadastros, enquanto as importações e exportações em Excel facilitam o trabalho com planilhas.

O objetivo é apoiar a decisão do contratante e do administrador. Uma sugestão de alta similaridade, por si só, não confirma que dois serviços são iguais.

## Perfis de acesso

O acesso ocorre por um código temporário enviado por e-mail, com validade de cinco minutos. Após a validação, o Java mantém a sessão autenticada. Na implementação atual, o login aceita endereços Gmail; o uso de e-mails empresariais depende de uma adaptação dessa regra.

| Perfil | Acesso |
|---|---|
| Contratante | Pesquisa, criação unitária, criação em massa, dashboard e operações do catálogo |
| Administrador | Recursos do contratante, além do dicionário de siglas e da limpeza de dados |

A lista de administradores está em `src/main/java/com/example/demo/security/AdminAccounts.java`. A proteção das funcionalidades é feita no servidor, além da apresentação das opções na interface.

## Tecnologias utilizadas

| Tecnologia | Uso no projeto |
|---|---|
| HTML, CSS e JavaScript | Telas, formulários, navegação e comunicação com o servidor |
| Java 21 e Spring Boot | Regras de negócio, APIs e integração entre os serviços |
| Spring Security | Autenticação da sessão e controle de acesso por perfil |
| Spring JDBC e PostgreSQL | Consulta e armazenamento dos dados |
| Flyway | Criação e atualização da estrutura do banco por migrações |
| Apache POI | Leitura e geração de planilhas Excel |
| Python e FastAPI | Serviço de busca, padronização e comparação das descrições |
| SentenceTransformer e MiniLM | Geração dos embeddings usados nas comparações semânticas |
| NumPy | Cálculos de similaridade entre os vetores |
| Python e Flask | Serviço de envio e validação do código de login |
| Docker Compose | Execução conjunta do banco, Java, Python e autenticação |
| Gradle | Gerenciamento das dependências e execução de tarefas Java |

## Organização e arquitetura

O navegador envia as solicitações ao Java. O Java aplica as regras de negócio, consulta o PostgreSQL e utiliza o serviço Python quando precisa padronizar ou comparar descrições. Para o login, ele se comunica com o serviço de autenticação por token.

O PostgreSQL é a fonte principal dos cadastros. O Python mantém os dados necessários à busca e seus embeddings, que são sincronizados com a versão do catálogo. Assim, não é necessário gerar novamente todos os vetores a cada pesquisa.

| Diretório ou arquivo | Conteúdo |
|---|---|
| `src/main/resources/static/` | Páginas, estilos e scripts da interface |
| `src/main/java/com/example/demo/api/` | Controladores que recebem as solicitações da interface |
| `src/main/java/com/example/demo/service/` | Regras de cadastro, importação, busca e limpeza |
| `src/main/java/com/example/demo/security/` | Integração do login e permissões |
| `src/main/resources/db/migration/` | Migrações do banco de dados |
| `ai-python/` | API Python, modelo de embeddings e regras de comparação |
| `auth-token/` | Serviço de autenticação por código enviado por e-mail |
| `docs/` | Documentação complementar |
| `scripts/` | Scripts de apoio e validação |
| `compose.yml` | Configuração dos serviços e volumes do Docker |

## Funcionalidades

### Pesquisa de códigos e embeddings

Embeddings são representações numéricas de textos. Eles permitem comparar descrições pelo significado, mesmo quando as palavras utilizadas são diferentes. O modelo utilizado é o `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`, executado localmente em CPU e com suporte ao português.

O sistema separa a atividade principal dos qualificadores reconhecidos pelo dicionário. Quando ambos estão presentes na consulta, a atividade recebe peso de 90% e os qualificadores, 10%. Quando um dos componentes está ausente, o peso disponível é aplicado ao outro. Essa separação ocorre na própria descrição e depende do reconhecimento das expressões cadastradas no dicionário.

Por exemplo, em uma consulta por um pintor em hora extra, a atividade de pintura deve ter mais influência do que o adicional de horário. A busca também utiliza correspondências textuais para organizar os resultados e apresenta até 20 semelhantes.

A indicação **contém** aparece quando a descrição comparada possui todas as palavras inteiras da consulta, ignorando acentos e pontuação. Ela tem prioridade na lista, mas não representa uma similaridade semântica de 100%. A nota semântica também não é uma probabilidade de dois serviços serem duplicados.

### Criação unitária

O formulário permite montar a descrição com a atividade principal e os qualificadores selecionados. Antes de confirmar um novo cadastro, o usuário pode consultar serviços semelhantes e escolher um código existente.

O identificador do serviço é separado da descrição. Se não for informado, o sistema gera um identificador como `LOCAL-00000001`. Esse código é interno ao protótipo e não é emitido pelo SAP.

Cada pedido utiliza um `request_id`, identificador que evita a duplicação do cadastro quando a mesma confirmação é repetida. O serviço é salvo no banco antes da sincronização da busca. Os embeddings anteriores são reaproveitados, e apenas textos novos ou alterados precisam ser processados novamente.

### Criação em massa

Permite enviar várias solicitações por planilha Excel ou por texto. O envio cria um lote para acompanhar as linhas e suas decisões. Ele não significa que todas as linhas serão cadastradas imediatamente.

| Modo | Funcionamento |
|---|---|
| Normal | As solicitações não resolvidas como idênticas passam pela revisão dos semelhantes antes da decisão |
| Rápido | A revisão manual é exigida quando há algum correspondente com **contém** ou similaridade semântica acima de 90%; as demais solicitações podem ser cadastradas automaticamente |

Os registros criados automaticamente pelo modo rápido exigem duas passagens pela limpeza para atingir a verificação completa. Os demais seguem o nível de verificação previsto para sua forma de criação. O dashboard apresenta essa situação.

A reutilização automática de um registro exige identidade pelas regras cadastrais, não apenas nota semântica de 100%. Na criação em massa, a comparação de identidade considera a descrição padronizada, grupo, fornecedor, unidade e descrição da unidade. Diferenças relevantes são preservadas para avaliação.

Durante a revisão, o usuário pode usar um serviço existente, confirmar um novo cadastro ou deixar a solicitação para depois. As descrições são padronizadas pelo dicionário antes do cadastro, com ajuste dos espaços entre as expressões.

#### Envio por Excel

Na tela **Criação em massa**, escolha **Planilha Excel** e clique em **Baixar planilha modelo**. O arquivo `modelo-solicitacoes.xlsx` contém os seis cabeçalhos aceitos, sem linhas de exemplo. Preencha a partir da linha 2, mantenha os cabeçalhos e envie o arquivo XLSX.

#### Envio por texto

Escolha **Colar texto** e informe uma solicitação por linha, sem cabeçalho, nesta ordem:

```text
Descrição do Item;Grupo de Serviço;Código do Fornecedor;Código do Serviço;Unidade de medida;Descrição da Unidade
```

A linha acima mostra a ordem dos campos e não deve ser colada como solicitação. Exemplo com dados fictícios para teste:

```text
SV-PINTOR HE;Civil;900000001;9900001;H;Hora
CUIDADO COM PLANTAS;Facilities;900000002;9900002;SV;Serviço
```

O resultado do lote pode ser exportado com a linha de origem, situação, dados solicitados, código final e informações de verificação.

### Dicionário de siglas

Disponível para administradores, permite cadastrar siglas padronizadas, seus significados e variantes de escrita. Também permite pesquisar, editar, desativar e consultar correções anteriores.

Uma variante não pode ficar associada a duas siglas diferentes. Entretanto, essa proteção não identifica sozinha um significado cadastrado de forma incorreta: essa avaliação depende de revisão humana. Expressões ambíguas, como `ESP` e `ADM`, devem ser tratadas por contexto e por expressões completas confirmadas, evitando conversões automáticas de termos isolados com mais de um significado.

### Dashboard

Apresenta o catálogo com páginas de 10, 20, 50 ou 100 registros. Possui filtros por descrição ou código, grupo, fornecedor, unidade e verificação. Permite ordenar por código, descrição, grupo, fornecedor, unidade, verificação e data de cadastro.

A opção **Analisar semelhanças** prepara a ordenação pelo par mais próximo de cada registro. Os pares com **contém** aparecem primeiro. A análise considera o catálogo completo, não apenas a página exibida.

### Limpeza de dados

Disponível para administradores, apresenta os registros com seus pares mais próximos e permite abrir uma comparação com até 20 semelhantes.

Ao entrar, o sistema identifica registros cadastralmente idênticos e os retira da lista de revisão. Essa igualdade considera a descrição padronizada, grupo, unidade e descrição da unidade. Diferentemente da criação em massa, diferenças de fornecedor não impedem o agrupamento na limpeza. Percentuais, qualificadores e outros dados relevantes não são descartados apenas por proximidade semântica.

O usuário pode manter o código inicial sem excluir semelhantes, manter o inicial e retirar um semelhante ou manter o semelhante e retirar o inicial. Ao manter um código, seu nível de verificação aumenta na prévia, no máximo uma passagem por ciclo de salvamento e até o nível completo. A exclusão automática de idênticos não conta como revisão humana.

As alterações só chegam à base permanente ao clicar em **Salvar alterações**. Se houver alterações pendentes ao sair da tela, o sistema oferece opções para salvar, sair sem salvar ou continuar a revisão. Os registros retirados deixam o catálogo ativo, mas os originais e os vínculos históricos são preservados no banco.

Veja os detalhes em [Dashboard e limpeza](docs/DASHBOARD-E-LIMPEZA.md).

### Operações do catálogo

- **Recuperar catálogo anterior:** importa para o SQL os registros disponíveis no catálogo Python e sincroniza a busca. Não é uma restauração de backup nem desfaz uma limpeza.
- **Importar base de referência:** acrescenta registros de uma planilha ao SQL, sem a revisão individual da criação em massa. A combinação de código do serviço e fornecedor já existente é ignorada, inclusive quando o registro foi retirado do catálogo ativo. Por isso, a quantidade de linhas informadas como existentes pode superar a quantidade de registros ativos no painel.
- **Exportar catálogo Excel:** baixa um novo XLSX com os registros ativos do banco. Não altera a planilha original do usuário.

## Como executar

### Execução completa com Docker

Tenha o Docker Desktop instalado e ativo em modo de contêineres Linux. Na pasta que contém `compose.yml`, execute:

```powershell
docker compose up -d --build
docker compose ps
```

O Compose inicia quatro serviços: `db` (PostgreSQL), `ai` (API Python), `auth` (autenticação por token) e `java` (aplicação web). Na primeira execução, o download do modelo pode aumentar o tempo de inicialização e exige conexão com a internet.

Abra a aplicação em http://localhost:8080/. Páginas protegidas encaminham o usuário ao login. O estado do Python pode ser consultado em http://localhost:8001/status e sua documentação em http://localhost:8001/docs.

O login depende de uma configuração válida de envio de e-mails no serviço `auth-token`. Iniciar os contêineres não garante, sozinho, que o envio esteja configurado e funcionando.

Para aguardar os serviços pelo terminal ou acompanhar mensagens de execução:

```powershell
docker compose up -d --build --wait --wait-timeout 900
docker compose logs -f ai java auth
```

As portas locais padrão são 8080 para o Java, 8001 para o Python e 5433 para o PostgreSQL. O serviço de autenticação é acessado pelo Java na rede interna do Compose. As opções de configuração estão em `.env.example`.

Os dados são mantidos em volumes do Docker. Para parar sem remover esses volumes:

```powershell
docker compose stop
```

O comando `docker compose down -v` remove os volumes e pode apagar os dados locais. Antes de usá-lo, faça um backup se precisar preservar o catálogo.

### Java pela IDE e Python pelo terminal

Para desenvolvimento, é possível manter somente o banco e a autenticação no Docker:

```powershell
docker compose up -d db auth
```

O serviço `auth` não publica uma porta local na configuração atual. Para usar o Java fora do Docker, é necessário disponibilizar o serviço de autenticação em um endereço acessível e ajustar `AUTH_TOKEN_URL`; a configuração padrão do Java espera `http://127.0.0.1:5000`.

Com Python 3.11 e Java 21 instalados, na raiz do projeto, prepare e inicie a API Python:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r ai-python/requirements.txt
.\.venv\Scripts\python.exe -m uvicorn main:app --app-dir ai-python --host 127.0.0.1 --port 8001
```

Em outro terminal, inicie o Java pela IDE ou pelo Gradle:

```powershell
.\gradlew.bat bootRun
```

Nessa forma de execução, o Java usa o Tomcat incorporado ao Spring Boot. Não é necessário instalar um Tomcat separado. As configurações locais do banco e do Python estão em `src/main/resources/application.properties` e podem ser substituídas por variáveis de ambiente. As variáveis usadas no Compose não são automaticamente aplicadas ao processo iniciado pela IDE.

## Como verificar o funcionamento

1. Confira se os quatro serviços estão ativos e se `/status` do Python responde.
2. Faça login e confira as opções disponíveis para o perfil utilizado.
3. Importe uma base de referência e verifique a quantidade de registros no dashboard.
4. Pesquise uma descrição e confira as sugestões e a indicação **contém**, quando aplicável.
5. Envie um pequeno lote de teste por Excel ou texto e confira as decisões antes de criar registros.
6. Como administrador, revise a limpeza e confira as alterações pendentes antes de salvar.
7. Exporte o catálogo para verificar os registros ativos.

Para executar os testes automatizados Java e Python, com as dependências de teste disponíveis:

```powershell
.\gradlew.bat test
.\.venv\Scripts\python.exe -m pip install pytest httpx
.\.venv\Scripts\python.exe -m pytest ai-python/tests
```

O script `scripts/test-docker.ps1` contém verificações adicionais do ambiente Docker. Consulte o script antes de executá-lo para entender as portas, os dados de teste e a remoção do ambiente criado.

## Rotas principais

| Rota Java | Função |
|---|---|
| `POST /enviar` e `POST /verificar` | Solicitar e validar o código de login |
| `GET /api/auth/status` | Consultar a sessão autenticada |
| `POST /api/auth/logout` | Encerrar a sessão |
| `POST /api/import` | Acrescentar a base de referência ao SQL |
| `POST /api/search` | Sincronizar o catálogo, se necessário, e pesquisar no Python |
| `GET /api/catalog/status` | Consultar a quantidade e a revisão do catálogo SQL |
| `POST /api/catalog/migrate-python` | Recuperar explicitamente o catálogo anterior do Python |
| `POST /api/catalog/services` | Criar um serviço com `request_id` |
| `GET /api/catalog/export` | Exportar o catálogo ativo |
| `POST /api/catalog/batches` | Enviar solicitações em XLSX |
| `POST /api/catalog/batches/text` | Enviar solicitações por texto |
| `GET /api/catalog/batches/template` | Baixar a planilha modelo |
| `GET /api/catalog/batches` | Retomar lotes e consultar o progresso |
| `GET /api/catalog/batches/{id}/items` | Consultar linhas do lote |
| `POST /api/catalog/batches/{id}/items/{item}/decision` | Confirmar a criação ou o uso de um existente |
| `GET /api/catalog/batches/{id}/export` | Exportar as decisões do lote |
| `GET /api/catalog/dashboard` | Consultar o catálogo com filtros e paginação |
| `GET /api/abbreviations` | Consultar o dicionário, com acesso de administrador |

## Limitações e melhorias futuras

O sistema é um protótipo e não possui integração com o SAP nem vínculo operacional com contratos e ordens de compra. As sugestões dependem da qualidade das descrições, do dicionário e do modelo. Consultas curtas ou ambíguas podem retornar serviços pouco relacionados, mesmo com uma nota elevada.

As próximas melhorias podem incluir:

- Avaliar a qualidade da busca com exemplos reais revisados por usuários e ajustar seus pesos com base nos resultados.
- Comparar modelos de embeddings e incluir uma segunda etapa de avaliação dos candidatos mais próximos.
- Utilizar um índice vetorial aproximado, como HNSW, para reduzir o custo da análise em bases maiores. A análise completa atual calcula comparações em blocos e reaproveita resultados, mas seu custo inicial cresce de forma quadrática com a quantidade de registros.
- Ampliar a gestão de usuários, permitir e-mails empresariais e retirar a lista de administradores do código.
- Reforçar a configuração do envio de tokens, a proteção das credenciais e os controles de tentativas de acesso antes de uso em produção.
- Evoluir os relatórios de qualidade, o acompanhamento de alterações e as rotinas de backup e recuperação.
- Avaliar a integração com sistemas corporativos, conforme as regras e permissões da empresa.

## Equipe

- Sofia Marra
- Renan Macedo
- Guilherme Barros
- Nathallya Fontoura
- Daniel Osterkamp
