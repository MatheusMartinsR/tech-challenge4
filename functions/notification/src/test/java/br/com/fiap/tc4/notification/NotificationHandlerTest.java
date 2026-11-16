package br.com.fiap.tc4.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

class NotificationHandlerTest {

    @Test
    void parseRecipients_shouldSplitCommaSeparatedValues() {
        List<String> recipients = NotificationHandler.parseRecipients("admin@a.com, admin2@a.com,admin3@a.com");

        assertEquals(3, recipients.size());
        assertEquals("admin@a.com", recipients.get(0));
        assertEquals("admin2@a.com", recipients.get(1));
        assertEquals("admin3@a.com", recipients.get(2));
    }

    @Test
    void constructor_shouldRejectEmptyRecipients() {
        SesV2Client ses = mock(SesV2Client.class);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new NotificationHandler(ses, "sender@a.com", List.of(), "critical-feedback-template"));

        assertEquals("at least one recipient is required", exception.getMessage());
    }

    @Test
    void handleRequest_shouldSendEmailAndReturnNoFailures() {
        SesV2Client ses = mock(SesV2Client.class);
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("msg-123").build());

        NotificationHandler handler = new NotificationHandler(ses, "sender@a.com", List.of("admin@a.com"), "critical-feedback-template");

        SQSEvent event = new SQSEvent();
        SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
        message.setBody("{\"id\":\"1\",\"descricao\":\"Aula muito confusa\",\"nota\":2,\"urgencia\":\"ALTA\",\"dataEnvio\":\"2026-11-10T14:30:00Z\"}");
        event.setRecords(List.of(message));

        SQSBatchResponse response = handler.handleRequest(event, mock(Context.class));

        assertFalse(response.getBatchItemFailures().stream().findAny().isPresent());
    }
}
