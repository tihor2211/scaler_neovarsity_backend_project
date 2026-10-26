package com.dashcart.inventory.reservation;

import org.springframework.http.HttpStatus;

import com.dashcart.common.ApiException;

public class InsufficientStockException extends ApiException {

    public InsufficientStockException(StockRequest item) {
        super(HttpStatus.CONFLICT, "Not enough " + item.productName() + " left for this order");
    }
}
