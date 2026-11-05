# Limpeza do catálogo

Abra `limpeza.html` pelo menu. Clique em **Criar cópia e iniciar**. Por padrão,
os registros mantidos em limpezas já aplicadas entram como referências. Desmarque
**Ignorar registros já verificados** para começar a revisão de todos novamente.

A cópia contém o catálogo ativo, o dicionário usado, as decisões e o progresso.
Somente os registros mantidos na cópia são candidatos a comparação. Os outros
pendentes nunca são utilizados como referência. O primeiro registro, quando
não há referências, inicia esse conjunto sem comparação.

Em cada revisão, escolha manter o já verificado, manter o registro em análise,
ou manter como serviço distinto. Ao substituir uma referência, os vínculos de
decisões anteriores passam a acompanhar o representante escolhido. **Pausar**
aguarda a comparação em andamento; fechar a página não perde decisões salvas.
Use **Limpezas salvas** para retomar inclusive após reiniciar o Java.

## Cópia local e confirmação

Na execução manual, os arquivos JSON ficam em `data/cleanups`, relativo à pasta
de execução do Java. `CLEANUP_DIRECTORY` configura outro local. No Docker, o
volume `cleanup_data` mantém os arquivos em `/data/cleanups`. Os arquivos não
são gravados no armazenamento do navegador e não são adicionados ao Git.

Durante a revisão, o catálogo permanente não muda. A confirmação em **Usar como
base permanente** fica disponível apenas quando não há pendências. Ela aplica
as decisões numa transação SQL e sincroniza o índice Python. Se o catálogo ou
o dicionário mudar durante a revisão, a aplicação é bloqueada; crie uma nova
cópia para incluir essas mudanças. A cópia antiga continua preservada.

O PostgreSQL recebe `cleaned_at` (data da limpeza), `cleanup_id` e `replaced_by`
(representante quando substituído). Os originais permanecem no banco como
histórico; buscas e exportações do catálogo usam somente os mantidos. Decisões
antigas de lotes acompanham os representantes atuais. Confirmações repetidas
não aplicam a limpeza duas vezes. Uma falha posterior no índice não desfaz o
SQL confirmado; a próxima pesquisa tenta sincronizar novamente.

Não remova os volumes para atualizar o sistema: `down --volumes` apaga também
as cópias locais persistidas. Esta versão usa uma única instância Java para
editar os arquivos de revisão.

## Passagem automática por embeddings

A limpeza e a criação em massa consultam `/compare`, que recebe explicitamente
os candidatos e não altera o catálogo permanente do Python. Na limpeza, os
candidatos são apenas os verificados; na criação em massa, são os serviços
ativos do catálogo SQL. O próprio registro é excluído da comparação.

A automação usa `semantic_exact` e a nota semântica original, sem arredondar.
Uma tolerância de até `1e-7` absorve somente erro numérico do cosseno. Igualdade
textual, bônus de palavras e o indicador “contem” não ativam a automação.
Quando há 100% semântico, o já verificado/existente é mantido. Na criação em
massa, a linha recebe EXISTING; nenhum serviço novo é inserido.

Os pesos continuam seguindo a busca atual: atividade 90%, qualificadores 10%.
Se a descrição em análise não tiver qualificadores, somente a atividade conta.
Assim, por exemplo, `Pintor` pode atingir 100% com `Pintor HE 50%`; a presença de
qualificadores no candidato não penaliza uma consulta sem qualificadores.
Os embeddings medem proximidade dos textos preparados, sem impor igualdade de
código, fornecedor ou unidade. Os originais são preservados ao aplicar.

## Testes

- Python: `python -m pytest ai-python/tests -q`.
- Java: `./gradlew test` (Windows: `./gradlew.bat test`).
- Interface: `node --test src/test/javascript/*.test.cjs`.

Cobertura nova: escopo somente verificados; retomada por arquivo; escolha do
novo/antigo; vínculos transitivos; confirmação obrigatória; filtro opcional;
conflito com base alterada; recuperação após interrupção da confirmação;
equivalência semântica original; falhas e repetição na criação em massa.
