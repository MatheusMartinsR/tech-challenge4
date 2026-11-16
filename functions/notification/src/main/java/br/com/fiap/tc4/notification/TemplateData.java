package br.com.fiap.tc4.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Monta os valores que preenchem as lacunas ({{campo}}) do template de e-mail de feedback crítico
 * no SES. O texto do e-mail fica no template (template.yaml); aqui só se preparam os dados.
 * Não depende da AWS, então pode ser testada isoladamente.
 */
public final class TemplateData {

    private static final DateTimeFormatter BRAZIL_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Sao_Paulo"));

    private TemplateData() {
    }

    /**
     * Os nomes das chaves precisam ser iguais aos do template: descricao, nota, urgencia, dataEnvio e id.
     * Todas são sempre preenchidas: no SES, uma lacuna sem valor faz o envio falhar depois de aceito.
     */
    public static Map<String, String> from(CriticalFeedbackEvent event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("descricao", event.descricao());
        data.put("nota", String.valueOf(event.nota()));
        data.put("urgencia", event.urgencia());
        data.put("dataEnvio", formatDate(event.dataEnvio()));
        data.put("id", event.id() == null ? "-" : event.id());
        return data;
    }

    /** Converte ISO-8601 (UTC) para o horário de Brasília; se não for ISO, mostra o valor original. */
    static String formatDate(String isoInstant) {
        try {
            return BRAZIL_FORMAT.format(Instant.parse(isoInstant)) + " (horário de Brasília)";
        } catch (DateTimeParseException e) {
            return isoInstant;
        }
    }
}
