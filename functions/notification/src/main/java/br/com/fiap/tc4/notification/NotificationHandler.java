package br.com.fiap.tc4.notification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/**
 * Consome a fila de feedbacks críticos e envia um e-mail aos administradores via SES.
 *
 * Cada mensagem é tratada de forma independente: as que falham são devolvidas em
 * batchItemFailures, voltam para a fila e, após maxReceiveCount tentativas, vão para a DLQ.
 * As que deram certo são removidas da fila pela própria Lambda.
 */
public class NotificationHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SesV2Client ses;
    private final String senderEmail;
    private final List<String> recipients;

    /** Usado pela Lambda: lê a configuração das variáveis de ambiente do template. */
    public NotificationHandler() {
        this(SesV2Client.builder().httpClient(UrlConnectionHttpClient.create()).build(),
                requiredEnv("SENDER_EMAIL"),
                parseRecipients(requiredEnv("RECIPIENT_EMAILS")));
    }

    /** Permite injetar um SesV2Client falso nos testes. */
    public NotificationHandler(SesV2Client ses, String senderEmail, List<String> recipients) {
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("at least one recipient is required");
        }
        this.ses = ses;
        this.senderEmail = senderEmail;
        this.recipients = List.copyOf(recipients);
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            String messageId = message.getMessageId();
            try {
                CriticalFeedbackEvent feedback = MAPPER.readValue(message.getBody(), CriticalFeedbackEvent.class);
                feedback.validate();
                String sesMessageId = send(NotificationEmail.from(feedback));
                logger.log("Notification sent: sqsMessageId=%s feedbackId=%s sesMessageId=%s recipients=%d"
                        .formatted(messageId, feedback.id(), sesMessageId, recipients.size()), LogLevel.INFO);
            } catch (Exception e) {
                // Não loga o corpo da mensagem: a descrição é texto livre do aluno
                logger.log("Notification failed: sqsMessageId=%s receiveCount=%s error=%s: %s"
                        .formatted(messageId, receiveCount(message), e.getClass().getSimpleName(), e.getMessage()),
                        LogLevel.ERROR);
                failures.add(new SQSBatchResponse.BatchItemFailure(messageId));
            }
        }
        return new SQSBatchResponse(failures);
    }

    private String send(NotificationEmail email) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(senderEmail)
                .destination(d -> d.toAddresses(recipients))
                .content(c -> c.simple(m -> m
                        .subject(s -> s.data(email.subject()).charset("UTF-8"))
                        .body(b -> b.text(t -> t.data(email.body()).charset("UTF-8")))))
                .build();
        SendEmailResponse response = ses.sendEmail(request);
        return response.messageId();
    }

    /** Quantas vezes a mensagem já foi entregue; ajuda a ver no log quando ela está perto de ir para a DLQ. */
    private static String receiveCount(SQSEvent.SQSMessage message) {
        return message.getAttributes() == null ? "?" : message.getAttributes().getOrDefault("ApproximateReceiveCount", "?");
    }

    static List<String> parseRecipients(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("environment variable " + name + " is not set");
        }
        return value;
    }
}
