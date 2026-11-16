package br.com.fiap.tc4.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WeeklyReportHandlerTest {

    @Test
    void buildReport_shouldAggregateAverageAndCounts() {
        WeeklyReportHandler handler = new WeeklyReportHandler();

        var feedbacks = List.of(
                WeeklyReportHandler.createFeedbackRecord(9, "Aula muito boa", "LOW", "2026-11-11T10:00:00Z"),
                WeeklyReportHandler.createFeedbackRecord(6, "Média", "MEDIUM", "2026-11-12T09:00:00Z"),
                WeeklyReportHandler.createFeedbackRecord(2, "Ruim", "CRITICAL", "2026-11-13T11:00:00Z"));

        var report = handler.buildReportForTest(feedbacks);

        assertEquals(5.67, report.average(), 0.01);
        assertEquals(3, report.byDay().size());
        assertEquals(1, report.byUrgency().get("LOW"));
        assertEquals(1, report.byUrgency().get("MEDIUM"));
        assertEquals(1, report.byUrgency().get("CRITICAL"));
    }

    @Test
    void extractDay_shouldReturnBrazilianDateFromIsoInstant() {
        WeeklyReportHandler handler = new WeeklyReportHandler();

        String day = handler.extractDayForTest("2026-11-11T10:00:00Z");

        assertEquals("2026-11-11", day);
    }

    @Test
    void parseFeedback_shouldIgnoreInvalidRows() {
        WeeklyReportHandler handler = new WeeklyReportHandler();

        var item = Map.of(
                "rating", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromN("7"),
                "description", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("Boa aula"),
                "urgency", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("LOW"),
                "createdAt", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("2026-11-11T10:00:00Z"));

        var record = handler.parseFeedbackForTest(item);

        assertTrue(record.isPresent());
        assertEquals(7, record.get().rating());
    }

    @Test
    void buildEmailBody_shouldListDescriptionUrgencyAndSentDateOfEachFeedback() {
        WeeklyReportHandler handler = new WeeklyReportHandler();
        var feedbacks = List.of(
                WeeklyReportHandler.createFeedbackRecord(2, "Aula confusa", "ALTA", "2026-11-12T13:30:00Z"),
                WeeklyReportHandler.createFeedbackRecord(9, "Aula muito boa", "NORMAL", "2026-11-13T10:00:00Z"));

        String body = handler.buildEmailBody(handler.buildReportForTest(feedbacks));

        assertTrue(body.contains("Média geral: 5"));
        assertTrue(body.contains("Feedbacks da semana:"));
        assertTrue(body.contains("- Data de envio: 12/11/2026 10:30 | Urgência: ALTA | Nota: 2 | Descrição: Aula confusa"));
        assertTrue(body.contains("- Data de envio: 13/11/2026 07:00 | Urgência: NORMAL | Nota: 9 | Descrição: Aula muito boa"));
    }

    @Test
    void buildEmailBody_shouldSayWhenThereAreNoFeedbacks() {
        WeeklyReportHandler handler = new WeeklyReportHandler();

        String body = handler.buildEmailBody(handler.buildReportForTest(List.of()));

        assertTrue(body.contains("Feedbacks da semana:\n- Nenhum feedback registrado na última semana"));
    }

    @Test
    void reportDays_shouldCoverPreviousMondayToSundayWithoutExecutionDay() {
        var monday = java.time.LocalDate.of(2026, 11, 16);

        var days = WeeklyReportHandler.reportDays(monday);

        assertEquals(7, days.size());
        assertEquals(java.time.LocalDate.of(2026, 11, 9), days.get(0));
        assertEquals(java.time.LocalDate.of(2026, 11, 15), days.get(6));
    }

}
