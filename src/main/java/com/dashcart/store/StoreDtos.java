package com.dashcart.store;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class StoreDtos {

    public static final String PINCODE_REGEX = "^[1-9]\\d{5}$";

    private StoreDtos() {
    }

    public record CreateStoreRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 255) String address,
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {
    }

    public record AssignPincodesRequest(
            @NotEmpty List<@Pattern(regexp = PINCODE_REGEX, message = "must be a 6-digit pincode") String> pincodes) {
    }

    public record StoreResponse(Long id, String name, String address, BigDecimal latitude, BigDecimal longitude,
            boolean active, List<String> pincodes) {

        static StoreResponse from(Store store, List<String> pincodes) {
            return new StoreResponse(store.getId(), store.getName(), store.getAddress(), store.getLatitude(),
                    store.getLongitude(), store.isActive(), pincodes);
        }
    }

    public record ServiceabilityResponse(String pincode, Long storeId, String storeName) {
    }
}
