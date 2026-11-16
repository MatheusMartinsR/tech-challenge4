package br.com.fiap.tc4.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

class NotificationHandlerTest {

    private static final String TEMPLATE = "tc4-dev-critical-feedback";
    private static final String VALID_BODY =
            "{\"id\":\"abc-1\",\"descricao\":\"Aula confusa\",\"nota\":2,\"urgencia\":\"CRITICAL\","
                    + "\"dataEnvio\":\"2026-11-10T14:30:00Z\"}";

    private final List<SendEmailRequest> sent = new ArrayList<>();
    private final List<String> logs = new ArrayList<>();

    @Test
    void sendsWithTheSesTemplate() throws Exception {
        var handler = new NotificationHandler(fakeSes(false), "remetente@exemplo.com",
                List.of("admin1@exemplo.com", "admin2@exemplo.com"), TEMPLATE);

        SQSBatchResponse response = handler.handleRequest(event(message("m-1", VALID_BODY)), fakeContext());

        assertTrue(response.getBatchItemFailures().isEmpty());
        assertEquals(1, sent.size());
        SendEmailRequest request = sent.get(0);
        assertEquals("remetente@exemplo.com", request.fromEmailAddress());
        assertEquals(List.of("admin1@exemplo.com", "admin2@exemplo.com"), request.destination().toAddresses());
        assertEquals(TEMPLATE, request.content().template().templateName());

        JsonNode data = new ObjectMapper().readTree(request.content().template().templateData());
        assertEquals("Aula confusa", data.get("descricao").asText());
        assertEquals("2", data.get("nota").asText());
        assertEquals("CRITICAL", data.get("urgencia").asText());
        assertEquals("10/11/2026 11:30 (horário de Brasília)", data.get("dataEnvio").asText());
        assertEquals("abc-1", data.get("id").asText());
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("Notification sent: sqsMessageId=m-1")));
    }

    @Test
    void invalidEventIsReturnedAsFailureWithoutSending() {
        var handler = new NotificationHandler(fakeSes(false), "remetente@exemplo.com", List.of("admin@exemplo.com"), TEMPLATE);

        SQSBatchResponse response = handler.handleRequest(event(message("m-2", "{\"id\":\"x\",\"nota\":1}")), fakeContext());

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("m-2", response.getBatchItemFailures().get(0).getItemIdentifier());
        assertTrue(sent.isEmpty());
    }

    @Test
    void sesErrorIsReturnedAsFailure() {
        var handler = new NotificationHandler(fakeSes(true), "remetente@exemplo.com", List.of("admin@exemplo.com"), TEMPLATE);

        SQSBatchResponse response = handler.handleRequest(event(message("m-3", VALID_BODY)), fakeContext());

        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("m-3", response.getBatchItemFailures().get(0).getItemIdentifier());
        assertTrue(logs.stream().anyMatch(l -> l.startsWith("Notification failed: sqsMessageId=m-3")));
    }

    @Test
    void eachMessageIsHandledIndependently() {
        var handler = new NotificationHandler(fakeSes(false), "remetente@exemplo.com", List.of("admin@exemplo.com"), TEMPLATE);

        SQSBatchResponse response = handler.handleRequest(
                event(message("ok", VALID_BODY), message("ruim", "{\"nota\":1}")), fakeContext());

        assertEquals(1, sent.size());
        assertEquals(1, response.getBatchItemFailures().size());
        assertEquals("ruim", response.getBatchItemFailures().get(0).getItemIdentifier());
    }

    // ----- dublês de teste (Proxy evita implementar as interfaces inteiras) -----

    private SesV2Client fakeSes(boolean fail) {
        return (SesV2Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {SesV2Client.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendEmail") && args != null && args[0] instanceof SendEmailRequest r) {
                        if (fail) {
                            throw new IllegalStateException("SES indisponível");
                        }
                        sent.add(r);
                        return SendEmailResponse.builder().messageId("ses-123").build();
                    }
                    return switch (method.getName()) {
                        case "serviceName" -> "ses";
                        case "toString" -> "FakeSes";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    };
                });
    }

    private Context fakeContext() {
        LambdaLogger logger = (LambdaLogger) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {LambdaLogger.class}, (proxy, method, args) -> {
                    if (method.getName().equals("log") && args != null && args[0] instanceof String message) {
                        logs.add(message);
                    }
                    return null;
                });
        return (Context) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Context.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> logger;
                    case "getRemainingTimeInMillis", "getMemoryLimitInMB" -> 0;
                    default -> null;
                });
    }

    private static SQSEvent.SQSMessage message(String id, String body) {
        var message = new SQSEvent.SQSMessage();
        message.setMessageId(id);
        message.setBody(body);
        message.setAttributes(Map.of("ApproximateReceiveCount", "1"));
        return message;
    }

    private static SQSEvent event(SQSEvent.SQSMessage... messages) {
        var event = new SQSEvent();
        event.setRecords(List.of(messages));
        return event;
    }
}
