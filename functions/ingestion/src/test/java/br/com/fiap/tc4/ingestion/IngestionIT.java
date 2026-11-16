package br.com.fiap.tc4.ingestion;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** Integração com DynamoDB, SNS e SQS reais do emulador; nenhum cliente AWS é mockado. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestionIT {
    private DynamoDbClient database;
    private SnsClient sns;
    private SqsClient sqs;
    private String table;
    private String topic;
    private String queue;
    private IngestionHandler handler;
    private Context context;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void setup() {
        // Endpoint local explícito, credenciais fictícias: nunca usa a conta AWS do desenvolvedor.
        URI endpoint = URI.create(System.getProperty("localstack.endpoint", "http://localhost:4566"));
        if (!java.util.Set.of("localhost", "127.0.0.1", "::1").contains(endpoint.getHost())) {
            throw new IllegalArgumentException("Integration tests require a loopback LocalStack endpoint");
        }
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        database = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        sns = SnsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        sqs = SqsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
        String suffix = UUID.randomUUID().toString();
        table = "ingestion-it-" + suffix;
        database.createTable(b -> b.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()));
        database.waiter().waitUntilTableExists(b -> b.tableName(table));
        topic = sns.createTopic(b -> b.name("ingestion-it-" + suffix)).topicArn();
        queue = sqs.createQueue(b -> b.queueName("ingestion-it-" + suffix)).queueUrl();
        String queueArn = sqs.getQueueAttributes(b -> b.queueUrl(queue)
                .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
        sqs.setQueueAttributes(b -> b.queueUrl(queue).attributes(java.util.Map.of(QueueAttributeName.POLICY,
                "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\","
                + "\"Resource\":\"" + queueArn + "\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"" + topic + "\"}}}]}")));
        sns.subscribe(b -> b.topicArn(topic).protocol("sqs").endpoint(queueArn)
                .attributes(java.util.Map.of("RawMessageDelivery", "true")));
        context = mock(Context.class);
        when(context.getLogger()).thenReturn(mock(LambdaLogger.class));
        when(context.getAwsRequestId()).thenReturn("integration-test");
        handler = new IngestionHandler(database, sns, table, topic,
                Clock.fixed(Instant.parse("2026-11-15T01:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void validCriticalFeedbackIsStoredAndDeliveredWithNotificationContract() throws Exception {
        var response = handler.handleRequest(new APIGatewayProxyRequestEvent()
                .withBody("{\"descricao\":\"Aula sem áudio\",\"nota\":2}"), context);
        assertEquals(201, response.getStatusCode());
        var body = json.readTree(response.getBody());
        var saved = database.getItem(b -> b.tableName(table).consistentRead(true).key(java.util.Map.of(
                "pk", AttributeValue.builder().s("DAY#2026-11-14").build(),
                "sk", AttributeValue.builder().s(body.get("dataEnvio").asText() + "#" + body.get("id").asText()).build()))).item();
        assertEquals("Aula sem áudio", saved.get("description").s());
        assertEquals("2", saved.get("rating").n());
        assertEquals("ALTA", saved.get("urgency").s());
        var messages = sqs.receiveMessage(b -> b.queueUrl(queue).waitTimeSeconds(10).maxNumberOfMessages(1)).messages();
        assertEquals(1, messages.size(), "SNS must deliver the critical event to SQS");
        assertEquals(body, json.readTree(messages.getFirst().body()), "Raw event must match notification fields");
        sqs.deleteMessage(b -> b.queueUrl(queue).receiptHandle(messages.getFirst().receiptHandle()));
    }

    @Test
    void normalFeedbackIsStoredWithoutPublishing() throws Exception {
        var response = handler.handleRequest(new APIGatewayProxyRequestEvent()
                .withBody("{\"descricao\":\"Boa aula\",\"nota\":8}"), context);
        assertEquals(201, response.getStatusCode());
        var body = json.readTree(response.getBody());
        var saved = database.getItem(b -> b.tableName(table).consistentRead(true).key(java.util.Map.of(
                "pk", AttributeValue.builder().s("DAY#2026-11-14").build(),
                "sk", AttributeValue.builder().s(body.get("dataEnvio").asText() + "#" + body.get("id").asText()).build()))).item();
        assertEquals("NORMAL", saved.get("urgency").s());
        assertTrue(sqs.receiveMessage(b -> b.queueUrl(queue).waitTimeSeconds(2)).messages().isEmpty());
    }

    @Test
    void invalidFeedbackDoesNotCreateAnItem() {
        long before = database.scan(b -> b.tableName(table).consistentRead(true)).count();
        var response = handler.handleRequest(new APIGatewayProxyRequestEvent()
                .withBody("{\"descricao\":\"Inválida\",\"nota\":11}"), context);
        assertEquals(400, response.getStatusCode());
        assertEquals(before, database.scan(b -> b.tableName(table).consistentRead(true)).count().longValue());
    }

    @AfterAll
    void cleanup() {
        try {
            if (queue != null) sqs.deleteQueue(b -> b.queueUrl(queue));
            if (topic != null) sns.deleteTopic(b -> b.topicArn(topic));
            if (table != null) database.deleteTable(b -> b.tableName(table));
        } finally {
            if (database != null) database.close();
            if (sns != null) sns.close();
            if (sqs != null) sqs.close();
        }
    }
}
