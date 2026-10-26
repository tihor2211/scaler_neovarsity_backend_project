Rush: 500 buyers, 100 units, 3 runs per strategy (median shown; oversold shows every run)

| Strategy | Orders created | Rejected (409) | Errors | Oversold (each run) | Median ms | p95 ms | p99 ms | All served in ms | Orders/s |
|---|---|---|---|---|---|---|---|---|---|
| NAIVE | 500 | 0 | 0 | 400, 400, 400 | 797 | 1087 | 1104 | 1149 | 435 |
| OPTIMISTIC | 100 | 400 | 0 | 0, 0, 0 | 1193 | 1309 | 1316 | 1335 | 75 |
| PESSIMISTIC | 100 | 400 | 0 | 0, 0, 0 | 394 | 485 | 493 | 519 | 193 |
| CONDITIONAL_UPDATE | 100 | 400 | 0 | 0, 0, 0 | 340 | 462 | 472 | 494 | 202 |
| REDIS | 100 | 400 | 0 | 0, 0, 0 | 419 | 534 | 566 | 576 | 174 |
