Connection pool size: flash-sale rush, CONDITIONAL_UPDATE, lock-late code. 500 buyers, 100 units.
App started fresh per pool size, 2 unrecorded warm-up rushes, then 3 measured runs (median shown).
Connection wait is HikariCP's acquire time over the whole experiment, setup traffic included.

| Pool size | Median ms | p95 ms | All served ms | Oversold (each run) | Mean connection wait ms | Max connection wait ms |
|---|---|---|---|---|---|---|
| 10 | 412 | 547 | 564 | 0, 0, 0 | 44.6 | 842 |
| 30 | 479 | 553 | 664 | 0, 0, 0 | 36.7 | 607 |
