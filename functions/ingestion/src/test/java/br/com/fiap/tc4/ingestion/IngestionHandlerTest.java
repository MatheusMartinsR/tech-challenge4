package br.com.fiap.tc4.ingestion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.logging.LogLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

class IngestionHandlerTest {
    private final ObjectMapper json = new ObjectMapper();
    private DynamoDbClient database;
    private SnsClient sns;
    private Context context;
    private LambdaLogger logger;
    private IngestionHandler handler;

    @BeforeEach
    void setup() {
        database = mock(DynamoDbClient.class);
        sns = mock(SnsClient.class);
        context = mock(Context.class);
        logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
        when(context.getAwsRequestId()).thenReturn("request-test");
        handler = new IngestionHandler(database, sns, "feedbacks", "critical-topic",
                Clock.fixed(Instant.parse("2026-11-15T01:30:00Z"), ZoneOffset.UTC));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, 4, 10})
    void persistsAndPublishesOnlyCriticalRatings(int rating) throws Exception {
        var response = handler.handleRequest(request("{\"descricao\":\" Aula teste \",\"nota\":" + rating + "}"), context);
        assertEquals(201, response.getStatusCode());
        var body = json.readTree(response.getBody());
        assertDoesNotThrow(() -> java.util.UUID.fromString(body.get("id").asText()));
        assertEquals("Aula teste", body.get("descricao").asText());
        assertEquals(rating <= 3 ? "ALTA" : "NORMAL", body.get("urgencia").asText());
        var saved = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(database).putItem(saved.capture());
        var item = saved.getValue().item();
        assertEquals("feedbacks", saved.getValue().tableName());
        assertEquals("DAY#2026-11-14", item.get("pk").s());
        assertEquals("2026-11-15T01:30:00Z#" + body.get("id").asText(), item.get("sk").s());
        assertEquals("Aula teste", item.get("description").s());
        assertEquals(Integer.toString(rating), item.get("rating").n());
        assertEquals(body.get("urgencia").asText(), item.get("urgency").s());
        assertEquals(body.get("dataEnvio").asText(), item.get("createdAt").s());
        if (rating <= 3) {
            var event = ArgumentCaptor.forClass(PublishRequest.class);
            var order = inOrder(database, sns);
            order.verify(database).putItem(any(PutItemRequest.class));
            order.verify(sns).publish(event.capture());
            assertEquals("critical-topic", event.getValue().topicArn());
            assertEquals(body, json.readTree(event.getValue().message()));
        } else {
            verifyNoInteractions(sns);
        }
    }

    static Stream<String> invalidBodies() {
        return Stream.of(null, "", " ", "{", "null", "[]", "42", "{}",
                "{\"descricao\":\" \",\"nota\":4}", "{\"descricao\":3,\"nota\":4}",
                "{\"descricao\":null,\"nota\":4}", "{\"descricao\":\"a\"}",
                "{\"descricao\":\"a\",\"nota\":null}", "{\"descricao\":\"a\",\"nota\":\"3\"}",
                "{\"descricao\":\"a\",\"nota\":3.0}", "{\"descricao\":\"a\",\"nota\":true}",
                "{\"descricao\":\"a\",\"nota\":-1}", "{\"descricao\":\"a\",\"nota\":11}",
                "{\"descricao\":\"a\",\"nota\":999999999999999999999}",
                "{\"descricao\":\"a\",\"nota\":3,\"nota\":8}",
                "{\"descricao\":\"a\",\"nota\":3} {}",
                "{\"descricao\":\"" + "a".repeat(1001) + "\",\"nota\":4}");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsMalformedInputWithoutSideEffects(String body) throws Exception {
        var response = handler.handleRequest(request(body), context);
        assertEquals(400, response.getStatusCode());
        assertEquals("ENTRADA_INVALIDA", json.readTree(response.getBody()).get("codigo").asText());
        assertFalse(json.readTree(response.getBody()).get("mensagem").asText().isBlank());
        verifyNoInteractions(database, sns);
        verify(logger).log(contains("validation_failed"), eq(LogLevel.WARN));
    }

    @Test
    void acceptsMaximumDescriptionAndBase64() {
        String body = "{\"descricao\":\"" + "a".repeat(1000) + "\",\"nota\":8}";
        var request = request(Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)))
                .withIsBase64Encoded(true);
        assertEquals(201, handler.handleRequest(request, context).getStatusCode());
    }

    @Test
    void rejectsInvalidBase64AndMissingEvent() {
        assertEquals(400, handler.handleRequest(request("%%%?").withIsBase64Encoded(true), context).getStatusCode());
        assertEquals(400, handler.handleRequest(null, context).getStatusCode());
        verifyNoInteractions(database, sns);
    }

    @Test
    void databaseFailureDoesNotPublishOrExposeInternalError() {
        when(database.putItem(any(PutItemRequest.class))).thenThrow(new RuntimeException("private infrastructure details"));
        var response = handler.handleRequest(request("{\"descricao\":\"a\",\"nota\":2}"), context);
        assertEquals(503, response.getStatusCode());
        assertTrue(response.getBody().contains("PERSISTENCIA_INDISPONIVEL"));
        assertFalse(response.getBody().contains("private"));
        verifyNoInteractions(sns);
    }

    @Test
    void publicationFailureReportsAlreadyPersistedFeedback() throws Exception {
        when(sns.publish(any(PublishRequest.class))).thenThrow(new RuntimeException("unavailable"));
        var response = handler.handleRequest(request("{\"descricao\":\"a\",\"nota\":2}"), context);
        assertEquals(503, response.getStatusCode());
        var body = json.readTree(response.getBody());
        assertEquals("PUBLICACAO_INDISPONIVEL", body.get("codigo").asText());
        assertTrue(body.get("persistido").asBoolean());
        assertTrue(body.hasNonNull("id"));
        verify(database).putItem(any(PutItemRequest.class));
    }

    @Test
    void logsDoNotContainStudentDescription() {
        handler.handleRequest(request("{\"descricao\":\"texto privado do aluno\",\"nota\":2}"), context);
        var entry = ArgumentCaptor.forClass(String.class);
        verify(logger).log(entry.capture(), eq(LogLevel.INFO));
        assertFalse(entry.getValue().contains("texto privado"));
        assertTrue(entry.getValue().contains("request-test"));
    }

    private static APIGatewayProxyRequestEvent request(String body) {
        return new APIGatewayProxyRequestEvent().withHttpMethod("POST").withPath("/avaliacao").withBody(body);
    }
}
