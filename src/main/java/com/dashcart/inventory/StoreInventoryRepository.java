package com.dashcart.inventory;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dashcart.inventory.InventoryDtos.InventoryRow;

public interface StoreInventoryRepository extends JpaRepository<StoreInventory, StoreInventoryId> {

    @Query("""
            select new com.dashcart.inventory.InventoryDtos$InventoryRow(
                p.id, p.name, p.unit, i.availableQty, i.reservedQty, i.version)
            from StoreInventory i
            join Product p on p.id = i.id.productId
            where i.id.storeId = :storeId
            order by p.name
            """)
    List<InventoryRow> findRowsByStore(@Param("storeId") Long storeId);
}
