package com.dashcart.inventory;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dashcart.catalog.CatalogService;
import com.dashcart.common.ApiException;
import com.dashcart.inventory.InventoryDtos.InventoryRow;
import com.dashcart.inventory.InventoryDtos.StockLine;
import com.dashcart.inventory.reservation.RedisStockStore;
import com.dashcart.store.StoreRepository;

@Service
public class InventoryService {

    private final StoreInventoryRepository inventory;
    private final StoreRepository stores;
    private final CatalogService catalogService;
    private final RedisStockStore redisStock;

    public InventoryService(StoreInventoryRepository inventory, StoreRepository stores,
            CatalogService catalogService, RedisStockStore redisStock) {
        this.inventory = inventory;
        this.stores = stores;
        this.catalogService = catalogService;
        this.redisStock = redisStock;
    }

    @Transactional
    public List<InventoryRow> setStock(Long storeId, List<StockLine> lines) {
        if (!stores.existsById(storeId)) {
            throw ApiException.notFound("Store " + storeId + " not found");
        }
        for (StockLine line : lines) {
            catalogService.getProduct(line.productId());
            var id = new StoreInventoryId(storeId, line.productId());
            inventory.findById(id).ifPresentOrElse(
                    row -> row.setAvailableQty(line.availableQty()),
                    () -> inventory.save(new StoreInventory(id, line.availableQty())));
        }
        inventory.flush();
        // The Redis copy (REDIS checkout strategy) is now stale: drop it once this commits; the next
        // checkout reloads the counted value from the database.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                lines.forEach(line -> redisStock.delete(storeId, line.productId()));
            }
        });
        return inventory.findRowsByStore(storeId);
    }

    @Transactional(readOnly = true)
    public List<InventoryRow> list(Long storeId) {
        if (!stores.existsById(storeId)) {
            throw ApiException.notFound("Store " + storeId + " not found");
        }
        return inventory.findRowsByStore(storeId);
    }
}
