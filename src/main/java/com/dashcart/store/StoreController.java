package com.dashcart.store;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dashcart.store.StoreDtos.AssignPincodesRequest;
import com.dashcart.store.StoreDtos.CreateStoreRequest;
import com.dashcart.store.StoreDtos.ServiceabilityResponse;
import com.dashcart.store.StoreDtos.StoreResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

@RestController
public class StoreController {

    private final StoreService storeService;

    public StoreController(StoreService storeService) {
        this.storeService = storeService;
    }

    // Public: the first call the app makes after the customer types a pincode.
    @GetMapping("/stores/serviceable")
    public ServiceabilityResponse serviceable(
            @RequestParam @Pattern(regexp = StoreDtos.PINCODE_REGEX, message = "must be a 6-digit pincode") String pincode) {
        return storeService.serviceability(pincode);
    }

    @PostMapping("/admin/stores")
    @ResponseStatus(HttpStatus.CREATED)
    public StoreResponse create(@Valid @RequestBody CreateStoreRequest request) {
        return storeService.create(request);
    }

    @GetMapping("/admin/stores")
    public List<StoreResponse> list() {
        return storeService.list();
    }

    @PutMapping("/admin/stores/{storeId}/pincodes")
    public StoreResponse assignPincodes(@PathVariable Long storeId, @Valid @RequestBody AssignPincodesRequest request) {
        return storeService.assignPincodes(storeId, request.pincodes());
    }

    @PutMapping("/admin/stores/{storeId}/active")
    public StoreResponse setActive(@PathVariable Long storeId, @RequestParam boolean value) {
        return storeService.setActive(storeId, value);
    }
}
