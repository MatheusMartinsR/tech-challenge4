# tech-challenge4

## Rodar localmente (Docker Desktop)

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
O compose usa endpoints do LocalStack e credenciais fictícias. Não faz deploy nem envia e-mail.
O servidor HTTP de desenvolvimento chama o mesmo handler da Lambda; não simula IAM,
API Gateway, limites de uso nem execução gerenciada da Lambda.

Teste automático da API, no PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\local\test-api.ps1
```

Ver avaliações salvas e logs:

```sh
docker compose -f compose.local.yaml exec localstack awslocal dynamodb scan --table-name tc4-local-feedbacks
docker compose -f compose.local.yaml logs api
```

Ver eventos críticos (receber uma mensagem a deixa temporariamente invisível):

```sh
docker compose -f compose.local.yaml exec localstack awslocal sqs receive-message --queue-url http://localhost:4566/000000000000/tc4-local-notification --wait-time-seconds 2
```

Parar mantendo os containers: `docker compose -f compose.local.yaml stop`.
Remover o ambiente e seus dados de teste: `docker compose -f compose.local.yaml down`.
Não há volume de persistência; não conte com retenção após reiniciar o LocalStack.
Na primeira execução o Docker baixa as imagens e dependências; depois usa cache.
Em Linux/macOS, execute `chmod +x local/init-aws.sh` antes de subir os serviços.

Documentação da [ingestão de feedback (Pedro)](docs/ingestao-feedback.md): contrato da API,
persistência, criticidade, erros e execução dos testes unitários e de integração.
