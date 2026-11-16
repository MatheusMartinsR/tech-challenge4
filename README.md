# tech-challenge4

## Rodar localmente (Docker Desktop)

O [capítulo de testes locais](docs/testes-locais.md) reúne o passo a passo completo:
pré-requisitos, Postman, três cenários de avaliação, consulta ao banco, e-mails, scripts
automáticos, retry/DLQ, solução de problemas com WSL e diferenças em relação à AWS.

Na raiz do repositório:

```sh
docker compose -f compose.local.yaml up -d --build
```

API: **POST http://localhost:3000/avaliacao**, sem API Key no ambiente local.
Header `Content-Type: application/json`; corpo:

```json
{"descricao":"Aula com áudio inaudível","nota":2}
```

Retorna `201`, grava no DynamoDB local e publica notas de 0 a 3 no SNS → SQS local.
O consumidor local executa o `NotificationHandler` do Conrado e captura o e-mail no
**Mailpit: http://localhost:8025**. O compose usa endpoints locais e credenciais fictícias.
Não faz deploy e não envia mensagens para caixas de e-mail reais.
O servidor HTTP de desenvolvimento chama o mesmo handler da Lambda; não simula IAM,
API Gateway, limites de uso nem execução gerenciada da Lambda.

Fluxo local: API → DynamoDB + SNS → SQS → função de notificação → Mailpit.
Notas de 0 a 3 geram e-mail para `admin@tc4.test`; notas de 4 a 10 não geram notificação.
O Mailpit fornece uma caixa local consultável pelo navegador ([documentação](https://mailpit.axllent.org/docs/api-v1/)).
O transporte SES é substituído por um adaptador local para Mailpit, compilado apenas com
`-Plocal`; o handler de produção continua usando SES na AWS. Este teste não valida IAM/SES reais.

Teste automático da API, no PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\local\test-api.ps1
powershell -ExecutionPolicy Bypass -File .\local\test-notification.ps1
```

Para ver as avaliações em uma interface gráfica, abra **http://localhost:8001** e clique
na tabela **tc4-local-feedbacks**. O DynamoDB Admin aponta somente para o LocalStack,
usando credenciais fictícias. Você pode visualizar os campos `description`, `rating`,
`urgency` e `createdAt` dos registros ([ferramenta](https://github.com/aaronshaf/dynamodb-admin)).

Ver avaliações salvas e logs pelo terminal:

```sh
docker compose -f compose.local.yaml exec localstack awslocal dynamodb scan --table-name tc4-local-feedbacks
docker compose -f compose.local.yaml logs api
docker compose -f compose.local.yaml logs notification
```

O consumidor remove as mensagens processadas da fila; o evento normalmente já terá virado
um e-mail no Mailpit quando você consultar a fila. Para inspecionar um evento antes do consumo:

```sh
docker compose -f compose.local.yaml stop notification
# Envie uma avaliação crítica pela API antes do próximo comando.
docker compose -f compose.local.yaml exec localstack awslocal sqs receive-message --queue-url http://localhost:4566/000000000000/tc4-local-notification --wait-time-seconds 2
docker compose -f compose.local.yaml start notification
```

Receber manualmente uma mensagem a deixa temporariamente invisível. O consumidor local
confirma apenas sucessos; falhas voltam após 20 segundos e, depois de três recebimentos sem
sucesso, vão para `tc4-local-notification-dlq`. O intervalo é reduzido para facilitar os testes;
na AWS permanece a configuração de 120 segundos. Não há alarme CloudWatch local.
Para simular falha, pare `mailpit`, envie uma nota baixa e acompanhe os logs e a DLQ.
Depois inicie `mailpit` novamente. Eventos que já estiverem na DLQ exigem reprocessamento.

Parar mantendo os containers: `docker compose -f compose.local.yaml stop`.
Remover o ambiente e seus dados de teste: `docker compose -f compose.local.yaml down`.
Não há volume de persistência; não conte com retenção após reiniciar o LocalStack.
Na primeira execução o Docker baixa as imagens e dependências; depois usa cache.
Em Linux/macOS, execute `chmod +x local/init-aws.sh` antes de subir os serviços.

Documentação da [ingestão de feedback (Pedro)](docs/ingestao-feedback.md): contrato da API,
persistência, criticidade, erros e execução dos testes unitários e de integração.
