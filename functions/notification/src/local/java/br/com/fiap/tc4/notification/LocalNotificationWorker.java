package br.com.fiap.tc4.notification;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/** Substitui somente o gatilho gerenciado da Lambda no desenvolvimento local. */
public final class LocalNotificationWorker {
    public static void main(String[] args) {
        AtomicBoolean running = new AtomicBoolean(true);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> running.set(false)));
        try (var sqs = SqsClient.builder().endpointOverride(URI.create("http://localstack:4566"))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1).build();
             var mail = new MailpitSesClient()) {
            var handler = new NotificationHandler(mail, "feedback@tc4.test", List.of("admin@tc4.test"), mail.templateName());
            // Não usa URL retornada pelo SQS, que pode apontar para localhost dentro de outro container.
            String queue = "http://localstack:4566/000000000000/tc4-local-notification";
            System.out.println("Consumidor local ativo: SQS -> NotificationHandler -> Mailpit (sem SES real)");
            while (running.get()) {
                try {
                    var messages = sqs.receiveMessage(b -> b.queueUrl(queue).maxNumberOfMessages(1)
                            .waitTimeSeconds(10).visibilityTimeout(20)
                            .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
                    for (var message : messages) {
                        var record = new SQSEvent.SQSMessage();
                        record.setMessageId(message.messageId());
                        record.setBody(message.body());
                        record.setAttributes(Map.of("ApproximateReceiveCount", message.attributes()
                                .getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "1")));
                        var event = new SQSEvent();
                        event.setRecords(List.of(record));
                        var result = handler.handleRequest(event, new LocalContext());
                        boolean failed = result.getBatchItemFailures() != null && result.getBatchItemFailures().stream()
                                .anyMatch(failure -> message.messageId().equals(failure.getItemIdentifier()));
                        if (!failed) {
                            sqs.deleteMessage(b -> b.queueUrl(queue).receiptHandle(message.receiptHandle()));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Local queue polling failed: " + e.getClass().getSimpleName());
                    try { Thread.sleep(2000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
    }

    private static final class LocalContext implements Context {
        private final String id = UUID.randomUUID().toString();
        public String getAwsRequestId() { return id; }
        public String getLogGroupName() { return "local"; }
        public String getLogStreamName() { return "local"; }
        public String getFunctionName() { return "tc4-local-notification"; }
        public String getFunctionVersion() { return "local"; }
        public String getInvokedFunctionArn() { return "local"; }
        public CognitoIdentity getIdentity() { return null; }
        public ClientContext getClientContext() { return null; }
        public int getRemainingTimeInMillis() { return 20000; }
        public int getMemoryLimitInMB() { return 512; }
        public LambdaLogger getLogger() {
            return new LambdaLogger() {
                public void log(String message) { System.out.println(message); }
                public void log(byte[] message) { log(new String(message, StandardCharsets.UTF_8)); }
            };
        }
    }
}
