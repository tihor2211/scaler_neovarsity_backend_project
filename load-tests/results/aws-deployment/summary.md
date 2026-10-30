Flash-sale rush against the AWS deployment (30 October 2026): EC2 t4g.small (app + Kafka), RDS MySQL 8.4
db.t4g.micro, ElastiCache Valkey 9.1 cache.t4g.micro, Mumbai (ap-south-1). k6 ran on the developer's laptop in
Bengaluru, over the internet. CONDITIONAL_UPDATE, 100 buyers, 25 units, one run.

| Orders created | Rejected (409) | Errors | Oversold | Final available / reserved | Median ms | p95 ms | All served ms |
|---|---|---|---|---|---|---|---|
| 25 | 75 | 0 | 0 | 0 / 25 | 3,950 | 5,082 | 5,228 |

The point of this run is correctness on the real infrastructure, not speed: the instance used the "standard" CPU
credit mode (capped at its 20% baseline, about 0.4 vCPU, to avoid any surplus charges), and every request crossed
the internet. Laptop benchmarks are in the other folders.
