package br.com.fiap.tc4.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateDataTest {

    @Test
    void from_shouldPopulateAllTemplateFields() {
        CriticalFeedbackEvent event = new CriticalFeedbackEvent(
                "1",
                "Aula muito ruim",
                2,
                "ALTA",
                "2026-11-10T14:30:00Z");

        Map<String, String> data = TemplateData.from(event);

        assertEquals("Aula muito ruim", data.get("descricao"));
        assertEquals("2", data.get("nota"));
        assertEquals("ALTA", data.get("urgencia"));
        assertEquals("1", data.get("id"));
        assertTrue(data.get("dataEnvio").contains("horário de Brasília"));
    }
}
