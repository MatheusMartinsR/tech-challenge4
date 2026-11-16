#!/bin/bash
set -euo pipefail

# Executado dentro do LocalStack. Endpoint local e credenciais fictícias explícitos.
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1
export AWS_ENDPOINT_URL=http://localhost:4566

if ! awslocal dynamodb describe-table --table-name tc4-local-feedbacks >/dev/null 2>&1; then
  awslocal dynamodb create-table --table-name tc4-local-feedbacks \
    --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE >/dev/null
fi
awslocal dynamodb wait table-exists --table-name tc4-local-feedbacks

topic_arn=$(awslocal sns create-topic --name tc4-local-critical-feedback --query TopicArn --output text)
queue_url=$(awslocal sqs create-queue --queue-name tc4-local-notification --query QueueUrl --output text)
queue_arn=$(awslocal sqs get-queue-attributes --queue-url "$queue_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)

cat > /tmp/tc4-policy.json <<EOF
{"Policy":"{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$queue_arn\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$topic_arn\"}}}]}"}
EOF
awslocal sqs set-queue-attributes --queue-url "$queue_url" --attributes file:///tmp/tc4-policy.json
awslocal sns subscribe --topic-arn "$topic_arn" --protocol sqs --notification-endpoint "$queue_arn" \
  --attributes RawMessageDelivery=true >/dev/null
touch /tmp/tc4-ready
echo 'TC4 local: tabela, tópico e fila prontos.'
