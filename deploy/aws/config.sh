# Shared settings for up.sh / down.sh. Every resource is named dashcart-* and tagged Project=dashcart,
# so down.sh can find and delete all of it even if the local state is lost.
export AWS_REGION=${AWS_REGION:-ap-south-1}
export AWS_PAGER=""
PROJECT=dashcart
TAG_SPEC="Key=Project,Value=$PROJECT"

VPC_CIDR=10.20.0.0/16
PUBLIC_CIDR=10.20.1.0/24       # EC2 instance
PRIVATE_A_CIDR=10.20.11.0/24   # RDS, ElastiCache
PRIVATE_B_CIDR=10.20.12.0/24   # RDS needs subnets in two availability zones
AZ_A=${AWS_REGION}a
AZ_B=${AWS_REGION}b

INSTANCE_TYPE=t4g.small        # Graviton (arm64), 2 GiB: the app and the Kafka broker
DB_ID=dashcart-db
DB_CLASS=db.t4g.micro
CACHE_ID=dashcart-cache
CACHE_NODE=cache.t4g.micro
KEY_NAME=dashcart-key

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LOCAL=$HERE/.local              # gitignored: SSH key, generated secrets, resource ids
STATE=$LOCAL/state.env
mkdir -p "$LOCAL"
chmod 700 "$LOCAL"

save() { echo "$1=$2" >> "$STATE"; export "$1=$2"; }
[ -f "$STATE" ] && . "$STATE"
log() { echo "[$(date +%H:%M:%S)] $*"; }
