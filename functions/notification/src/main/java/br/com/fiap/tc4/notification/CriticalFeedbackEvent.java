package br.com.fiap.tc4.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Evento publicado pela ingestão no tópico SNS de feedback crítico (entregue cru na fila,
 * RawMessageDelivery). Os nomes dos campos seguem o contrato em português do POST /avaliacao.
 *
 * Exemplo:
 * {"id":"7f3c...","descricao":"Aula confusa","nota":2,"urgencia":"ALTA","dataEnvio":"2026-11-10T14:30:00Z"}
 *
 * Campos desconhecidos são ignorados para que a ingestão possa evoluir o evento sem quebrar esta função.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CriticalFeedbackEvent(
        String id,
        String descricao,
        Integer nota,
        String urgencia,
        String dataEnvio) {

    /** Lança IllegalArgumentException se faltar algum campo obrigatório para montar o e-mail. */
    public void validate() {
        if (descricao == null || descricao.isBlank()) {
            throw new IllegalArgumentException("field 'descricao' is required");
        }
        if (nota == null) {
            throw new IllegalArgumentException("field 'nota' is required");
        }
        if (urgencia == null || urgencia.isBlank()) {
            throw new IllegalArgumentException("field 'urgencia' is required");
        }
        if (dataEnvio == null || dataEnvio.isBlank()) {
            throw new IllegalArgumentException("field 'dataEnvio' is required");
        }
    }
}
