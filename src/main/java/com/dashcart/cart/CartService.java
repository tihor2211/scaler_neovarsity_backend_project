package com.dashcart.cart;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dashcart.cart.CartDtos.CartItemResponse;
import com.dashcart.cart.CartDtos.CartLine;
import com.dashcart.cart.CartDtos.CartResponse;
import com.dashcart.catalog.ProductRepository;
import com.dashcart.common.ApiException;
import com.dashcart.store.StoreService;

@Service
public class CartService {

    private final CartRepository carts;
    private final CartItemRepository items;
    private final ProductRepository products;
    private final StoreService storeService;
    private final int maxQuantityPerItem;

    public CartService(CartRepository carts, CartItemRepository items, ProductRepository products,
            StoreService storeService, @Value("${dashcart.cart.max-quantity-per-item:10}") int maxQuantityPerItem) {
        this.carts = carts;
        this.items = items;
        this.products = products;
        this.storeService = storeService;
        this.maxQuantityPerItem = maxQuantityPerItem;
    }

    @Transactional(readOnly = true)
    public CartResponse get(Long userId) {
        return carts.findByUserId(userId).map(this::toResponse).orElse(CartResponse.empty());
    }

    // Sets a product's quantity (0 removes it). A cart holds items from one store only; adding from another
    // store is refused until the cart is cleared, the same way delivery apps do it.
    @Transactional
    public CartResponse setItem(Long userId, Long productId, Long storeId, int quantity) {
        if (quantity > maxQuantityPerItem) {
            throw ApiException.badRequest("You can add at most " + maxQuantityPerItem + " of an item");
        }
        Optional<Cart> existing = carts.findByUserId(userId);
        if (quantity == 0) {
            existing.ifPresent(cart -> items.findById(new CartItemId(cart.getId(), productId)).ifPresent(items::delete));
            return existing.map(this::toResponse).orElse(CartResponse.empty());
        }

        storeService.getActive(storeId);
        var product = products.findStoreProduct(storeId, productId)
                .orElseThrow(() -> ApiException.notFound("Product " + productId + " isn't sold at store " + storeId));
        if (product.availableQty() < quantity) {
            throw ApiException.conflict(product.availableQty() == 0
                    ? product.name() + " is out of stock"
                    : "Only " + product.availableQty() + " of " + product.name() + " left");
        }

        Cart cart = existing.orElseGet(() -> carts.save(new Cart(userId, storeId)));
        if (!cart.getStoreId().equals(storeId)) {
            if (!items.findLines(cart.getId()).isEmpty()) {
                throw ApiException.conflict("Your cart has items from another store. Clear it to shop here.");
            }
            cart.setStoreId(storeId);
        }
        var id = new CartItemId(cart.getId(), productId);
        items.findById(id).ifPresentOrElse(
                item -> item.setQuantity(quantity),
                () -> items.save(new CartItem(id, quantity)));
        items.flush();
        return toResponse(cart);
    }

    @Transactional
    public void clear(Long userId) {
        carts.findByUserId(userId).ifPresent(cart -> items.deleteAllInCart(cart.getId()));
    }

    private CartResponse toResponse(Cart cart) {
        List<CartLine> lines = items.findLines(cart.getId());
        if (lines.isEmpty()) {
            return CartResponse.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal savings = BigDecimal.ZERO;
        int count = 0;
        List<CartItemResponse> responses = new ArrayList<>();
        for (CartLine line : lines) {
            total = total.add(line.lineTotal());
            savings = savings.add(line.mrp().subtract(line.price()).multiply(BigDecimal.valueOf(line.quantity())));
            count += line.quantity();
            responses.add(new CartItemResponse(line.productId(), line.name(), line.unit(), line.mrp(), line.price(),
                    line.quantity(), line.lineTotal(), line.active() && line.availableQty() >= line.quantity()));
        }
        return new CartResponse(cart.getStoreId(), responses, count, total, savings);
    }
}
