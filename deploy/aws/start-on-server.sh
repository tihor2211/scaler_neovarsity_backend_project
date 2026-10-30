#!/bin/bash
# Runs on the EC2 instance: the Kafka broker and the DashCart API as containers on one Docker network.
# The API reads its configuration (RDS and ElastiCache endpoints, secrets) from ~/dashcart.env.
set -euo pipefail
docker network create dashcart 2> /dev/null || true
docker rm -f kafka dashcart-app > /dev/null 2>&1 || true

docker run -d --name kafka --network dashcart --restart unless-stopped --memory 640m \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_NUM_PARTITIONS=3 \
  -e KAFKA_HEAP_OPTS="-Xmx256m -Xms256m" \
  apache/kafka:4.3.2

docker run -d --name dashcart-app --network dashcart --restart unless-stopped --memory 900m \
  -p 80:8080 --env-file "$HOME/dashcart.env" dashcart:latest

docker ps --format '{{.Names}} {{.Status}}'
