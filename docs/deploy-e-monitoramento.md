# Deploy, configuração e monitoramento

> Tech Challenge Fase 4 — Grupo 35 · Como implantar a plataforma na AWS, testar a API e acompanhar o funcionamento.
> Arquitetura em [`arquitetura.md`](arquitetura.md); segurança em [`governanca-aws.md`](governanca-aws.md).

Todos os comandos foram pensados para o **AWS CloudShell** na região **us-east-1** (ícone `>_` na barra do console),
que já tem AWS CLI, AWS SAM CLI e git instalados. Valores entre `<...>` são marcadores: troque pelos seus.

> **Rascunho — validar no CloudShell.** Itens marcados com _(a confirmar)_ dependem de informações do grupo ou de
> recursos ainda em construção.

---

## Sumário

1. [Pré-requisitos da conta (uma vez)](#1-pré-requisitos-da-conta-uma-vez)
2. [Deploy automatizado (caminho oficial)](#2-deploy-automatizado-caminho-oficial)
3. [Deploy manual pelo CloudShell](#3-deploy-manual-pelo-cloudshell)
4. [Depois do deploy: obter a URL e a API Key e testar](#4-depois-do-deploy-obter-a-url-e-a-api-key-e-testar)
5. [Configurações de segurança aplicadas](#5-configurações-de-segurança-aplicadas)
6. [Monitoramento](#6-monitoramento)
7. [Relatório semanal](#7-relatório-semanal)
8. [Remoção do ambiente](#8-remoção-do-ambiente)

---

## 1. Pré-requisitos da conta (uma vez)

Itens criados fora do `template.yaml`, porque valem para a conta inteira ou envolvem pessoas.

| # | Item | Onde | Por quê |
|---|---|---|---|
| 1 | Usuários IAM no grupo `Admins`, com MFA | IAM | Acesso humano sem root e sem access keys ([governança, seção 4](governanca-aws.md#4-acesso-humano-usuários-grupo-e-mfa-)) |
| 2 | Provedor OIDC `token.actions.githubusercontent.com` | IAM → *Identity providers* | Permite que o GitHub Actions obtenha credenciais temporárias |
| 3 | Role de deploy assumida pelo GitHub via OIDC | IAM → *Roles* | Usada pelo pipeline; sem chaves guardadas no GitHub |
| 4 | Remetente verificado no SES | SES → *Identities* | Sem identidade verificada o SES recusa o envio ([ADR-001](decisoes/ADR-001-identidade-email-ses.md)) |
| 5 | Destinatários verificados no SES | SES → *Identities* | A conta está no **sandbox**: só envia para endereços verificados |
| 6 | Secrets do repositório | GitHub → *Settings → Secrets and variables → Actions* | Valores que o pipeline passa ao `sam deploy` |

### 1.1 Provedor OIDC e role de deploy

_(a confirmar com o grupo: nome da role e políticas anexadas.)_

Criar o provedor (uma vez por conta):

```bash
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com
```

A role de deploy confia nesse provedor e só pode ser assumida por workflows **deste repositório** na **branch `main`**.
Trust policy (troque `<ACCOUNT_ID>`, `<OWNER>` e `<REPO>`):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
        "token.actions.githubusercontent.com:sub": "repo:<OWNER>/<REPO>:ref:refs/heads/main"
      }
    }
  }]
}
```

Conferir o que já existe na conta:

```bash
aws iam list-open-id-connect-providers
```

```bash
aws iam get-role --role-name <NOME_DA_ROLE_DE_DEPLOY> --query "Role.AssumeRolePolicyDocument"
```

### 1.2 Identidades do SES

Cada endereço (remetente e cada destinatário) precisa ser verificado uma vez. A AWS envia um e-mail com um link de
confirmação:

```bash
aws sesv2 create-email-identity --email-identity <EMAIL>
```

Conferir o status (precisa estar `SUCCESS`):

```bash
aws sesv2 list-email-identities --query "EmailIdentities[].[IdentityName,VerificationStatus]" --output table
```

### 1.3 Secrets do GitHub

| Secret | Conteúdo | Parâmetro do template |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN` | ARN da role de deploy (1.1) | — |
| `SENDER_EMAIL` | Remetente verificado no SES | `SenderEmail` |
| `RECIPIENT_EMAILS` | Administradores, separados por vírgula e **sem espaço** | `RecipientEmails` |
| `ALERT_EMAIL` | Quem recebe os alertas operacionais (opcional) | `AlertEmail` |

> Os secrets têm que estar sempre atualizados: o pipeline passa esses valores a cada deploy e **sobrescreve** o que
> estiver na stack.

---

## 2. Deploy automatizado (caminho oficial)

O workflow [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) implanta a stack **`tc4-dev`**.

**Quando roda:**

- a cada push ou merge na branch `main`;
- manualmente, em **Actions → Deploy → Run workflow**.

> O botão **Run workflow** só aparece depois que o `deploy.yml` estiver na `main`, e a role de deploy só aceita
> execuções da `main`.

**O que o workflow faz:**

| Etapa | Ação |
|---|---|
| Checkout | Baixa o código |
| Setup Java 21 | Amazon Corretto 21, a mesma distribuição do runtime `java21` da Lambda, com cache do Maven |
| Setup SAM CLI | Instala o AWS SAM CLI |
| Credenciais via OIDC | Assume a role `AWS_DEPLOY_ROLE_ARN` e recebe credenciais temporárias (sem access keys) |
| Build | `sam build --parallel --cached` |
| Deploy | `sam deploy --no-confirm-changeset --no-fail-on-empty-changeset` com `Stage=dev` e os secrets como parâmetros |

**Como acompanhar:**

1. GitHub → **Actions** → execução mais recente → job **Build e deploy (dev)**.
2. AWS → **CloudFormation** → stack `tc4-dev` → aba **Events**: mostra cada recurso criado ou alterado.
3. Se algum recurso falhar, o CloudFormation faz **rollback automático** para a última versão estável e o job fica
   vermelho. O motivo aparece na aba **Events** (status `*_FAILED`).

---

## 3. Deploy manual pelo CloudShell

Para o avaliador implantar na própria conta ou para uma emergência.

> ⚠️ **A stack `tc4-dev` é compartilhada pelo grupo.** O `samconfig.toml` só define `Stage`; se `SenderEmail`,
> `RecipientEmails` e `AlertEmail` não forem informados, voltam ao valor padrão (`not-configured@example.com`) e a
> notificação para de funcionar. Sempre passe todos os parâmetros e leia o changeset antes de confirmar.

### 3.1 Preparar o CloudShell

Java 21 e Maven (o que fica fora de `$HOME` some quando a sessão do CloudShell expira; repita se precisar):

```bash
sudo dnf install -y java-21-amazon-corretto-devel
```

```bash
cd ~ && curl -sO https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz && tar xzf apache-maven-3.9.9-bin.tar.gz && echo 'export PATH=$HOME/apache-maven-3.9.9/bin:$PATH' >> ~/.bashrc && source ~/.bashrc
```

Conferir:

```bash
java -version && mvn -version && sam --version
```

Baixar o código:

```bash
git clone https://github.com/MatheusMartinsR/tech-challenge4.git && cd tech-challenge4
```

### 3.2 Build

```bash
sam build
```

Deve terminar com `Build Succeeded`.

### 3.3 Deploy em `dev`

Ver os parâmetros que a stack usa hoje (para não sobrescrever com valores errados):

```bash
aws cloudformation describe-stacks --stack-name tc4-dev --query "Stacks[0].Parameters" --output table
```

Implantar:

```bash
sam deploy --parameter-overrides "Stage=dev SenderEmail=<REMETENTE> RecipientEmails=<ADMIN1>,<ADMIN2> AlertEmail=<RESPONSAVEL>"
```

O SAM mostra o **changeset** (o que será criado, alterado ou removido) e pergunta `Deploy this changeset? [y/N]`.
Confira a lista e responda `y` para aplicar ou `N` para cancelar sem mudar nada.

### 3.4 Deploy em `prod`

```bash
sam deploy --config-env prod --parameter-overrides "Stage=prod SenderEmail=<REMETENTE> RecipientEmails=<ADMIN1>,<ADMIN2> AlertEmail=<RESPONSAVEL>"
```

Cria a stack independente `tc4-prod`, com recursos prefixados por `tc4-prod-` e proteção contra exclusão na tabela.

### 3.5 Parâmetros

| Parâmetro | Obrigatório | Padrão | Descrição |
|---|---|---|---|
| `Stage` | Sim | `dev` | `dev` ou `prod`; cada um vira uma stack separada |
| `SenderEmail` | Sim | `not-configured@example.com` | Remetente verificado no SES |
| `RecipientEmails` | Sim | `not-configured@example.com` | Administradores, separados por vírgula e sem espaço |
| `AlertEmail` | Não | vazio | Inscrito no tópico de alertas. A AWS envia um e-mail de confirmação: os alertas só chegam depois que o link é clicado |
| `LogRetentionDays` | Não | `14` | Retenção dos logs das funções: 7, 14, 30, 60 ou 90 dias |

---

## 4. Depois do deploy: obter a URL e a API Key e testar

URL da API e ID da API Key (saídas da stack):

```bash
aws cloudformation describe-stacks --stack-name tc4-dev --query "Stacks[0].Outputs" --output table
```

Valor da API Key (não publique esse valor):

```bash
aws apigateway get-api-key --api-key <ApiKeyId> --include-value --query value --output text
```

Guardar em variáveis para os testes:

```bash
API_URL="<ApiUrl>"; API_KEY="<valor da chave>"
```

### 4.1 Testes

Feedback válido:

```bash
curl -i -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" -d '{"descricao":"Aula muito boa","nota":9}'
```

Feedback crítico (nota baixa; deve gerar o e-mail aos administradores):

```bash
curl -i -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" -d '{"descricao":"Aula confusa","nota":2}'
```

Corpo inválido:

```bash
curl -i -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" -d '{"nota":11}'
```

Sem API Key:

```bash
curl -i -X POST "$API_URL" -H "Content-Type: application/json" -d '{"descricao":"Teste","nota":8}'
```

Limite de requisições (30 chamadas seguidas):

```bash
for i in $(seq 1 30); do curl -s -o /dev/null -w "%{http_code} " -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" -d '{"descricao":"Teste","nota":8}'; done; echo
```

| Resposta | Quando | Quem responde |
|---|---|---|
| `2xx` | Feedback aceito | Função de ingestão |
| `400` | Corpo inválido: campo ausente, tipo errado, `nota` fora de 0 a 10, `descricao` vazia ou com mais de 1000 caracteres | API Gateway (a função nem é chamada) |
| `403` | API Key ausente ou inválida | API Gateway |
| `429` | Mais de 5 req/s (rajada de 10) ou mais de 1000 req no dia | API Gateway (usage plan) |

> _(a confirmar)_ Na branch atual a função de ingestão ainda é um placeholder (responde `202` sem gravar nem publicar).
> Enquanto isso, o fluxo de notificação pode ser testado publicando direto no tópico SNS
> ([como testar a notificação](notificacao-feedback-critico.md#7-como-testar-manualmente)).

---

## 5. Configurações de segurança aplicadas

Resumo; detalhes e evidências em [`governanca-aws.md`](governanca-aws.md).

| Camada | Configuração |
|---|---|
| Pessoas | Grupo `Admins`, MFA obrigatório (`RequireMFA`), sem access keys, root sem uso diário |
| Pipeline | OIDC com credenciais temporárias; role restrita ao repositório e à `main` |
| Funções | Uma role IAM por função, só com as ações e recursos que usa |
| API | API Key, usage plan (5 req/s, rajada 10, 1000/dia), validação do corpo, TLS 1.2+ |
| Dados | DynamoDB, SNS de feedback crítico e SQS criptografados em repouso; HTTPS em trânsito; proteção contra exclusão em `prod` |
| Segredos | Nenhum e-mail, ID de conta ou chave no repositório; valores entram como parâmetros/secrets |
| Auditoria e custo | CloudTrail, logs JSON no CloudWatch, AWS Budgets com alertas (US$ 5/mês) |

---

## 6. Monitoramento

### 6.1 Dashboard

_(a completar com a issue de monitoramento)_ Link direto:
`https://us-east-1.console.aws.amazon.com/cloudwatch/home?region=us-east-1#dashboards/dashboard/<NOME_DO_DASHBOARD>`

| Gráfico | O que mostra |
|---|---|
| _(a completar)_ | |

### 6.2 Alarmes e quem recebe

| Alarme | Dispara quando | Ação |
|---|---|---|
| `tc4-<stage>-notification-dlq-not-empty` | Há ao menos uma mensagem na DLQ (notificação que falhou 3 vezes) | Publica em `tc4-<stage>-alerts` ao entrar em `ALARM` e ao voltar para `OK` |
| _(a completar com a issue de monitoramento)_ | | |

Quem está inscrito no tópico de alertas (status `PendingConfirmation` = o link de confirmação ainda não foi clicado):

```bash
aws sns list-subscriptions --query "Subscriptions[?contains(TopicArn,'tc4-dev-alerts')].[Endpoint,SubscriptionArn]" --output table
```

Estado atual dos alarmes:

```bash
aws cloudwatch describe-alarms --alarm-name-prefix tc4-dev --query "MetricAlarms[].[AlarmName,StateValue]" --output table
```

### 6.3 Logs

Cada função grava logs em **JSON** num log group próprio, com retenção definida por `LogRetentionDays`:

| Função | Log group |
|---|---|
| Ingestão | `/aws/lambda/tc4-<stage>-ingestion` |
| Notificação | `/aws/lambda/tc4-<stage>-notification` |
| Relatório | `/aws/lambda/tc4-<stage>-weekly-report` |

Acompanhar em tempo real pelo terminal:

```bash
aws logs tail /aws/lambda/tc4-dev-notification --since 10m --follow --format short
```

### 6.4 Consultas prontas no Logs Insights

Console → **CloudWatch → Logs Insights** → selecione os três log groups `/aws/lambda/tc4-dev-*` → cole a consulta.

Erros recentes:

```text
fields @timestamp, @log, level, message
| filter level = "ERROR"
| sort @timestamp desc
| limit 50
```

Notificações enviadas por hora:

```text
fields @timestamp
| filter message like /Notification sent/
| stats count() as enviados by bin(1h)
```

Duração e cold start das funções (com logs em JSON, o resumo de cada execução vem no registro `platform.report`):

```text
filter type = "platform.report"
| stats avg(record.metrics.durationMs) as media_ms, max(record.metrics.durationMs) as max_ms,
        count(record.metrics.initDurationMs) as cold_starts by @log
```

### 6.5 O alarme disparou, e agora?

1. Abrir os logs da notificação e filtrar por `ERROR` para ver o motivo (consulta 6.4).
2. Corrigir a causa (dado inválido, identidade do SES, permissão etc.).
3. Console → **SQS** → `tc4-<stage>-notification-dlq` → **Start DLQ redrive** para devolver as mensagens à fila
   principal. Se a mensagem não puder ser reprocessada, registrar o conteúdo e usar **Purge**.
4. Com a DLQ vazia, o alarme volta para `OK` em poucos minutos e um e-mail de retorno é enviado.

Passo a passo completo, com prints do teste em `dev`:
[notificação de feedback crítico, seção 4](notificacao-feedback-critico.md#4-caminho-de-falha-novas-tentativas-dlq-e-alarme).

---

## 7. Relatório semanal

O **EventBridge Scheduler** `tc4-<stage>-weekly-report` invoca a função toda **segunda-feira às 08:00 (horário de
Brasília)**, com `cron(0 8 ? * MON *)` no fuso `America/Sao_Paulo`.

Conferir o agendamento:

```bash
aws scheduler get-schedule --name tc4-dev-weekly-report --query "[State,ScheduleExpression,ScheduleExpressionTimezone]"
```

Disparar manualmente, sem esperar a segunda-feira:

```bash
aws lambda invoke --function-name tc4-dev-weekly-report:live --payload '{}' --cli-binary-format raw-in-base64-out /tmp/out.json && cat /tmp/out.json
```

Depois, conferir o log da execução:

```bash
aws logs tail /aws/lambda/tc4-dev-weekly-report --since 5m --format short
```

> _(a confirmar)_ Na branch atual a função de relatório ainda é um placeholder: registra a execução no log, mas não
> envia e-mail.

---

## 8. Remoção do ambiente

> ⚠️ Apaga **todos** os recursos da stack, inclusive a tabela e os feedbacks gravados. Não há como desfazer.

```bash
sam delete --stack-name tc4-dev
```

O SAM pede confirmação e também remove os artefatos que ele enviou ao bucket S3 de deploy.

Em `prod`, a tabela tem proteção contra exclusão. Desligue antes de remover a stack:

```bash
aws dynamodb update-table --table-name tc4-prod-feedbacks --no-deletion-protection-enabled
```

```bash
sam delete --stack-name tc4-prod
```

Itens criados fora do template (provedor OIDC, role de deploy, identidades do SES, usuários IAM, Budgets) continuam
na conta e precisam ser removidos à parte, se for o caso.
