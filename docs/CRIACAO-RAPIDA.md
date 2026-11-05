# Criação rápida

Na tela Criação em massa, selecione **Normal** ou **Rápida** antes de enviar Excel ou texto. O modo fica gravado no lote; retomá-lo mantém a mesma regra. Lotes existentes continuam como Normal.

O servidor compara cada solicitação com todo o catálogo ativo, incluindo os novos cadastros das linhas anteriores. Registros idênticos são reutilizados sem criar outro cadastro nos dois modos.

No modo Rápida, uma solicitação fica para decisão humana quando existe qualquer descrição contendo todas as palavras da consulta, ignorando acentos e respeitando palavras inteiras, ou quando a similaridade semântica ponderada de atividade/qualificadores é **estritamente maior que 0,90**. A regra usa o valor sem arredondamento, sem bônus de palavras. O resumo da comparação considera todos os candidatos, inclusive os que ficaram fora dos 20 resultados exibidos. Descrições sem atividade identificada também ficam para decisão humana.

As demais solicitações geram automaticamente um cadastro com `creation_kind=QUICK`, `cleanup_passes=0` e `required_cleanup_passes=2`. Quem confirma manualmente um novo cadastro na tela recebe a classificação padrão, mesmo em um lote rápido: uma limpeza necessária, como nos outros cadastros revisados manualmente. Reutilizar um existente preserva o nível dele.

## Limpezas e verificação

- Cadastro rápido automático: 0/2 → primeira limpeza aplicada: 1/2, parcial → segunda limpeza aplicada: 2/2, verificado.
- Cadastro padrão: uma limpeza aplicada para ficar verificado.
- Somente confirmar a cópia como base permanente conta uma passagem. A revisão local, a pausa, a retomada e a reaplicação da mesma cópia não somam passagens extras.
- Uma passagem é contada no máximo uma vez por cadastro por limpeza. A tabela `catalog_cleanup_review` guarda o comprovante. O registro usado como primeira referência da cópia também conta uma passagem ao aplicar.
- Registros pulados pela opção Ignorar já verificados não recebem outra passagem. Um rápido em 1/2 ainda entra na próxima limpeza.
- Ao manter um registro no lugar de outro, o mantido conserva sua própria exigência de verificação. Um rápido não herda automaticamente o nível do substituído.
- A migração V7 preserva a situação dos cadastros antigos: os que já tinham `cleaned_at` começam com uma passagem e continuam verificados. Cadastros antigos não limpos continuam pendentes.

O nível aparece na revisão da limpeza e nas colunas adicionais do resultado Excel do lote. Na criação rápida, o resumo distingue reutilizações de cadastros automáticos. Falhas do Python, versões antigas sem a nova análise e alterações concorrentes no catálogo/dicionário deixam a linha pendente, sem criar um serviço com uma análise desatualizada.

## Atualização

Com Docker Desktop iniciado, execute na raiz do projeto:

```powershell
docker compose up -d --build java ai
```

O Java aplica a migração V7 ao iniciar. Atualize Java e Python juntos, pois a classificação rápida precisa do resumo novo do comparador. Depois recarregue a tela Criação em massa. Não é necessário excluir banco, volumes ou dados existentes.
