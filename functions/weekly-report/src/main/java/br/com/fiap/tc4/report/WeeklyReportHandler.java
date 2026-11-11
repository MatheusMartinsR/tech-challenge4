package br.com.fiap.tc4.report;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

public class WeeklyReportHandler implements RequestHandler<Map<String, Object>, Void> {

    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DAY_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public Void handleRequest(Map<String, Object> input, Context context) {
        try {
            String tableName = System.getenv("TABLE_NAME");
            String senderEmail = System.getenv("SENDER_EMAIL");
            String reportRecipients = System.getenv("REPORT_RECIPIENTS");

            if (tableName == null || tableName.isBlank()) {
                throw new IllegalStateException("TABLE_NAME is not configured");
            }
            if (senderEmail == null || senderEmail.isBlank()) {
                throw new IllegalStateException("SENDER_EMAIL is not configured");
            }
            if (reportRecipients == null || reportRecipients.isBlank()) {
                throw new IllegalStateException("REPORT_RECIPIENTS is not configured");
            }

            List<FeedbackRecord> feedbacks = loadLastWeek(tableName);
            WeeklyReport report = buildReport(feedbacks);
            sendEmail(senderEmail, reportRecipients, report);

            String logMessage = String.format("Weekly report generated with %d feedback(s), average %.2f",
                    feedbacks.size(), report.average());
            if (context != null) {
                context.getLogger().log(logMessage);
            }
            return null;
        } catch (Exception e) {
            if (context != null) {
                context.getLogger().log("Weekly report failed: " + e.getMessage());
            }
            throw new RuntimeException("Failed to generate weekly report", e);
        }
    }

    private List<FeedbackRecord> loadLastWeek(String tableName) {
        List<FeedbackRecord> all = new ArrayList<>();
        LocalDate today = LocalDate.now(BRASILIA);
        LocalDate start = today.minusDays(6);

        for (LocalDate date = start; !date.isAfter(today); date = date.plusDays(1)) {
            String pk = "DAY#" + date.format(DAY_KEY_FORMATTER);
            List<Map<String, AttributeValue>> items = queryDay(tableName, pk);
            for (Map<String, AttributeValue> item : items) {
                FeedbackRecord feedback = parseFeedback(item);
                if (feedback != null) {
                    all.add(feedback);
                }
            }
        }

        all.sort(Comparator.comparing(FeedbackRecord::createdAt));
        return all;
    }

    private List<Map<String, AttributeValue>> queryDay(String tableName, String pk) {
        try (DynamoDbClient dynamoDbClient = DynamoDbClient.create()) {
            QueryRequest request = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("pk = :pk")
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.fromS(pk)))
                    .build();

            QueryResponse response = dynamoDbClient.query(request);
            return response.items() == null ? List.of() : response.items();
        }
    }

    private FeedbackRecord parseFeedback(Map<String, AttributeValue> item) {
        try {
            Integer rating = item.get("rating") == null || item.get("rating").n() == null
                    ? null
                    : Integer.parseInt(item.get("rating").n());
            String description = item.get("description") != null ? item.get("description").s() : "";
            String createdAt = item.get("createdAt") != null ? item.get("createdAt").s() : Instant.now().toString();
            String urgency = item.get("urgency") != null ? item.get("urgency").s() : "";

            if (rating == null) {
                return null;
            }

            String resolvedUrgency = resolveUrgency(rating, urgency);
            return new FeedbackRecord(rating, description, resolvedUrgency, createdAt);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveUrgency(int rating, String urgency) {
        if (urgency != null && !urgency.isBlank()) {
            return urgency.trim().toUpperCase();
        }
        if (rating <= 3) {
            return "CRITICAL";
        }
        if (rating <= 6) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private WeeklyReport buildReport(List<FeedbackRecord> feedbacks) {
        Map<String, Integer> byDay = new HashMap<>();
        Map<String, Integer> byUrgency = new HashMap<>();
        double total = 0;

        for (FeedbackRecord feedback : feedbacks) {
            total += feedback.rating();
            byDay.merge(extractDay(feedback.createdAt()), 1, Integer::sum);
            byUrgency.merge(feedback.urgency(), 1, Integer::sum);
        }

        double average = feedbacks.isEmpty() ? 0.0 : total / feedbacks.size();
        return new WeeklyReport(average, byDay, byUrgency);
    }

    private String extractDay(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return LocalDate.now(BRASILIA).format(DAY_KEY_FORMATTER);
        }

        Instant instant = Instant.parse(createdAt);
        return instant.atZone(BRASILIA).toLocalDate().format(DAY_KEY_FORMATTER);
    }

    private void sendEmail(String senderEmail, String reportRecipients, WeeklyReport report) {
        try (SesClient sesClient = SesClient.create()) {
            String subject = "Relatório semanal de feedbacks";
            String body = buildEmailBody(report);

            List<String> recipients = Arrays.stream(reportRecipients.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();

            SendEmailRequest request = SendEmailRequest.builder()
                    .source(senderEmail)
                    .destination(Destination.builder().toAddresses(recipients).build())
                    .message(Message.builder()
                            .subject(Content.builder().data(subject).charset("UTF-8").build())
                            .body(Body.builder()
                                    .text(Content.builder().data(body).charset("UTF-8").build())
                                    .build())
                            .build())
                    .build();

            sesClient.sendEmail(request);
        }
    }

    private String buildEmailBody(WeeklyReport report) {
        StringBuilder body = new StringBuilder();
        body.append("Relatório semanal de feedbacks\n");
        body.append("Média geral: ").append(String.format("%.2f", report.average())).append("\n\n");

        body.append("Quantidade por dia:\n");
        if (report.byDay().isEmpty()) {
            body.append("- Nenhum feedback registrado na última semana\n");
        } else {
            report.byDay().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> body.append("- ")
                            .append(entry.getKey())
                            .append(": ")
                            .append(entry.getValue())
                            .append("\n"));
        }

        body.append("\nQuantidade por urgência:\n");
        if (report.byUrgency().isEmpty()) {
            body.append("- Nenhuma urgência registrada\n");
        } else {
            report.byUrgency().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> body.append("- ")
                            .append(entry.getKey())
                            .append(": ")
                            .append(entry.getValue())
                            .append("\n"));
        }

        return body.toString();
    }

    private record FeedbackRecord(int rating, String description, String urgency, String createdAt) {
    }

    private record WeeklyReport(double average, Map<String, Integer> byDay, Map<String, Integer> byUrgency) {
    }
}
