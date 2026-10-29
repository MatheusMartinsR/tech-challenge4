package br.com.fiap.tc4.report;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

/**
 * Executada semanalmente pelo EventBridge Scheduler.
 * TODO: consultar os feedbacks da semana (TABLE_NAME), calcular os indicadores e enviar via SES (SENDER_EMAIL).
 */
public class WeeklyReportHandler implements RequestHandler<Map<String, Object>, Void> {

    @Override
    public Void handleRequest(Map<String, Object> input, Context context) {
        context.getLogger().log("Weekly report triggered (placeholder)");
        return null;
    }
}
