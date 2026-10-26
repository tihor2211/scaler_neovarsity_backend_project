package com.dashcart.cart;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dashcart.cart.CartDtos.CartLine;

public interface CartItemRepository extends JpaRepository<CartItem, CartItemId> {

    // Cart lines with current price and the store's current stock, in one query.
    @Query("""
            select new com.dashcart.cart.CartDtos$CartLine(
                p.id, p.name, p.unit, p.mrp, p.price, ci.quantity, coalesce(i.availableQty, 0), p.active)
            from CartItem ci
            join Cart c on c.id = ci.id.cartId
            join Product p on p.id = ci.id.productId
            left join StoreInventory i on i.id.storeId = c.storeId and i.id.productId = p.id
            where c.id = :cartId
            order by ci.addedAt, p.id
            """)
    List<CartLine> findLines(@Param("cartId") Long cartId);

    @Modifying
    @Query("delete from CartItem ci where ci.id.cartId = :cartId")
    int deleteAllInCart(@Param("cartId") Long cartId);
}
