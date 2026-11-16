package br.com.fiap.tc4.ingestion;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;

/** Adaptador HTTP de desenvolvimento. Compilado somente com -Plocal; não é runtime Lambda. */
public final class LocalApiServer {
    public static void main(String[] args) throws IOException {
        // Não lê credenciais nem endpoints do ambiente do usuário.
        URI endpoint = URI.create("http://localstack:4566");
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        var database = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        var sns = SnsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder()).credentialsProvider(credentials).build();
        var handler = new IngestionHandler(database, sns, "tc4-local-feedbacks",
                "arn:aws:sns:us-east-1:000000000000:tc4-local-critical-feedback", Clock.systemUTC());
        var server = HttpServer.create(new InetSocketAddress("0.0.0.0", 3000), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                    send(exchange, 200, "{\"status\":\"local\"}");
                } else if (!path.equals("/avaliacao")) {
                    send(exchange, 404, "{\"codigo\":\"ROTA_INEXISTENTE\"}");
                } else if (!exchange.getRequestMethod().equals("POST")) {
                    exchange.getResponseHeaders().set("Allow", "POST");
                    send(exchange, 405, "{\"codigo\":\"METODO_INVALIDO\"}");
                } else {
                    byte[] body = exchange.getRequestBody().readNBytes(32769);
                    if (body.length > 32768) {
                        send(exchange, 413, "{\"codigo\":\"CORPO_MUITO_GRANDE\"}");
                    } else {
                        var response = handler.handleRequest(new APIGatewayProxyRequestEvent()
                                .withPath(path).withHttpMethod("POST")
                                .withBody(new String(body, StandardCharsets.UTF_8)), new LocalContext());
                        send(exchange, response.getStatusCode(), response.getBody());
                    }
                }
            } catch (Exception e) {
                System.err.println("Local HTTP request failed: " + e.getClass().getSimpleName());
                send(exchange, 500, "{\"codigo\":\"ERRO_INTERNO\",\"mensagem\":\"Falha no servidor local.\"}");
            } finally {
                exchange.close();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            database.close();
            sns.close();
        }));
        server.start();
        System.out.println("API local: http://localhost:3000/avaliacao | AWS: LocalStack | credenciais fictícias");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static final class LocalContext implements Context {
        private final String id = UUID.randomUUID().toString();
        public String getAwsRequestId() { return id; }
        public String getLogGroupName() { return "local"; }
        public String getLogStreamName() { return "local"; }
        public String getFunctionName() { return "tc4-local-ingestion"; }
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
