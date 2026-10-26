package com.dashcart.catalog;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dashcart.catalog.CatalogDtos.StoreProductView;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // Products a store stocks, with its stock level, in one query (no N+1).
    // categoryId = null means all categories; search = "" means no name filter.
    @Query(value = """
            select new com.dashcart.catalog.CatalogDtos$StoreProductView(
                p.id, p.name, p.description, p.unit, p.mrp, p.price, p.imageUrl, c.id, c.name, i.availableQty)
            from StoreInventory i
            join Product p on p.id = i.id.productId
            join Category c on c.id = p.categoryId
            where i.id.storeId = :storeId
              and p.active = true
              and (:categoryId is null or p.categoryId = :categoryId)
              and lower(p.name) like lower(concat('%', :search, '%'))
            order by p.name, p.id
            """,
            countQuery = """
            select count(i)
            from StoreInventory i
            join Product p on p.id = i.id.productId
            where i.id.storeId = :storeId
              and p.active = true
              and (:categoryId is null or p.categoryId = :categoryId)
              and lower(p.name) like lower(concat('%', :search, '%'))
            """)
    Page<StoreProductView> findStoreProducts(@Param("storeId") Long storeId, @Param("categoryId") Long categoryId,
            @Param("search") String search, Pageable pageable);

    @Query("""
            select new com.dashcart.catalog.CatalogDtos$StoreProductView(
                p.id, p.name, p.description, p.unit, p.mrp, p.price, p.imageUrl, c.id, c.name, i.availableQty)
            from StoreInventory i
            join Product p on p.id = i.id.productId
            join Category c on c.id = p.categoryId
            where i.id.storeId = :storeId and p.id = :productId and p.active = true
            """)
    Optional<StoreProductView> findStoreProduct(@Param("storeId") Long storeId, @Param("productId") Long productId);
}
