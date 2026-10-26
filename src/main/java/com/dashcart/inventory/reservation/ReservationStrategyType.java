package com.dashcart.inventory.reservation;

// The ways checkout can reserve stock. Compared against each other in the load tests.
public enum ReservationStrategyType {
    // Read, check in Java, write the computed value. Two buyers can both read "1 left": oversells.
    NAIVE,
    // Read value + version, write only if the version is unchanged, retry on conflict.
    OPTIMISTIC,
    // SELECT ... FOR UPDATE locks the row until commit; buyers queue on the lock.
    PESSIMISTIC,
    // One UPDATE ... WHERE available_qty >= ?; the database checks and decrements atomically.
    CONDITIONAL_UPDATE,
    // A Redis Lua script checks and decrements first; only winners touch the database.
    REDIS
}
