package br.com.fiap.tc4.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalSesTemplateTest {
    @Test
    void readsActualCloudFormationTemplateWithIntrinsicTags() {
        var template = LocalSesTemplate.load(Path.of("..", "..", "template.yaml"));
        assertEquals("tc4-local-critical-feedback", template.name());
        Map<String, String> values = Map.of("descricao", "Aula <sem áudio> & ruim", "nota", "2",
                "urgencia", "ALTA", "dataEnvio", "15/11/2026 22:00", "id", "test-id");
        assertEquals("[Feedback crítico] Urgência ALTA - nota 2", LocalSesTemplate.render(template.subject(), values, false));
        assertTrue(LocalSesTemplate.render(template.text(), values, false).contains("Aula <sem áudio> & ruim"));
        var html = LocalSesTemplate.render(template.html(), values, true);
        assertTrue(html.contains("Aula &lt;sem áudio&gt; &amp; ruim"));
        assertFalse(html.contains("{{"));
    }

    @Test
    void doesNotInterpretPlaceholdersOrRegexCharactersInStudentInput() {
        String input = "{{id}} $1 \\ texto";
        assertEquals(input, LocalSesTemplate.render("{{descricao}}", Map.of("descricao", input), false));
    }

    @Test
    void missingValuesAndUnescapedExpressionsFailInsteadOfSendingIncompleteEmail() {
        assertThrows(IllegalArgumentException.class, () -> LocalSesTemplate.render("{{id}}", Map.of(), false));
        assertThrows(IllegalArgumentException.class, () -> LocalSesTemplate.render("{{{descricao}}}", Map.of("descricao", "x"), true));
    }
}
