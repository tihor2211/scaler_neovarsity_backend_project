Rush: 500 buyers, 100 units, 3 runs per strategy (median shown; oversold shows every run)

| Strategy | Orders created | Rejected (409) | Errors | Oversold (each run) | Median ms | p95 ms | p99 ms | All served in ms | Orders/s |
|---|---|---|---|---|---|---|---|---|---|
| NAIVE | 500 | 0 | 0 | 400, 400, 400 | 1052 | 1594 | 1618 | 1653 | 303 |
| OPTIMISTIC | 100 | 400 | 0 | 0, 0, 0 | 991 | 1086 | 1109 | 1131 | 88 |
| PESSIMISTIC | 100 | 400 | 0 | 0, 0, 0 | 472 | 594 | 603 | 622 | 161 |
| CONDITIONAL_UPDATE | 100 | 400 | 0 | 0, 0, 0 | 406 | 524 | 540 | 568 | 176 |
| REDIS | 100 | 400 | 0 | 0, 0, 0 | 470 | 556 | 580 | 589 | 170 |
