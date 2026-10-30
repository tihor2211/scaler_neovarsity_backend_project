#!/usr/bin/env bash
# Deletes everything up.sh created: EC2 instance, RDS instance (no final snapshot), ElastiCache, subnet
# groups, security groups, subnets, route tables, internet gateway, VPC and the key pair. Resources are
# found by name and by the Project=dashcart tag, so it works even without local state. Safe to re-run.
set -uo pipefail
. "$(dirname "$0")/config.sh"

log "EC2 instances"
ids=$(aws ec2 describe-instances --filters "Name=tag:Project,Values=$PROJECT" \
  "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].InstanceId' --output text)
if [ -n "$ids" ]; then
  aws ec2 terminate-instances --instance-ids $ids > /dev/null
  aws ec2 wait instance-terminated --instance-ids $ids
fi

log "RDS $DB_ID"
if aws rds describe-db-instances --db-instance-identifier "$DB_ID" > /dev/null 2>&1; then
  aws rds delete-db-instance --db-instance-identifier "$DB_ID" --skip-final-snapshot \
    --delete-automated-backups > /dev/null 2>&1 || true
fi

log "ElastiCache $CACHE_ID"
if aws elasticache describe-replication-groups --replication-group-id "$CACHE_ID" > /dev/null 2>&1; then
  aws elasticache delete-replication-group --replication-group-id "$CACHE_ID" > /dev/null 2>&1 || true
fi

log "waiting for RDS and ElastiCache to be deleted"
aws rds wait db-instance-deleted --db-instance-identifier "$DB_ID" 2> /dev/null || true
aws elasticache wait replication-group-deleted --replication-group-id "$CACHE_ID" 2> /dev/null || true

log "subnet groups and key pair"
aws rds delete-db-subnet-group --db-subnet-group-name "$PROJECT-db-subnets" > /dev/null 2>&1 || true
aws elasticache delete-cache-subnet-group --cache-subnet-group-name "$PROJECT-cache-subnets" > /dev/null 2>&1 || true
aws ec2 delete-key-pair --key-name "$KEY_NAME" > /dev/null 2>&1 || true
rm -f "$LOCAL/$KEY_NAME.pem"

for vpc in $(aws ec2 describe-vpcs --filters "Name=tag:Project,Values=$PROJECT" --query 'Vpcs[].VpcId' --output text); do
  log "VPC $vpc"
  for sg in $(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$vpc" \
      --query 'SecurityGroups[?GroupName!=`default`].GroupId' --output text); do
    # Rules referencing other groups must go first, or the groups can't be deleted.
    perms=$(aws ec2 describe-security-groups --group-ids "$sg" --query 'SecurityGroups[0].IpPermissions' --output json)
    [ "$perms" != "[]" ] && aws ec2 revoke-security-group-ingress --group-id "$sg" --ip-permissions "$perms" > /dev/null 2>&1
  done
  for sg in $(aws ec2 describe-security-groups --filters "Name=vpc-id,Values=$vpc" \
      --query 'SecurityGroups[?GroupName!=`default`].GroupId' --output text); do
    aws ec2 delete-security-group --group-id "$sg" > /dev/null 2>&1 || log "  could not delete $sg yet"
  done
  for subnet in $(aws ec2 describe-subnets --filters "Name=vpc-id,Values=$vpc" --query 'Subnets[].SubnetId' --output text); do
    aws ec2 delete-subnet --subnet-id "$subnet" > /dev/null 2>&1 || log "  could not delete $subnet"
  done
  for rt in $(aws ec2 describe-route-tables --filters "Name=vpc-id,Values=$vpc" \
      --query 'RouteTables[?Associations[0].Main!=`true`].RouteTableId' --output text); do
    aws ec2 delete-route-table --route-table-id "$rt" > /dev/null 2>&1 || true
  done
  for igw in $(aws ec2 describe-internet-gateways --filters "Name=attachment.vpc-id,Values=$vpc" \
      --query 'InternetGateways[].InternetGatewayId' --output text); do
    aws ec2 detach-internet-gateway --internet-gateway-id "$igw" --vpc-id "$vpc" > /dev/null
    aws ec2 delete-internet-gateway --internet-gateway-id "$igw" > /dev/null
  done
  aws ec2 delete-vpc --vpc-id "$vpc" > /dev/null 2>&1 || log "  could not delete $vpc"
done

rm -f "$STATE"
log "remaining:"
"$(dirname "$0")/status.sh"
