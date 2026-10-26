package com.dashcart.config;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// Drops cached catalogue reads after a change, at the right moment:
//   - after the transaction commits, so a request racing the change can't re-cache the old rows, and
//   - with Cache.invalidate(), which removes entries immediately. @CacheEvict(allEntries = true) uses
//     Cache.clear(), which Spring allows to happen later; a test caught the next listing still being
//     served from the cache.
@Component
public class CatalogCache {

    private final CacheManager cacheManager;

    public CatalogCache(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void invalidateListingsAfterCommit() {
        afterCommit(CacheConfig.STORE_PRODUCTS);
    }

    public void invalidateCategoriesAfterCommit() {
        afterCommit(CacheConfig.CATEGORIES);
    }

    private void afterCommit(String cacheName) {
        Runnable invalidate = () -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.invalidate();
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }
}
