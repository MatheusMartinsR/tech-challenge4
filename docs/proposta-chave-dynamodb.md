# Proposta: chave da tabela de feedbacks (DynamoDB)

> **Status: proposta — precisa da concordância do grupo antes do primeiro deploy em `prod`.**
> No DynamoDB, mudar a chave de uma tabela existente exige recriá-la (os dados são perdidos).

## Por que a chave importa

No DynamoDB só existem duas consultas eficientes:

- **`GetItem`**: buscar um item pela chave completa;
- **`Query`**: buscar os itens de **uma partition key**, filtrando/ordenando pela **sort key**.

Qualquer outra pergunta vira **`Scan`** (leitura da tabela inteira). Por isso a chave é escolhida a partir das
perguntas que o sistema faz (*padrões de acesso*), e não o contrário.

## Padrões de acesso

| # | Quem | Operação |
|---|---|---|
| 1 | Ingestão | Gravar um feedback |
| 2 | Relatório semanal | Ler todos os feedbacks de um período (a semana anterior) |
| 3 | Relatório semanal | Quantidade de feedbacks **por dia** |
| 4 | Relatório semanal | Média das notas e quantidade **por urgência** |

## Proposta

| Atributo | Exemplo | Papel |
|---|---|---|
| `pk` (partition key) | `DAY#2026-10-29` | Agrupa os feedbacks de um dia |
| `sk` (sort key) | `2026-10-29T14:03:12.123Z#7f3c…` | Data/hora de envio + id (o id evita colisão entre envios no mesmo instante) |
| `id` | `7f3c…` (UUID) | Identificador do feedback |
| `description` | `"A aula ficou confusa"` | Campo `descricao` do payload |
| `rating` | `3` | Campo `nota` do payload (0 a 10) |
| `urgency` | `CRITICAL` | Calculada na ingestão |
| `createdAt` | `2026-10-29T14:03:12.123Z` | Data/hora de envio (ISO-8601, UTC) |

Como cada padrão é atendido:

1. **Gravar**: `PutItem` com `pk = DAY#<data>` e `sk = <createdAt>#<id>`.
2. **Ler a semana**: 7 `Query` (uma por dia, `pk = DAY#<data>`). Cada uma lê apenas aquele dia, já ordenado por horário.
3. **Por dia**: sai direto da etapa anterior, porque cada `Query` corresponde a um dia.
4. **Média e por urgência**: calculadas em memória sobre os itens lidos (o volume semanal é pequeno).

A função de relatório só precisa de `dynamodb:Query`, e a de ingestão só de `dynamodb:PutItem`. Nenhuma faz `Scan`.

## Pontos para o grupo decidir

1. **Fuso do "dia" da `pk`.** Proposta: a data no **horário de Brasília** (`America/Sao_Paulo`), para que "feedbacks
   do dia 29" signifiquem o dia 29 para quem usa o sistema. O `createdAt` continua em UTC.
2. **Critério de urgência** (ex.: `nota <= 3` → `CRITICAL`). Definido pela ingestão, mas usado pelo relatório.
3. **Registro de auditoria das notificações.** Pode ficar na mesma tabela, com outro prefixo
   (`pk = NOTIFICATION#<data>`), ou apenas nos logs estruturados do CloudWatch.

## Alternativas consideradas

| Alternativa | Por que não |
|---|---|
| `pk = id` (UUID), sem sort key | O relatório precisaria de `Scan` + filtro por data |
| `pk = "FEEDBACK"` fixo, `sk = createdAt#id` | Funciona (uma `Query` por semana), mas concentra tudo em uma única partição. Aceitável neste volume, porém é um anti-padrão conhecido do DynamoDB |
| `pk = WEEK#2026-W44` | Uma `Query` por semana, mas a contagem por dia fica toda em memória e a "semana" vira uma regra de negócio fixada na chave |
| Índice secundário (GSI) por data | Mais um recurso para manter, sem ganho para esses padrões de acesso |
