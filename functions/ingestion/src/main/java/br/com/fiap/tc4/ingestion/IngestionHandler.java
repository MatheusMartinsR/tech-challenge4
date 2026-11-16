package br.com.fiap.tc4.ingestion;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/** Recebe, persiste e publica avaliações críticas. O envio de e-mail pertence ao consumidor. */
public class IngestionHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");
    private final DynamoDbClient database;
    private final SnsClient sns;
    private final String tableName;
    private final String topicArn;
    private final Clock clock;

    public IngestionHandler() {
        this(DynamoDbClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                SnsClient.builder().httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                requiredEnv("TABLE_NAME"), requiredEnv("TOPIC_ARN"), Clock.systemUTC());
    }

    public IngestionHandler(DynamoDbClient database, SnsClient sns, String tableName, String topicArn, Clock clock) {
        this.database = database;
        this.sns = sns;
        this.tableName = tableName;
        this.topicArn = topicArn;
        this.clock = clock;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        final Evaluation evaluation;
        try {
            evaluation = parse(request);
        } catch (IllegalArgumentException e) {
            log(context, "validation_failed", null, e.getMessage(), LogLevel.WARN);
            return response(400, Map.of("codigo", "ENTRADA_INVALIDA", "mensagem", e.getMessage()));
        }

        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        String date = now.toString();
        String urgency = evaluation.nota() <= 3 ? "ALTA" : "NORMAL";
        String day = now.atZone(BRAZIL).toLocalDate().toString();
        Map<String, AttributeValue> item = Map.of(
                "pk", text("DAY#" + day), "sk", text(date + "#" + id),
                "id", text(id), "description", text(evaluation.descricao()),
                "rating", AttributeValue.builder().n(Integer.toString(evaluation.nota())).build(),
                "urgency", text(urgency), "createdAt", text(date));
        try {
            database.putItem(PutItemRequest.builder().tableName(tableName).item(item)
                    .conditionExpression("attribute_not_exists(pk) AND attribute_not_exists(sk)").build());
        } catch (Exception e) {
            log(context, "persistence_failed", id, e.getClass().getSimpleName(), LogLevel.ERROR);
            return response(503, Map.of("codigo", "PERSISTENCIA_INDISPONIVEL",
                    "mensagem", "Não foi possível salvar a avaliação. Tente novamente mais tarde."));
        }

        if (urgency.equals("ALTA")) {
            try {
                sns.publish(PublishRequest.builder().topicArn(topicArn).message(encode(Map.of(
                        "id", id, "descricao", evaluation.descricao(), "nota", evaluation.nota(),
                        "urgencia", urgency, "dataEnvio", date))).build());
            } catch (Exception e) {
                log(context, "publication_failed", id, e.getClass().getSimpleName(), LogLevel.ERROR);
                return response(503, Map.of("codigo", "PUBLICACAO_INDISPONIVEL", "id", id,
                        "persistido", true, "mensagem", "Avaliação salva, mas não foi possível confirmar o envio do alerta."));
            }
        }
        log(context, "evaluation_created", id, urgency, LogLevel.INFO);
        return response(201, Map.of("id", id, "descricao", evaluation.descricao(), "nota", evaluation.nota(),
                "urgencia", urgency, "dataEnvio", date));
    }

    private static Evaluation parse(APIGatewayProxyRequestEvent request) {
        if (request == null || request.getBody() == null || request.getBody().isBlank()) {
            throw new IllegalArgumentException("O corpo JSON é obrigatório.");
        }
        final JsonNode body;
        try {
            String raw = Boolean.TRUE.equals(request.getIsBase64Encoded())
                    ? new String(Base64.getDecoder().decode(request.getBody()), StandardCharsets.UTF_8)
                    : request.getBody();
            body = JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("O corpo deve conter um JSON válido.");
        }
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("O corpo deve ser um objeto JSON.");
        }
        JsonNode description = body.get("descricao");
        if (description == null || !description.isTextual() || description.asText().isBlank()
                || description.asText().codePointCount(0, description.asText().length()) > 1000) {
            throw new IllegalArgumentException("descricao deve ser um texto não vazio de até 1000 caracteres.");
        }
        JsonNode rating = body.get("nota");
        if (rating == null || !rating.isIntegralNumber() || !rating.canConvertToInt()
                || rating.intValue() < 0 || rating.intValue() > 10) {
            throw new IllegalArgumentException("nota deve ser um número inteiro entre 0 e 10.");
        }
        return new Evaluation(description.asText().strip(), rating.intValue());
    }

    private static APIGatewayProxyResponseEvent response(int status, Map<String, Object> body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json; charset=utf-8"))
                .withBody(encode(body));
    }

    private static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize response", e);
        }
    }

    private static void log(Context context, String event, String id, String detail, LogLevel level) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event", event);
        fields.put("requestId", context.getAwsRequestId());
        if (id != null) fields.put("feedbackId", id);
        fields.put("detail", detail);
        context.getLogger().log(encode(fields), level);
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing environment variable: " + name);
        return value;
    }

    private record Evaluation(String descricao, int nota) { }
}
