package br.com.fiap.tc4.notification;

import java.util.List;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

/**
 * Consome a fila de feedbacks críticos. Mensagens devolvidas como falha voltam para a fila
 * e, após esgotar as tentativas, vão para a DLQ.
 * TODO: enviar e-mail via SES (SENDER_EMAIL) e registrar sucesso/falha do envio.
 */
public class NotificationHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        context.getLogger().log(event.getRecords().size() + " message(s) received (placeholder)");
        return new SQSBatchResponse(List.of());
    }
}
