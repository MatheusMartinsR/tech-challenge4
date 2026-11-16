package br.com.fiap.tc4.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateDataTest {

    @Test
    void fillsAllTemplateFieldsWithDateInBrasiliaTime() {
        var event = new CriticalFeedbackEvent("abc-1", "Aula confusa", 2, "CRITICAL", "2026-11-10T14:30:00Z");

        Map<String, String> data = TemplateData.from(event);

        assertEquals("Aula confusa", data.get("descricao"));
        assertEquals("2", data.get("nota"));
        assertEquals("CRITICAL", data.get("urgencia"));
        assertEquals("10/11/2026 11:30 (horário de Brasília)", data.get("dataEnvio"));
        assertEquals("abc-1", data.get("id"));
    }

    @Test
    void keysMatchTheTemplatePlaceholders() {
        var event = new CriticalFeedbackEvent("abc-2", "Teste", 3, "CRITICAL", "2026-11-10T14:30:00Z");

        // Os nomes precisam ser iguais aos {{campos}} do template no template.yaml
        assertEquals(List.of("descricao", "nota", "urgencia", "dataEnvio", "id"),
                List.copyOf(TemplateData.from(event).keySet()));
    }

    @Test
    void missingIdBecomesDash() {
        var event = new CriticalFeedbackEvent(null, "Sem áudio", 1, "CRITICAL", "2026-11-10T14:30:00Z");

        assertEquals("-", TemplateData.from(event).get("id"));
    }

    @Test
    void nonIsoDateIsKeptAsIs() {
        assertEquals("ontem à tarde", TemplateData.formatDate("ontem à tarde"));
    }
}
