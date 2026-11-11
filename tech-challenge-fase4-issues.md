# TECH CHALLENGE — FASE 4

## Plataforma de Feedback de Aulas — Cloud Computing & Serverless

<!-- Página 1 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 1
 TECH CHALLENGE — FASE 4
 Plataforma de Feedback de Aulas — Cloud Computing & Serverless
 Plano de Issues por Integrante
 Pós-Graduação em Arquitetura de Software — FIAP Postech
 Grupo 35
 Data de referência: 14/10/2026
 Integrantes
 Matheus
 Alexandre
 Lucas
 Conrado
 Pedro

---

<!-- Página 2 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 2
1. Contexto do Desafio
A Fase 4 do Tech Challenge propõe o desenvolvimento de uma plataforma de feedback de aulas, na
qual estudantes avaliam as aulas e administradores acompanham a satisfação por meio de relatórios e
alertas. O projeto deve ser hospedado em ambiente de nuvem, utilizando funções serverless para
automatizar o recebimento de feedbacks, o envio de notificações para itens críticos e a geração de
relatórios periódicos.
1.1 Requisitos obrigatórios

Ambiente de nuvem configurado, com segurança de dados e governança de acesso

Componentes de suporte configurados (ex.: banco de dados)

Deploy automatizado dos componentes atualizáveis (ex.: funções serverless)

Aplicação monitorada

Notificações automáticas aos administradores para feedbacks críticos

Relatório semanal dos feedbacks, com média de avaliações

No mínimo duas funções serverless, respeitando o Princípio da Responsabilidade Única
1.2 Contrato de entrada (referência)
POST /avaliação — payload: { "descricao": string, "nota": int (0 a 10) }
1.3 Entregáveis finais do grupo

Repositório aberto com o código-fonte do projeto

Vídeo de demonstração da aplicação em funcionamento, funções serverless ativas e configurações do projeto
2. Divisão de Responsabilidades
A divisão abaixo segue o princípio da Responsabilidade Única por função serverless e busca aproveitar a
especialização de cada integrante ao longo do curso, cobrindo as cinco frentes necessárias:
infraestrutura/segurança, ingestão de dados, notificações, monitoramento/relatórios e
documentação/entrega.
Integrante
Frente principal
Matheus
Infraestrutura de nuvem, segurança e pipeline de deploy
Pedro
Função serverless de ingestão de feedback (POST /avaliação)
Conrado
Função serverless de notificações críticas
Lucas
Monitoramento, relatório semanal e testes automatizados
Alexandre
Documentação da arquitetura, instruções de deploy e configuração do e-mail de
notificação
Todos
Vídeo de demonstração final (tarefa geral da equipe)

---

<!-- Página 3 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 3
Matheus
Infraestrutura de Nuvem & Segurança
Issue #1 — Provisionar ambiente de nuvem com segurança e
governança de acesso
infra
Configurar a conta/projeto cloud (ex.: AWS) que hospedará toda a solução, definindo papéis de
acesso (IAM), políticas de menor privilégio e segregação de ambientes.
Tarefas:

Criar conta/organização cloud e usuários com MFA habilitado

Definir roles e políticas IAM específicas para cada função serverless

Configurar API Gateway como porta de entrada única, com autenticação e rate limiting

Documentar o modelo de responsabilidade e governança de acesso adotado
Critério de aceite:
Ambiente provisionado, políticas de acesso mínimas aplicadas e nenhuma credencial sensível exposta no
repositório.
Issue #2 — Configurar banco de dados gerenciado com rede e
criptografia seguras
infra
Provisionar o banco de dados (ex.: DynamoDB ou RDS) que armazenará os feedbacks, isolado em
rede privada e com dados sensíveis protegidos.
Tarefas:

Escolher e provisionar o banco de dados gerenciado

Configurar VPC/rede privada e security groups restritivos

Habilitar criptografia em repouso e em trânsito

Validar acesso somente pelas funções serverless autorizadas
Critério de aceite:
Banco acessível apenas pelas funções autorizadas, com criptografia habilitada e sem exposição pública.
Issue #3 — Implementar pipeline de deploy automatizado das
funções serverless
ci/cd
Configurar um pipeline de CI/CD que automatize o deploy das funções serverless e da infraestrutura
a cada atualização de código.
Tarefas:

Configurar GitHub Actions (ou similar) para build e deploy automático

Utilizar IaC (Serverless Framework, SAM ou Terraform) para versionar a infraestrutura

Garantir rollback simples em caso de falha no deploy

Documentar o passo a passo de deploy no README do projeto
Critério de aceite:
Um push na branch principal dispara o deploy automatizado das funções sem intervenção manual, com
instruções reproduzíveis documentadas.

---

<!-- Página 4 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 4
Pedro
Função de Ingestão de Feedback
Issue #1 — Implementar função serverless de recebimento de
feedback (POST /avaliação)
backend
Desenvolver a função serverless responsável por receber as avaliações enviadas pelos estudantes e
persisti-las no banco de dados.
Tarefas:

Criar a função com responsabilidade única de ingestão

Implementar o endpoint POST /avaliação conforme contrato (descricao, nota)

Persistir cada avaliação com data/hora de envio e nível de urgência calculado

Retornar respostas HTTP adequadas (sucesso e erro)
Critério de aceite:
O endpoint recebe um feedback válido, salva no banco e retorna 201; entradas inválidas retornam erro
apropriado.
Issue #2 — Implementar validação de entrada e tratamento de
erros
backend
Garantir que a função de ingestão valide os dados recebidos (nota entre 0 e 10, descrição
obrigatória) e trate falhas de forma controlada.
Tarefas:

Validar tipo e intervalo do campo 'nota' (0 a 10)

Validar presença e tamanho do campo 'descricao'

Padronizar mensagens de erro e códigos HTTP

Registrar logs estruturados para falhas de validação
Critério de aceite:
Requisições malformadas são rejeitadas com mensagem clara, sem gerar exceções não tratadas na
função.
Issue #3 — Disparar evento para a função de notificações e
cobrir com testes de integração
integração
Conectar a função de ingestão à função de notificações por meio de um evento/fila quando um
feedback crítico é recebido, e validar o fluxo ponta a ponta.
Tarefas:

Publicar evento (ex.: SNS/SQS/EventBridge) ao identificar feedback crítico

Definir o critério de criticidade (ex.: nota abaixo de um limite)

Escrever testes de integração do endpoint com o banco de dados

Validar o fluxo completo em ambiente de homologação
Critério de aceite:
Um feedback crítico enviado via API dispara corretamente o evento consumido pela função de
notificações, validado por teste automatizado.

---

<!-- Página 5 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 5

---

<!-- Página 6 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 6
Conrado
Notificações Críticas
Issue #1 — Implementar função serverless de notificação de
feedbacks críticos
backend
Desenvolver a função serverless, independente da de ingestão, responsável por notificar
administradores quando um feedback crítico é registrado.
Tarefas:

Criar função com responsabilidade única de notificação

Consumir o evento publicado pela função de ingestão

Montar o conteúdo da notificação com descrição, urgência e data de envio

Registrar o envio (sucesso/falha) para fins de auditoria
Critério de aceite:
Ao receber um evento de feedback crítico, a função dispara a notificação correspondente e registra o
resultado do envio.
Issue #2 — Definir e documentar o fluxo evento → notificação
com tratamento de falhas
resiliência
Garantir que falhas no envio da notificação não sejam perdidas silenciosamente, com nova tentativa
e registro de erro.
Tarefas:

Configurar política de retry para falhas de envio

Configurar fila de mensagens não processadas (dead-letter queue)

Documentar o fluxo completo de notificação no repositório

Adicionar alerta para falhas recorrentes de envio
Critério de aceite:
Uma falha simulada no envio é reprocessada automaticamente e, após esgotar as tentativas, a
mensagem é encaminhada para a fila de falhas.

---

<!-- Página 7 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 7
Lucas
Monitoramento, Relatórios & Testes
Issue #1 — Implementar função serverless de geração do
relatório semanal
backend
Criar a função serverless, agendada automaticamente, que consolida os feedbacks da semana em
um relatório com médias e contagens.
Tarefas:

Criar função agendada (ex.: EventBridge/CloudWatch Events) com execução semanal

Calcular média de avaliações, quantidade por dia e quantidade por urgência

Persistir ou enviar o relatório gerado aos administradores

Cobrir a função com testes unitários dos cálculos
Critério de aceite:
O relatório semanal é gerado automaticamente na periodicidade definida, contendo todos os indicadores
exigidos (médias e contagens).
Issue #2 — Configurar monitoramento e alertas da aplicação
observabilidade
Configurar monitoramento de todos os componentes (funções, banco de dados, API Gateway) com
métricas, logs centralizados e alertas.
Tarefas:

Habilitar logs estruturados em todas as funções serverless

Configurar dashboard de métricas (ex.: CloudWatch Dashboards)

Criar alertas para erros, latência alta e falhas de execução

Documentar como acessar e interpretar o monitoramento
Critério de aceite:
Dashboard mostra métricas em tempo real das funções e dispara alerta quando uma função falha ou
excede o tempo esperado.
Issue #3 — Criar suíte de testes automatizados e coleção de
validação da API
qa
Garantir qualidade e regressão do sistema por meio de testes automatizados e de uma coleção de
requisições para validação manual.
Tarefas:

Escrever testes unitários das funções serverless principais

Escrever testes de ponta a ponta do fluxo feedback → notificação → relatório

Criar coleção Postman/Insomnia cobrindo os cenários de sucesso e erro

Integrar execução dos testes ao pipeline de CI
Critério de aceite:
Pipeline de CI executa a suíte de testes automaticamente e a coleção de requisições cobre todos os
endpoints do sistema.

---

<!-- Página 8 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 8
Alexandre
Documentação & Entrega
Issue #1 — Documentar a arquitetura da solução e o modelo de
nuvem escolhido
documentação
Produzir a documentação de arquitetura exigida pela avaliação, explicando o modelo de nuvem
escolhido e os componentes da solução.
Tarefas:

Elaborar diagrama de arquitetura (componentes, funções serverless e banco de dados)

Justificar a escolha do modelo de nuvem (ex.: AWS Serverless)

Descrever a responsabilidade de cada função serverless implementada

Consolidar a documentação em um README ou documento de arquitetura
Critério de aceite:
Documento de arquitetura publicado no repositório, cobrindo diagrama, modelo de nuvem e
responsabilidade de cada componente.
Issue #2 — Escrever instruções de deploy, configuração e
monitoramento
documentação
Reunir, em conjunto com Matheus e Lucas, as instruções necessárias para reproduzir o deploy, a
configuração do ambiente e o monitoramento.
Tarefas:

Documentar pré-requisitos e passo a passo de deploy

Documentar configurações de segurança e governança de acesso aplicadas

Documentar como acessar dashboards e alertas de monitoramento

Revisar a documentação das funções serverless com os responsáveis
Critério de aceite:
Qualquer avaliador consegue seguir o documento e reproduzir o deploy e a verificação do monitoramento
sem apoio adicional da equipe.
Issue #3 — Configurar serviço de envio de e-mail e template de
urgência
infra
Configurar o serviço de e-mail transacional (ex.: Amazon SES) e criar o template usado para avisar
administradores sobre itens críticos.
Tarefas:

Configurar domínio/identidade verificada no serviço de e-mail

Criar template com os campos: descrição, urgência e data de envio

Parametrizar destinatários administradores

Testar envio em ambiente de homologação
Critério de aceite:
E-mail de aviso é entregue ao administrador com todos os campos exigidos corretamente preenchidos.

---

<!-- Página 9 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 9
Tarefa Geral
Toda a equipe
Gravar e editar o vídeo de demonstração da aplicação
geral
Produzir, em conjunto, o vídeo final exigido como artefato de entrega, demonstrando a aplicação, as
funções serverless ativas e as configurações do projeto. Tarefa geral: todos os integrantes
participam do roteiro e da gravação de sua respectiva frente.
Tarefas:

Roteirizar a demonstração cobrindo todos os critérios de avaliação

Gravar o fluxo completo: envio de feedback, notificação crítica e relatório semanal

Demonstrar as funções serverless ativas e o painel de monitoramento

Editar, revisar e publicar o vídeo junto ao repositório
Critério de aceite:
Vídeo publicado demonstra claramente o funcionamento ponta a ponta e as configurações exigidas pelos
critérios de avaliação, com a participação de todos os integrantes.
Esta tarefa não pertence a um único integrante: Matheus, Alexandre, Lucas, Conrado e Pedro participam juntos do roteiro e
da gravação, cada um demonstrando a frente que implementou.

---

<!-- Página 10 -->

Tech Challenge Fase 4 — Plano de Issues por Integrante
Página 10
3. Resumo Consolidado
Integrante
Nº
Issue
Matheus
#1
Provisionar ambiente de nuvem com segurança e governança de acesso
Matheus
#2
Configurar banco de dados gerenciado com rede e criptografia seguras
Matheus
#3
Implementar pipeline de deploy automatizado das funções serverless
Pedro
#1
Implementar função serverless de recebimento de feedback (POST /avaliação)
Pedro
#2
Implementar validação de entrada e tratamento de erros
Pedro
#3
Disparar evento para a função de notificações e cobrir com testes de integração
Conrado
#1
Implementar função serverless de notificação de feedbacks críticos
Conrado
#2
Definir e documentar o fluxo evento → notificação com tratamento de falhas
Lucas
#1
Implementar função serverless de geração do relatório semanal
Lucas
#2
Configurar monitoramento e alertas da aplicação
Lucas
#3
Criar suíte de testes automatizados e coleção de validação da API
Alexandre
#1
Documentar a arquitetura da solução e o modelo de nuvem escolhido
Alexandre
#2
Escrever instruções de deploy, configuração e monitoramento
Alexandre
#3
Configurar serviço de envio de e-mail e template de urgência
Todos
Geral
Gravar e editar o vídeo de demonstração da aplicação
Fonte: enunciado do Tech Challenge — Fase 4 (ADJT_Fase_4_Tech_Challenge), FIAP Postech, Grupo 35. Divisão de
issues elaborada com base nos requisitos obrigatórios, regras de implementação e critérios de avaliação descritos no
documento oficial.
