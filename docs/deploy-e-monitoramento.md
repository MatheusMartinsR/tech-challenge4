# Deploy, configuração e monitoramento

> Tech Challenge Fase 4 — Grupo 35 · Como implantar a plataforma na AWS, testar a API e acompanhar o funcionamento.
> Arquitetura em [`arquitetura.md`](arquitetura.md); segurança em [`governanca-aws.md`](governanca-aws.md).

Todos os comandos foram pensados para o **AWS CloudShell** na região **us-east-1** (ícone `>_` na barra do console),
que já tem AWS CLI, AWS SAM CLI e git instalados. Valores entre `<...>` são marcadores: troque pelos seus.

> **Validado no CloudShell em 15/11/2026** (seções 1, 3, 4, 6 e 7, na stack `tc4-dev`). A seção 2 será validada no
> primeiro deploy pela `main`. Itens marcados com _(a confirmar)_ dependem de recursos ainda em construção.

Antes de começar, desligue o paginador da AWS CLI para as saídas longas não ficarem cortadas:

```bash
export AWS_PAGER=""
```

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

Na conta do projeto, os dois já existem: o provedor `token.actions.githubusercontent.com` e a role
**`tc4-gha-deploy`**. Os comandos de criação servem para montar o ambiente em outra conta.

Criar o provedor (uma vez por conta):

```bash
aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com --client-id-list sts.amazonaws.com
```

> `EntityAlreadyExists` nesse comando significa que o provedor já foi criado: nada a corrigir.

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

Permissões da role: uma política inline, `tc4-gha-deploy-policy`, de menor privilégio. Ela libera só o que o
`sam deploy` usa: a stack e os change sets no CloudFormation, o bucket de artefatos do SAM, as funções Lambda e seus
mapeamentos de eventos, as roles das funções (com `iam:PassRole` restrito à Lambda e ao Scheduler), a tabela do
DynamoDB, os tópicos SNS, as filas SQS, os alarmes, os log groups, o API Gateway e o agendamento semanal.

> Recursos novos no `template.yaml` exigem ampliar essa política antes do deploy. Exemplo: um `AWS::SES::Template`
> precisa de `ses:CreateTemplate`, `ses:UpdateTemplate`, `ses:DeleteTemplate` e `ses:GetTemplate`.

Conferir o que já existe na conta:

```bash
aws iam list-open-id-connect-providers
```

```bash
aws iam get-open-id-connect-provider --open-id-connect-provider-arn $(aws iam list-open-id-connect-providers --query "OpenIDConnectProviderList[?contains(Arn,'token.actions')].Arn" --output text) --query "ClientIDList"
```

Deve mostrar `sts.amazonaws.com`.

```bash
aws iam get-role --role-name tc4-gha-deploy --query "Role.AssumeRolePolicyDocument.Statement[0].Condition"
```

```bash
aws iam get-role-policy --role-name tc4-gha-deploy --policy-name tc4-gha-deploy-policy --query "PolicyDocument.Statement[].Sid"
```

Para saber se o pipeline já assumiu a role alguma vez (vazio = nunca rodou):

```bash
aws cloudtrail lookup-events --lookup-attributes AttributeKey=EventName,AttributeValue=AssumeRoleWithWebIdentity --max-results 5 --query "Events[].EventTime"
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

`PENDING` = o link ainda não foi clicado. `FAILED` = o link expirou ou o endereço foi digitado errado: apague com
`aws sesv2 delete-email-identity --email-identity <EMAIL>` e cadastre de novo.

### 1.3 Secrets do GitHub

| Secret | Conteúdo | Parâmetro do template |
|---|---|---|
| `AWS_DEPLOY_ROLE_ARN` | ARN da role de deploy (1.1) | — |
| `SENDER_EMAIL` | Remetente verificado no SES | `SenderEmail` |
| `RECIPIENT_EMAILS` | Administradores, separados por vírgula e **sem espaço** | `RecipientEmails` |
| `ALERT_EMAIL` | Quem recebe os alertas operacionais (opcional) | `AlertEmail` |

> ⚠️ **`SENDER_EMAIL`, `RECIPIENT_EMAILS` e `ALERT_EMAIL` são obrigatórios antes do primeiro deploy pela `main`.**
> O pipeline passa esses valores a cada deploy e **sobrescreve** o que estiver na stack: um secret ausente vira
> parâmetro vazio e a notificação para de funcionar. Para copiar os valores atuais:
> `aws cloudformation describe-stacks --stack-name tc4-dev --query "Stacks[0].Parameters" --output table`.

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

> _(a confirmar)_ Em 15/11/2026 o pipeline ainda não tinha rodado (nenhum `AssumeRoleWithWebIdentity` no
> CloudTrail). Esta seção será conferida no primeiro deploy pela `main`.

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

> _(a confirmar)_ Enquanto o código não estiver na `main`, troque para a branch de integração:
> `git checkout ci/deploy-workflow`. Se a pasta já existir de uma sessão anterior, atualize em vez de clonar:
> `cd ~/tech-challenge4 && git fetch && git pull`.

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

**Primeiro, conferir sem aplicar.** O comando abaixo lê os e-mails da própria stack (nada para digitar) e só cria o
changeset, sem executá-lo:

```bash
q(){ aws cloudformation describe-stacks --stack-name tc4-dev --query "Stacks[0].Parameters[?ParameterKey=='$1'].ParameterValue" --output text; }; S=$(q SenderEmail); R=$(q RecipientEmails); A=$(q AlertEmail); sam deploy --no-execute-changeset --parameter-overrides "Stage=dev SenderEmail=$S RecipientEmails=$R AlertEmail=$A"
```

Como ler o changeset:

| Linha | Significado |
|---|---|
| `Add` / `Delete` de `AWS::Lambda::Version` | Normal: cada deploy publica versões novas para o alias `live`. O SAM retém as antigas |
| `Modify` nas funções e nos aliases `live` | Normal: o pacote compilado mudou (o Maven grava datas no `.jar`) |
| `Modify` em `AlertsEmailSubscription` ou nas roles | ⚠️ Algum e-mail diferente do atual. Confira os parâmetros antes de seguir |
| `Remove`, ou `Replacement: True` na tabela | ⚠️ Pare: a branch pode estar desatualizada |

> ⚠️ Nunca rode o comando com os marcadores literais (`<REMETENTE>`...). Validado em 15/11/2026: com eles, o
> changeset troca a inscrição de alertas e as permissões do SES das funções, e a notificação para.

Apagar o changeset conferido (não mexe em nenhum recurso):

```bash
aws cloudformation list-change-sets --stack-name tc4-dev --query "Summaries[].ChangeSetName"
```

```bash
aws cloudformation delete-change-set --stack-name tc4-dev --change-set-name <NOME>
```

**Implantar de verdade** (o mesmo comando, sem `--no-execute-changeset`):

```bash
q(){ aws cloudformation describe-stacks --stack-name tc4-dev --query "Stacks[0].Parameters[?ParameterKey=='$1'].ParameterValue" --output text; }; S=$(q SenderEmail); R=$(q RecipientEmails); A=$(q AlertEmail); sam deploy --parameter-overrides "Stage=dev SenderEmail=$S RecipientEmails=$R AlertEmail=$A"
```

O SAM mostra o changeset e pergunta `Deploy this changeset? [y/N]`. Responda `y` para aplicar ou `N` para cancelar.
Para uma stack nova (outra conta), troque `$S`, `$R` e `$A` pelos endereços verificados no SES.

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

Guardar a URL e a chave em variáveis para os testes, sem exibir a chave (não publique esse valor):

```bash
q(){ aws cloudformation describe-stacks --stack-name tc4-dev --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text; }; API_URL=$(q ApiUrl); KEYID=$(q ApiKeyId); API_KEY=$(aws apigateway get-api-key --api-key "$KEYID" --include-value --query value --output text); echo "URL: $API_URL"; echo "Chave carregada: ${#API_KEY} caracteres"
```

As variáveis só existem na aba atual do CloudShell; repita o comando se a sessão expirar.

### 4.1 Testes

Feedback válido:

```bash
curl -i -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" -d '{"descricao":"Aula muito boa","nota":9}'
```

Feedback crítico (nota baixa; deve gerar o e-mail aos administradores):

```bash
curl -i -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" -d '{"descricao":"Aula confusa","nota":2}'
```

Corpo inválido (nota fora da faixa; também vale testar sem `descricao` ou com `"nota":"cinco"`):

```bash
curl -i -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" -d '{"descricao":"Teste","nota":11}'
```

Sem API Key:

```bash
curl -i -X POST "$API_URL" -H "Content-Type: application/json" -d '{"descricao":"Teste","nota":8}'
```

Limite de requisições (30 chamadas seguidas):

```bash
for i in $(seq 1 30); do curl -s -o /dev/null -w "%{http_code} " -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" -d '{"descricao":"Teste","nota":8}'; done; echo
```

| Resposta | Corpo | Quando | Quem responde |
|---|---|---|---|
| `2xx` | definido pela função | Feedback aceito | Função de ingestão |
| `400` | `{"message": "Invalid request body"}` | Corpo inválido: campo ausente, tipo errado, `nota` fora de 0 a 10, `descricao` vazia ou com mais de 1000 caracteres | API Gateway (a função nem é chamada) |
| `403` | `{"message":"Forbidden"}` | API Key ausente ou inválida | API Gateway |
| `429` | `{"message":"Too Many Requests"}` | Mais de 5 req/s (rajada de 10) ou mais de 1000 req no dia | API Gateway (usage plan) |

> O limite de requisições é aplicado pela AWS em regime de melhor esforço: o `429` aparece em parte das execuções do
> laço, não em todas. Em 15/11/2026 o laço sequencial teve um `429` em 30 chamadas.

> _(a confirmar)_ Na branch atual a função de ingestão ainda é um placeholder: responde `202` com
> `{"status":"placeholder"}`, sem gravar nem publicar.
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

> Se o console abrir a nova experiência **Log Analytics** (caixa com linhas `SOURCE "/aws/lambda/..."`), clique em
> **Opt out (Logs Insights)** na faixa azul do topo, ou mantenha as linhas `SOURCE` e cole a consulta logo abaixo,
> começando cada linha com `|`.

Erros recentes:

```text
fields @timestamp, @log, level, message
| filter level = "ERROR"
| sort @timestamp desc
| limit 50
```

> Só pega os registros gravados com nível. Mensagens gravadas sem nível aparecem como `"level":"UNDEFINED"`; para ver
> quais níveis existem: `filter ispresent(level) | stats count() by level, @log`.

Notificações enviadas por hora:

```text
fields @timestamp
| filter message like /Notification sent/
| stats count() as enviados by bin(1h)
```

Duração e cold start das funções (com logs em JSON, o resumo de cada execução vem no registro `platform.report`).
Como as funções usam **SnapStart**, o cold start aparece como `restoreDurationMs` (restauração do snapshot), não como
`initDurationMs`:

```text
filter type = "platform.report"
| stats avg(record.metrics.durationMs) as media_ms, max(record.metrics.durationMs) as max_ms,
        count(record.metrics.restoreDurationMs) as snapstart_restores by @log
```

> Linhas `SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder"` na inicialização são avisos de uma
> dependência sem implementação de log: inofensivos, mas ficam fora das consultas em JSON.

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

Disparar manualmente, sem esperar a segunda-feira (envia o relatório de verdade para os administradores):

```bash
aws lambda invoke --function-name tc4-dev-weekly-report:live --payload '{}' --cli-binary-format raw-in-base64-out /tmp/out.json && cat /tmp/out.json
```

Sucesso: `"StatusCode": 200` **sem** a linha `"FunctionError"`. O `null` no final é o conteúdo de `/tmp/out.json`
(a função não devolve nada) e é esperado.

Depois, conferir o log da execução:

```bash
aws logs tail /aws/lambda/tc4-dev-weekly-report --since 5m --format short
```

| Mensagem no log | Causa | Correção |
|---|---|---|
| `Weekly report generated with N feedback(s)` | Sucesso | — |
| `not authorized to perform 'ses:SendEmail' on resource '...:identity/<destinatário>'` | No **sandbox**, o SES confere a permissão IAM do remetente **e de cada destinatário**, e a role da função só libera a identidade do remetente | Na `WeeklyReportRole`: `Resource` `identity/*` com a condição `ses:FromAddress` igual ao `SenderEmail` (mesmo padrão da role da notificação) |
| `REPORT_RECIPIENTS is not configured` | Variável de ambiente ausente | Conferir o parâmetro `RecipientEmails` da stack |

> _(a confirmar)_ Em 15/11/2026 o `tc4-dev` executava a versão completa do relatório e o envio falhava com o
> `AccessDenied` acima; a correção da role está pendente. Na `ci/deploy-workflow` a função ainda é um placeholder
> (só registra a execução no log).

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
