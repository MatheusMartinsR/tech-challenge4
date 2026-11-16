# Ingestão de feedback — Pedro

Implementação dos três tickets de ingestão: recebimento e persistência, validação e erros,
publicação de evento crítico e testes. Código em `functions/ingestion` (Java 21 e Maven).

Para testar manualmente pelo Postman, use o ambiente Docker completo descrito no
[README](../README.md#rodar-localmente-docker-desktop): `docker compose -f compose.local.yaml up -d --build`.
Ele expõe `POST http://localhost:3000/avaliacao` e cria o banco e a mensageria locais.
Não requer JDK, Maven ou SAM instalados no computador; o Docker faz o build.

## API

O endpoint existente da stack é **POST /avaliacao**, sem acento. O enunciado usa `/avaliação`;
o projeto mantém a rota já integrada ao API Gateway. Envie `Content-Type: application/json`
e a API Key no header `x-api-key`.

```json
{"descricao":"Aula com áudio inaudível","nota":2}
```

Descrição: texto não vazio, até 1000 caracteres antes de remover espaços das extremidades.
Nota: número JSON inteiro de 0 a 10 (strings, booleanos e decimais como `3.0` são rejeitados).
Campos extras são ignorados. JSON malformado, campos duplicados e conteúdo após o JSON são
rejeitados pela função. Corpos base64 do evento proxy também são suportados.

Criticidade adotada: **0 a 3 → ALTA**; **4 a 10 → NORMAL**. Apenas `ALTA` publica no SNS.
Esta regra é uma escolha de implementação; o enunciado não define o limite.

Sucesso: **201 Created**.

```json
{
  "id":"UUID gerado pela função",
  "descricao":"Aula com áudio inaudível",
  "nota":2,
  "urgencia":"ALTA",
  "dataEnvio":"2026-11-15T01:30:00Z"
}
```

| HTTP | codigo | Significado |
|---|---|---|
| 400 | ENTRADA_INVALIDA | Corrigir a entrada conforme `mensagem` |
| 503 | PERSISTENCIA_INDISPONIVEL | Não foi possível confirmar a gravação no banco |
| 503 | PUBLICACAO_INDISPONIVEL | Gravação concluída, mas publicação do alerta não confirmada; inclui `id` e `persistido: true` |

Erros de autenticação e limites continuam sob responsabilidade do API Gateway.
O validator do Gateway rejeita payloads inválidos antes da Lambda e usa o mesmo formato
`codigo`/`mensagem` para `BAD_REQUEST_BODY`. A função repete a validação para invocações diretas.
Quando a rejeição ocorre no Gateway, não há execução nem log de validação na Lambda.

## Persistência e contrato de integração

Variáveis obrigatórias: `TABLE_NAME` e `TOPIC_ARN`, já fornecidas pelo template SAM.
As roles existentes já permitem `dynamodb:PutItem` e `sns:Publish`.

| Campo DynamoDB | Valor |
|---|---|
| pk | `DAY#yyyy-MM-dd`, dia no fuso `America/Sao_Paulo` |
| sk | `<timestamp UTC>#<UUID>` |
| id | UUID |
| description | descricao sem espaços nas extremidades |
| rating | nota, tipo Number |
| urgency | ALTA ou NORMAL |
| createdAt | instante UTC ISO-8601 |

A chave segue a proposta do banco. Os valores de `urgency` adotados aqui são `ALTA` e
`NORMAL` (o exemplo `CRITICAL` do documento de proposta não é utilizado).
O relatório deve consultar os sete dias locais e usar esses atributos/valores.

A publicação ocorre **depois da gravação**. O JSON publicado no tópico contém exatamente
`id`, `descricao`, `nota`, `urgencia`, `dataEnvio`, compatível com `CriticalFeedbackEvent`
da função de notificação. SNS entrega o evento cru à fila existente.

Os logs da função contêm evento, requestId, feedbackId quando disponível e detalhe controlado.
Não registram a descrição do aluno nem mensagens internas de exceções dos serviços.
Eventos: `validation_failed`, `persistence_failed`, `publication_failed`, `evaluation_created`.

### Limite de consistência

DynamoDB e SNS não compartilham uma transação. Se a gravação funcionar e o SNS falhar,
o feedback continua salvo e a função retorna erro explícito com seu ID. Não há recuperação
automática desse intervalo nesta implementação. Investigue `publication_failed` e republique
o evento correspondente; a DLQ de notificação só cobre eventos que chegaram à fila.
Reenviar o POST gera outro UUID e pode duplicar avaliações. Um timeout do SDK também pode
deixar o resultado da operação incerto. Para uma garantia transacional futura, usar outbox
com DynamoDB Streams e consumidores idempotentes.

## Testes locais

Pré-requisitos: JDK 21 e Maven.

```sh
mvn -f functions/ingestion/pom.xml test
```

Testes unitários cobrem limites de nota, descrição, tipos inválidos, JSON inválido,
base64, chave por dia de Brasília, contrato do evento, ordem salvar/publicar,
falhas de DynamoDB/SNS e ausência de texto do aluno nos logs.

### Integração com DynamoDB, SNS e SQS

Se o compose acima já estiver ativo, execute diretamente o Maven com `-Pintegration`:
os testes usam recursos temporários separados dos dados da API local.
Como alternativa, com Docker ativo, inicie apenas o emulador dedicado (não rode os dois
ambientes simultaneamente, pois ambos usam a porta 4566):

```sh
docker run --rm -d --name tc4-ingestion-localstack -p 4566:4566 -e SERVICES=dynamodb,sns,sqs localstack/localstack:4.0.3
```

Aguarde `http://localhost:4566/_localstack/health` responder e execute:

```sh
mvn -f functions/ingestion/pom.xml verify -Pintegration
docker stop tc4-ingestion-localstack
```

Os testes criam tabela/tópico/fila temporários e os removem ao terminar. Usam credenciais
fictícias e endpoint local; não usam sua conta AWS. O profile falha se o emulador estiver
indisponível. Para outra porta: `-Dlocalstack.endpoint=http://localhost:4567`.

Os testes integram o handler proxy com os serviços do emulador: verificam gravação real,
entrega SNS → SQS e contrato da notificação, persistência sem evento para nota normal e
ausência de gravação inválida. Não executam API Gateway, Lambda gerenciada nem SES.
O workflow `ingestion-tests.yml` executa essa suíte automaticamente em pushes e PRs.

## Homologação na AWS e evidências dos tickets

Esta etapa exige a stack implantada e identidades SES configuradas pelos responsáveis.
Não considerar a entrega de e-mail comprovada apenas pelo teste do emulador.

1. Compilar com `sam build`; usar o fluxo de deploy existente para `dev`.
2. Enviar via API uma nota 8: esperar 201 e conferir o item no DynamoDB (`NORMAL`).
3. Enviar nota 2: esperar 201, conferir item `ALTA`, log `evaluation_created` e o log
   `Notification sent` do consumidor com o mesmo feedbackId; confirmar o e-mail recebido.
4. Enviar nota -1, 11, texto, descrição ausente/vazia e JSON inválido: esperar 400.
5. Guardar saída da suíte, resposta da API, item do banco e evidência do e-mail para os tickets
   e para o vídeo. Não expor API Key, endereços pessoais ou texto sensível nos prints.

Exemplo PowerShell (URL e chave entram apenas no ambiente local):

```powershell
$body = @{ descricao = 'Aula com áudio inaudível'; nota = 2 } | ConvertTo-Json
Invoke-WebRequest -Method Post -Uri $env:TC4_API_URL `
  -Headers @{ 'x-api-key' = $env:TC4_API_KEY } `
  -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
```
