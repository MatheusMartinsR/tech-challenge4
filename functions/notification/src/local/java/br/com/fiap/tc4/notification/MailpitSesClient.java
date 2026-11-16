package br.com.fiap.tc4.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

/** Transporte somente local: captura a mensagem SES montada pelo handler na caixa Mailpit. */
final class MailpitSesClient implements SesV2Client {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Override
    public SendEmailResponse sendEmail(SendEmailRequest request) {
        try {
            var email = request.content().simple();
            var payload = Map.of(
                    "From", Map.of("Email", request.fromEmailAddress()),
                    "To", request.destination().toAddresses().stream().map(address -> Map.of("Email", address)).toList(),
                    "Subject", email.subject().data(),
                    "Text", email.body().text().data());
            var response = http.send(HttpRequest.newBuilder(URI.create("http://mailpit:8025/api/v1/send"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Mailpit returned HTTP " + response.statusCode());
            }
            String id = json.readTree(response.body()).path("ID").asText();
            if (id.isBlank()) throw new IllegalStateException("Mailpit did not return a message ID");
            return SendEmailResponse.builder().messageId(id).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mailpit request interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Local mail capture failed", e);
        }
    }

    @Override public String serviceName() { return "local-mailpit"; }
    @Override public void close() { http.close(); }
}
