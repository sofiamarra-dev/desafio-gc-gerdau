# Desafio GC - Gerdau

Projeto desenvolvido durante o desafio proposto pela Gerdau, através do programa Geração Caldeira.

## Sobre o desafio
(Qual era o problema proposto?)

## Nossa solução
(O que vocês criaram e como funciona?)

## Tecnologias utilizadas


## Como executar

Requer Docker Desktop ativo em modo de contêineres Linux. Na pasta que contém
`compose.yml`, execute:

```powershell
docker compose up -d --build
docker compose ps
docker compose logs -f ai java
```

Abra http://localhost:8080/menu.html. Python: http://localhost:8001/status.

Para aguardar pelo terminal:

```powershell
docker compose up -d --build --wait --wait-timeout 900
```

### Funcionalidades 

## Cadastro unitário

Preencha a descrição e consulte os semelhantes antes de confirmar novo cadastro.
O identificador é separado da descrição montada pelo formulário. Se não for informado, será gerado um identificador `LOCAL-00000001`.
Ele é interno ao protótipo, não é um código emitido pelo SAP.
O pedido possui `request_id` para que repetir a mesma confirmação não duplique o cadastro.
O serviço é salvo no banco antes da sincronização. Na próxima busca, somente os textos novos/alterados geram embeddings; os anteriores são reaproveitados.

## Criação em massa

Na tela **Criação em massa**, escolha **Planilha Excel** e clique em **Baixar planilha modelo**.
O arquivo `modelo-solicitacoes.xlsx` contém os seis cabeçalhos aceitos, sem linhas de exemplo.
Preencha a partir da linha 2, mantenha os cabeçalhos e envie o XLSX.

O arquivo cria um lote, sem inserir serviços imediatamente. Cada linha válida fica pendente:

1. **Usar existente** vincula a solicitação ao serviço escolhido sem criar outro.
2. **Confirmar novo cadastro** cria um serviço e registra a decisão de forma transacional.
3. **Revisar depois** deixa a linha pendente e navega para outra.

# Excel

**Exportar catálogo** baixa um novo XLSX com os serviços SQL. **Baixar resultado do lote** inclui linha de origem, situação, dados solicitados e código final.
O sistema não altera o arquivo original no computador do usuário. Não existem vínculos com contratos nem login implementados nesta etapa.

# Criação em massa por texto

Na tela **Criação em massa**, escolha **Colar texto**. Use uma solicitação por
linha, sem cabeçalho, na ordem:
`Descrição do Item;Grupo de Serviço;Código do Fornecedor;Código do Serviço;Unidade de medida;Descrição da Unidade`.

## Edição de siglas

No menu, abra **Gerenciar siglas** (`/siglas.html`). A lista permite buscar por
sigla, significado e variantes, filtrar ativas/inativas e cadastrar novas siglas.
As variantes são informadas uma por linha. É possível editar
variantes e desativar uma sigla, preservando a reserva de suas expressões.

## Limpeza de dados

Acesse **Limpeza de dados** no menu. A revisão usa uma cópia local persistente, compara somente com os registros já verificados e permite escolher qual equivalente manter. A base permanente só muda após concluir e confirmar **Usar como base permanente**. Veja [docs/LIMPEZA.md](docs/LIMPEZA.md) para armazenamento, retomada, marcações e regras do 100% semântico.

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

## Equipe
- Nome 1 Sofia Marra
- Nome 2 Renan Macedo
- Nome 3 Guilherme Barros
- Nome 4 Nathallya Fontoura  
- Nome 5 Daniel Osterkamp



