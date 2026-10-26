package com.dashcart.inventory;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dashcart.catalog.CatalogService;
import com.dashcart.common.ApiException;
import com.dashcart.inventory.InventoryDtos.InventoryRow;
import com.dashcart.inventory.InventoryDtos.StockLine;
import com.dashcart.store.StoreRepository;

@Service
public class InventoryService {

    private final StoreInventoryRepository inventory;
    private final StoreRepository stores;
    private final CatalogService catalogService;

    public InventoryService(StoreInventoryRepository inventory, StoreRepository stores,
            CatalogService catalogService) {
        this.inventory = inventory;
        this.stores = stores;
        this.catalogService = catalogService;
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
