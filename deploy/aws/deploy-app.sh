#!/usr/bin/env bash
# Ships the locally built image to the EC2 instance and starts Kafka and the API there:
#   1. wait for the instance's first-boot setup (Docker)
#   2. copy the image over SSH (docker save | docker load; no registry needed)
#   3. wait for RDS and ElastiCache, then write the API's environment file with their endpoints
#   4. start the containers and wait for /actuator/health on the public address
# Build the image first: docker build -t dashcart:latest .   (arm64, to match the t4g instance)
set -euo pipefail
. "$(dirname "$0")/config.sh"
. "$LOCAL/secrets.env"

SSH="ssh -i $LOCAL/$KEY_NAME.pem -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$LOCAL/known_hosts -o ConnectTimeout=10 ec2-user@$PUBLIC_IP"

log "waiting for the instance's first-boot setup"
until $SSH test -f /var/tmp/dashcart-ready 2> /dev/null; do sleep 10; done

log "copying the image ($(docker image inspect dashcart:latest --format '{{.Architecture}}'))"
docker save dashcart:latest | gzip -1 | $SSH 'gunzip | docker load'
$SSH 'docker pull -q apache/kafka:4.3.2'

log "waiting for RDS"
aws rds wait db-instance-available --db-instance-identifier "$DB_ID"
DB_HOST=$(aws rds describe-db-instances --db-instance-identifier "$DB_ID" --query 'DBInstances[0].Endpoint.Address' --output text)
log "waiting for ElastiCache"
until [ "$(aws elasticache describe-replication-groups --replication-group-id "$CACHE_ID" --query 'ReplicationGroups[0].Status' --output text)" = available ]; do sleep 15; done
REDIS=$(aws elasticache describe-replication-groups --replication-group-id "$CACHE_ID" --query 'ReplicationGroups[0].NodeGroups[0].PrimaryEndpoint.Address' --output text)

log "starting Kafka and the API"
$SSH "umask 077 && cat > dashcart.env" <<EOF
DB_URL=jdbc:mysql://$DB_HOST:3306/dashcart
DB_USERNAME=dashcart_admin
DB_PASSWORD=$DB_PASSWORD
REDIS_HOST=$REDIS
KAFKA_BOOTSTRAP_SERVERS=kafka:9092
JWT_SECRET=$JWT_SECRET
ADMIN_EMAIL=admin@dashcart.local
ADMIN_PASSWORD=$ADMIN_PASSWORD
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError
EOF
$SSH 'bash -s' < "$HERE/start-on-server.sh"

log "waiting for http://$PUBLIC_IP/actuator/health"
for _ in $(seq 1 60); do
  if curl -sf -m 5 "http://$PUBLIC_IP/actuator/health" > /dev/null; then
    curl -s "http://$PUBLIC_IP/actuator/health"; echo
    log "DashCart is up at http://$PUBLIC_IP"
    exit 0
  fi
  sleep 5
done
log "API didn't become healthy; last log lines:"
$SSH 'docker logs --tail 40 dashcart-app'
exit 1
