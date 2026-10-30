#!/usr/bin/env bash
# Creates the AWS infrastructure for DashCart in its own VPC (ids saved in .local/state.env):
#   VPC 10.20.0.0/16, internet gateway, one public subnet (EC2) and two private subnets (RDS, ElastiCache)
#   security groups: app (80 from anywhere, 22 from this machine), db (3306 from app), cache (6379 from app)
#   RDS MySQL 8.4 (db.t4g.micro, 20 GB gp3, single-AZ, private), ElastiCache Valkey (cache.t4g.micro, private)
#   EC2 t4g.small (Amazon Linux 2023, arm64) with Docker, in the public subnet
# Then run deploy-app.sh. Delete everything with down.sh.
set -euo pipefail
. "$(dirname "$0")/config.sh"

tags() { echo "ResourceType=$1,Tags=[{Key=Project,Value=$PROJECT},{Key=Name,Value=$PROJECT-$2}]"; }

# Secrets are generated once and kept in .local (gitignored, mode 600).
SECRETS=$LOCAL/secrets.env
if [ ! -f "$SECRETS" ]; then
  umask 077
  {
    echo "DB_PASSWORD=$(openssl rand -hex 16)"
    echo "JWT_SECRET=$(openssl rand -base64 32)"
    echo "ADMIN_PASSWORD=$(openssl rand -hex 12)"
  } > "$SECRETS"
fi
. "$SECRETS"

if [ -z "${VPC_ID:-}" ]; then
  log "VPC and subnets"
  save VPC_ID "$(aws ec2 create-vpc --cidr-block $VPC_CIDR --tag-specifications "$(tags vpc vpc)" --query Vpc.VpcId --output text)"
  aws ec2 modify-vpc-attribute --vpc-id "$VPC_ID" --enable-dns-hostnames '{"Value":true}'
  save IGW_ID "$(aws ec2 create-internet-gateway --tag-specifications "$(tags internet-gateway igw)" --query InternetGateway.InternetGatewayId --output text)"
  aws ec2 attach-internet-gateway --internet-gateway-id "$IGW_ID" --vpc-id "$VPC_ID"
  save PUBLIC_SUBNET "$(aws ec2 create-subnet --vpc-id "$VPC_ID" --cidr-block $PUBLIC_CIDR --availability-zone $AZ_A --tag-specifications "$(tags subnet public)" --query Subnet.SubnetId --output text)"
  save PRIVATE_A "$(aws ec2 create-subnet --vpc-id "$VPC_ID" --cidr-block $PRIVATE_A_CIDR --availability-zone $AZ_A --tag-specifications "$(tags subnet private-a)" --query Subnet.SubnetId --output text)"
  save PRIVATE_B "$(aws ec2 create-subnet --vpc-id "$VPC_ID" --cidr-block $PRIVATE_B_CIDR --availability-zone $AZ_B --tag-specifications "$(tags subnet private-b)" --query Subnet.SubnetId --output text)"
  save PUBLIC_RT "$(aws ec2 create-route-table --vpc-id "$VPC_ID" --tag-specifications "$(tags route-table public)" --query RouteTable.RouteTableId --output text)"
  aws ec2 create-route --route-table-id "$PUBLIC_RT" --destination-cidr-block 0.0.0.0/0 --gateway-id "$IGW_ID" > /dev/null
  aws ec2 associate-route-table --route-table-id "$PUBLIC_RT" --subnet-id "$PUBLIC_SUBNET" > /dev/null
fi

if [ -z "${APP_SG:-}" ]; then
  log "security groups"
  MY_IP=$(curl -s https://checkip.amazonaws.com)
  save APP_SG "$(aws ec2 create-security-group --group-name $PROJECT-app-sg --description 'DashCart API: HTTP from anywhere, SSH from the developer' --vpc-id "$VPC_ID" --tag-specifications "$(tags security-group app-sg)" --query GroupId --output text)"
  save DB_SG "$(aws ec2 create-security-group --group-name $PROJECT-db-sg --description 'DashCart MySQL: only from the app' --vpc-id "$VPC_ID" --tag-specifications "$(tags security-group db-sg)" --query GroupId --output text)"
  save CACHE_SG "$(aws ec2 create-security-group --group-name $PROJECT-cache-sg --description 'DashCart Valkey: only from the app' --vpc-id "$VPC_ID" --tag-specifications "$(tags security-group cache-sg)" --query GroupId --output text)"
  aws ec2 authorize-security-group-ingress --group-id "$APP_SG" --protocol tcp --port 80 --cidr 0.0.0.0/0 > /dev/null
  aws ec2 authorize-security-group-ingress --group-id "$APP_SG" --protocol tcp --port 22 --cidr "$MY_IP/32" > /dev/null
  aws ec2 authorize-security-group-ingress --group-id "$DB_SG" --protocol tcp --port 3306 --source-group "$APP_SG" > /dev/null
  aws ec2 authorize-security-group-ingress --group-id "$CACHE_SG" --protocol tcp --port 6379 --source-group "$APP_SG" > /dev/null
fi

if ! aws rds describe-db-instances --db-instance-identifier "$DB_ID" > /dev/null 2>&1; then
  log "RDS MySQL (takes several minutes; continuing meanwhile)"
  aws rds create-db-subnet-group --db-subnet-group-name $PROJECT-db-subnets --db-subnet-group-description 'DashCart private subnets' \
    --subnet-ids "$PRIVATE_A" "$PRIVATE_B" --tags "$TAG_SPEC" > /dev/null
  aws rds create-db-instance --db-instance-identifier "$DB_ID" --engine mysql --db-instance-class $DB_CLASS \
    --allocated-storage 20 --storage-type gp3 --master-username dashcart_admin --master-user-password "$DB_PASSWORD" \
    --db-name dashcart --vpc-security-group-ids "$DB_SG" --db-subnet-group-name $PROJECT-db-subnets \
    --no-publicly-accessible --backup-retention-period 0 --no-multi-az --no-deletion-protection \
    --tags "$TAG_SPEC" > /dev/null
fi

if ! aws elasticache describe-replication-groups --replication-group-id "$CACHE_ID" > /dev/null 2>&1; then
  log "ElastiCache Valkey (takes several minutes; continuing meanwhile)"
  aws elasticache create-cache-subnet-group --cache-subnet-group-name $PROJECT-cache-subnets \
    --cache-subnet-group-description 'DashCart private subnets' --subnet-ids "$PRIVATE_A" "$PRIVATE_B" > /dev/null
  aws elasticache create-replication-group --replication-group-id "$CACHE_ID" \
    --replication-group-description 'DashCart cache, logout deny-list and stock counters' \
    --engine valkey --cache-node-type $CACHE_NODE --num-cache-clusters 1 \
    --cache-subnet-group-name $PROJECT-cache-subnets --security-group-ids "$CACHE_SG" \
    --no-transit-encryption-enabled --tags "$TAG_SPEC" > /dev/null
fi

if [ -z "${INSTANCE_ID:-}" ]; then
  log "EC2 $INSTANCE_TYPE"
  aws ec2 create-key-pair --key-name $KEY_NAME --key-type ed25519 --tag-specifications "$(tags key-pair key)" \
    --query KeyMaterial --output text > "$LOCAL/$KEY_NAME.pem"
  chmod 600 "$LOCAL/$KEY_NAME.pem"
  AMI=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64 --query Parameter.Value --output text)
  save INSTANCE_ID "$(aws ec2 run-instances --image-id "$AMI" --instance-type $INSTANCE_TYPE --key-name $KEY_NAME \
    --network-interfaces "DeviceIndex=0,SubnetId=$PUBLIC_SUBNET,Groups=$APP_SG,AssociatePublicIpAddress=true" \
    --user-data "file://$HERE/user-data.sh" --credit-specification CpuCredits=standard \
    --metadata-options HttpTokens=required \
    --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=10,VolumeType=gp3,DeleteOnTermination=true}' \
    --tag-specifications "$(tags instance app)" "$(tags volume app)" \
    --query 'Instances[0].InstanceId' --output text)"
  aws ec2 wait instance-running --instance-ids "$INSTANCE_ID"
fi
save PUBLIC_IP "$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
log "EC2 $INSTANCE_ID running at $PUBLIC_IP"
log "infrastructure requested; next: ./deploy-app.sh"
