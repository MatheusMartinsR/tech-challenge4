package br.com.fiap.tc4.ingestion;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

/**
 * POST /avaliacao. O corpo já chega validado pelo API Gateway (modelo EvaluationRequest).
 * TODO: gravar no DynamoDB (TABLE_NAME) e publicar feedbacks críticos no SNS (TOPIC_ARN).
 */
public class IngestionHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        context.getLogger().log("Evaluation received (placeholder)");
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(202)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody("{\"status\":\"placeholder\"}");
    }
}
