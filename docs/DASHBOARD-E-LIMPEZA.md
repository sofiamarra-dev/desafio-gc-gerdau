# Dashboard e limpeza por pares semelhantes

## Uso

O Dashboard está no portal. A lista carrega páginas de 10, 20, 50 ou 100 códigos, com filtros de descrição/código, grupo, fornecedor, unidade e verificação. A paginação comum é feita no SQL com LIMIT/OFFSET e ordenação validada no servidor. É possível ordenar por código, descrição, grupo, fornecedor, unidade, verificação e data de cadastro.

Clique em **Analisar semelhanças** para obter a ordenação pelos pares mais próximos. Ela coloca primeiro quem tem correspondência “contém". Dentro de cada grupo, a similaridade do melhor par fica em ordem decrescente. Esta ordenação usa a análise completa, e não somente os códigos da página atual. “Contém” significa que a descrição do outro código possui todas as palavras inteiras da consulta, ignorando acentos e pontuação. É uma relação direcional: “PINTOR” está contido em “PINTOR INDUSTRIAL”, mas a consulta inversa não tem esse indicador.

O dashboard e a consulta dos 20 semelhantes são acessíveis a contas autenticadas. As alterações de limpeza são exclusivas de administradores.

## Limpeza direta com salvamento explícito

1. Ao abrir, o Python agrupa registros cadastralmente idênticos com uma tabela hash. A chave usa a descrição padronizada, grupo, unidade e descrição da unidade. Na limpeza, diferenças no código do fornecedor são ignoradas, inclusive fornecedor vazio. IDs e códigos de serviço também podem diferir. A regra da criação em massa distingue fornecedores. São preservadas diferenças de percentuais, qualificadores e metadados. Descrições que exigem revisão não são agrupadas automaticamente.
2. Em cada grupo, mantém-se o registro com melhor nível de verificação; em empate, o mais antigo e depois o menor ID. A operação é linear na quantidade de registros, além do processamento dos textos.
3. Quando há exclusões automáticas, um aviso obrigatório informa **X registros idênticos retirados da revisão**. As remoções ficam pendentes; confirmar o aviso libera a revisão manual. Quando X é zero, a tela segue diretamente para a revisão, sem popup e sem gravar alterações no catálogo.
4. A lista e os 20 semelhantes passam a usar somente os representantes mantidos na revisão. As opções são manter o inicial sem excluir, manter o inicial retirando o semelhante ou manter o semelhante retirando o inicial.
5. Ao manter um código, sua verificação aumenta na prévia e fica identificada como **pendente de salvar**. O código mantido ganha no máximo uma passagem por ciclo de salvamento, limitado ao nível completo. Remover duplicados automaticamente não equivale a uma revisão humana e não aumenta a verificação.
6. **Salvar alterações**, disponível na lista e na comparação, grava as exclusões e verificações em uma única transação. Depois o usuário pode continuar revisando sem preparar outra cópia. Salvar novamente sem alterações não aumenta níveis nem duplica operações.

Os links para fora da limpeza, o botão Sair da conta e o retorno do navegador oferecem **Salvar e sair**, **Sair sem salvar** ou **Continuar revisando** quando existem alterações pendentes. Navegar entre lista e comparação não precisa salvar. Ao fechar/recarregar a aba, o navegador mostra seu próprio aviso genérico.

Ao salvar, os registros retirados deixam de aparecer no catálogo ativo, mas seus originais e vínculos históricos são preservados. Alterações concorrentes no catálogo/dicionário e versões de abas desatualizadas são rejeitadas; o usuário pode descartar/recomeçar sem sobrescrever dados novos. Um comprovante SQL permite recuperar um salvamento que tenha confirmado a transação antes de ocorrer falha ao atualizar a memória da sessão.



## Algoritmo e custo

Implementação em `ai-python/review_index.py`: comparação exata de cosseno em blocos + índice invertido de palavras + seleção parcial dos melhores candidatos + cache por snapshot.

- Textos de atividade e qualificadores são codificados uma vez por texto novo e reutilizados em memória. Os pesos seguem 90/10; quando a consulta não tem um dos componentes, o outro recebe o peso disponível.
- O produto de matrizes NumPy calcula as similaridades em blocos de 64 consultas. Não é alocada uma matriz permanente N × N.
- O índice invertido encontra “contém” pela interseção dos conjuntos de códigos associados a cada palavra.
- `argpartition` seleciona candidatos antes de ordenar os até 20 resultados finais. Os resultados de contém recebem prioridade, sem substituir a nota semântica por 100%.
- Vetores e melhores vizinhos são salvos em `PYTHON_DATA_DIR/review-index` e identificados por hash do conteúdo, dicionário e modelo. Uma nova versão usa outra chave. O cache em memória guarda no máximo dois índices; os arquivos persistidos de versões antigas não são removidos automaticamente.
- Ao retirar códigos, o melhor par é escolhido entre sobreviventes. Se todos os 20 candidatos em cache foram retirados, a consulta é recalculada contra os sobreviventes para não apresentar um par errado ou inexistente.
- A primeira análise é assíncrona, com somente um cálculo de índice por vez. Paginação e filtros não refazem a matriz. O navegador recebe somente a página solicitada.

O cálculo inicial tem o custo O(N² × D), onde D é a dimensão do embedding. O uso principal de memória é O(N × D + 64 × N + 20 × N), além dos textos e do modelo. Isto reduz memória e evita inferências repetidas, mas não elimina o custo quadrático. Como a similaridade depende dos qualificadores da consulta, algumas comparações são direcionais; isso preserva o comportamento da busca atual.

Para bases maiores ou muito alteradas, recomenda-se avaliar um índice vetorial aproximado, como HNSW, para gerar candidatos e depois aplicar cosseno exato nesses candidatos. O índice invertido pode continuar garantindo a prioridade de “contém”. Essa alternativa reduz trabalho, mas pode omitir pares semânticos que uma análise exata encontraria. Outra opção é analisar em horários de menor uso e reaproveitar a versão pronta.
