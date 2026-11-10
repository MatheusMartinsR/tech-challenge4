package br.com.fiap.tc4.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Monta o assunto e o corpo do e-mail de feedback crítico. Não depende da AWS,
 * então pode ser testada isoladamente.
 */
public record NotificationEmail(String subject, String body) {

    private static final DateTimeFormatter BRAZIL_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Sao_Paulo"));

    public static NotificationEmail from(CriticalFeedbackEvent event) {
        String subject = "[Feedback crítico] Urgência " + event.urgencia() + " - nota " + event.nota();
        String body = """
                Um feedback crítico foi recebido na plataforma de avaliação de aulas.

                Descrição: %s
                Nota: %d
                Urgência: %s
                Data de envio: %s
                ID: %s
                """.formatted(
                event.descricao(),
                event.nota(),
                event.urgencia(),
                formatDate(event.dataEnvio()),
                event.id() == null ? "-" : event.id());
        return new NotificationEmail(subject, body);
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
