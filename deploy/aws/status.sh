#!/usr/bin/env bash
# Lists every DashCart resource that exists (and costs money) right now. Empty output means nothing left.
set -uo pipefail
. "$(dirname "$0")/config.sh"

aws ec2 describe-instances --filters "Name=tag:Project,Values=$PROJECT" \
  "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].["EC2", InstanceId, InstanceType, State.Name, PublicIpAddress]' --output text
aws rds describe-db-instances --query "DBInstances[?DBInstanceIdentifier=='$DB_ID'].[\`RDS\`, DBInstanceIdentifier, DBInstanceClass, DBInstanceStatus]" --output text
aws elasticache describe-replication-groups --query "ReplicationGroups[?ReplicationGroupId=='$CACHE_ID'].[\`ElastiCache\`, ReplicationGroupId, CacheNodeType, Status]" --output text
aws ec2 describe-vpcs --filters "Name=tag:Project,Values=$PROJECT" --query 'Vpcs[].["VPC", VpcId, CidrBlock]' --output text
aws ec2 describe-key-pairs --query "KeyPairs[?KeyName=='$KEY_NAME'].[\`KeyPair\`, KeyName]" --output text
