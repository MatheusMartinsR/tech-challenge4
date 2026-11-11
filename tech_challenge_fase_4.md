# PÓS TECH - Tech Challenge (Fase 4)

Boas-vindas ao Tech Challenge da fase 4! Este desafio é fundamental para consolidar os conhecimentos obtidos ao longo da fase. Nosso desafio focará em **Cloud Computing**, **Serverless** e **Deploy de Aplicações em ambiente de nuvem**. 

O projeto proposto envolve a criação de uma **plataforma de feedback**, onde os estudantes podem avaliar as aulas e os administradores podem ter acesso a relatórios e análises desses feedbacks. Esta atividade será desenvolvida em grupo, com prazo de entrega determinado e com impacto direto na nota final.

---

## 1. Problema

Para garantir a qualidade dos cursos on-line, é essencial que os estudantes possam fornecer feedbacks e que os administradores possam acompanhar rapidamente a satisfação dos alunos. O sistema deverá ser capaz de:
* Receber feedbacks;
* Enviar notificações para itens críticos;
* Gerar relatórios periódicos para auxiliar na análise dos dados.

---

## 2. Objetivo

O objetivo é desenvolver uma aplicação hospedada em um ambiente de nuvem, com funções *serverless* para automatizar o recebimento de feedbacks, o envio de notificações e a geração de relatórios. 

Como estamos em um ambiente com créditos de *cloud computing* limitados, vocês deverão gravar um vídeo demonstrando o sistema em funcionamento.

---

## 3. Requisitos

* **Ambiente de Nuvem:** Configurado e funcionando, com configurações de segurança relacionadas aos dados de clientes e com governança de acesso.
* **Componentes de Suporte:** Configuração adequada dos componentes (bancos de dados, etc.).
* **Deploy Automatizado:** Para componentes atualizáveis (ex.: funções serverless).
* **Monitoramento:** Aplicação devidamente monitorada.
* **Notificações Automáticas:** Envio automático de alertas aos administradores para problemas críticos.
* **Relatório Semanal:** Relatório semanal dos feedbacks, incluindo média de avaliações.

---

## 4. Regras para a Aplicação

* Deve, obrigatoriamente, implementar **serverless**.
* Deve, obrigatoriamente, rodar em **ambiente cloud**.
* O mínimo de funções serverless a implementar é de **duas (2)**, respeitando a regra de **Responsabilidade Única** para cada um dos componentes. 
  * *Obs: A correta separação dos serviços e responsabilidades é parte da avaliação.*

---

## 5. Artefatos de Entrega

1. **Repositório Aberto:** Contendo todo o código-fonte do projeto.
2. **Vídeo de Demonstração:** Vídeo demonstrando a aplicação em funcionamento, as funções serverless ativas e as configurações do projeto.

---

## 6. Critérios de Avaliação

A avaliação será baseada nos seguintes pontos:
* Explicação do modelo de cloud escolhido e dos componentes envolvidos na solução.
* Funcionamento correto da aplicação.
* Qualidade do código, acompanhado de documentação.
* Descrição do projeto contendo:
  * Arquitetura da solução;
  * Instruções de deploy;
  * Configuração do monitoramento;
  * Documentação das funções criadas.
* Configuração do ambiente de nuvem e funções serverless, com explicações sobre o modelo escolhido e configurações de segurança.

---

## 7. Referências Técnicas

### Endpoints de Entrada

* `POST /avaliacao`
  ```json
  {
    "descricao": "string",
    "nota": "int (0 a 10)"
  }
  ```

### Dados para o E-mail de Aviso de Urgência

* Descrição
* Urgência
* Data de envio

### Dados para o Relatório Semanal

* Descrição
* Urgência
* Data de envio
* Quantidade de avaliações por dia
* Quantidade de avaliações por urgência

---

> *Lembramos que as datas de todas as lives, grupos de estudos e entrega do Tech Challenge estão disponíveis na plataforma. Bom trabalho!*