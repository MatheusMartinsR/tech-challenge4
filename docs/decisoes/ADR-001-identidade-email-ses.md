# ADR-001: Identidade de e-mail do SES sem domínio próprio

- **Status:** Aceita
- **Data:** 15/11/2026
- **Issue relacionada:** #3 — Configurar serviço de envio de e-mail e template de urgência

## Contexto

As funções de notificação de feedback crítico e de relatório semanal enviam e-mails pelo Amazon SES.
O SES exige uma identidade verificada para o remetente. As opções avaliadas foram:

| Opção | Custo | Prós | Contras |
|---|---|---|---|
| a) Registrar domínio no Route 53 | Anual, por TLD, mais hosted zone | DKIM/SPF/DMARC, melhor entrega | Gasto recorrente |
| b) Domínio já existente de um integrante | Zero | Sem custo | DNS fora da AWS, dependência pessoal |
| c) Identidade de e-mail verificada | Zero | Já funciona; atende ao critério "domínio/identidade verificada" | Remetente de webmail pode cair no spam |

## Decisão

O grupo adotou a **opção c**: o remetente é um endereço de e-mail verificado no SES, sem domínio próprio.

## Consequências

- Sem DKIM, MAIL FROM e DMARC próprios: e-mails podem cair na caixa de spam.
- A conta permanece no **sandbox** do SES: só envia para destinatários verificados, com limite de 200 e-mails por dia,
  suficiente para o volume do projeto e para a demonstração.
- Cada administrador que recebe alertas ou relatórios precisa verificar o próprio endereço no SES.
- O remetente é configurado pelo parâmetro `SenderEmail` da stack (secret `SENDER_EMAIL` no GitHub).
- **Evolução possível:** registrar um domínio, verificá-lo no SES com Easy DKIM, trocar o `SenderEmail` e pedir a
  saída do sandbox. Nenhuma mudança de código é necessária.
