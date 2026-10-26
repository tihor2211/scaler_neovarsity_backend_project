package com.dashcart.inventory;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.dashcart.inventory.InventoryDtos.InventoryRow;
import com.dashcart.inventory.InventoryDtos.SetStockRequest;

import jakarta.validation.Valid;

@RestController
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PutMapping("/admin/stores/{storeId}/inventory")
    public List<InventoryRow> setStock(@PathVariable Long storeId, @Valid @RequestBody SetStockRequest request) {
        return inventoryService.setStock(storeId, request.items());
    }

    @GetMapping("/admin/stores/{storeId}/inventory")
    public List<InventoryRow> list(@PathVariable Long storeId) {
        return inventoryService.list(storeId);
    }
}
