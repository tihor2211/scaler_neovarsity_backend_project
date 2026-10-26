package com.dashcart.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

// Read-through caching of public catalogue reads in Redis (cache-aside via Spring's @Cacheable).
// spring.cache.type=redis turns it on, =none turns it off (used for the before/after benchmark).
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String STORE_PRODUCTS = "storeProducts";
    public static final String CATEGORIES = "categories";
}
