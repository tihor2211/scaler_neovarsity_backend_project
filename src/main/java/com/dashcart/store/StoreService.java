package com.dashcart.store;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dashcart.common.ApiException;
import com.dashcart.store.StoreDtos.CreateStoreRequest;
import com.dashcart.store.StoreDtos.ServiceabilityResponse;
import com.dashcart.store.StoreDtos.StoreResponse;

@Service
public class StoreService {

    private final StoreRepository stores;
    private final ServiceablePincodeRepository pincodes;

    public StoreService(StoreRepository stores, ServiceablePincodeRepository pincodes) {
        this.stores = stores;
        this.pincodes = pincodes;
    }

    @Transactional
    public StoreResponse create(CreateStoreRequest request) {
        Store store = stores.save(new Store(request.name().trim(), request.address().trim(),
                request.latitude(), request.longitude()));
        return StoreResponse.from(store, List.of());
    }

    // A pincode already served by another store is moved to this one.
    @Transactional
    public StoreResponse assignPincodes(Long storeId, List<String> codes) {
        Store store = getActiveOrInactive(storeId);
        for (String code : codes) {
            ServiceablePincode pincode = pincodes.findById(code).orElseGet(() -> new ServiceablePincode(code, storeId));
            pincode.setStoreId(storeId);
            pincodes.save(pincode);
        }
        return StoreResponse.from(store, pincodesOf(storeId));
    }

    @Transactional
    public StoreResponse setActive(Long storeId, boolean active) {
        Store store = getActiveOrInactive(storeId);
        store.setActive(active);
        return StoreResponse.from(store, pincodesOf(storeId));
    }

    @Transactional(readOnly = true)
    public List<StoreResponse> list() {
        return stores.findAll().stream()
                .map(store -> StoreResponse.from(store, pincodesOf(store.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceabilityResponse serviceability(String pincode) {
        return pincodes.findById(pincode)
                .flatMap(p -> stores.findById(p.getStoreId()))
                .filter(Store::isActive)
                .map(store -> new ServiceabilityResponse(pincode, store.getId(), store.getName()))
                .orElseThrow(() -> ApiException.notFound("We don't deliver to " + pincode + " yet"));
    }

    // Used by cart and checkout: the store must exist and be taking orders.
    @Transactional(readOnly = true)
    public Store getActive(Long storeId) {
        Store store = getActiveOrInactive(storeId);
        if (!store.isActive()) {
            throw ApiException.badRequest("Store " + storeId + " is not taking orders right now");
        }
        return store;
    }

    private Store getActiveOrInactive(Long storeId) {
        return stores.findById(storeId).orElseThrow(() -> ApiException.notFound("Store " + storeId + " not found"));
    }

    private List<String> pincodesOf(Long storeId) {
        return pincodes.findByStoreIdOrderByPincode(storeId).stream().map(ServiceablePincode::getPincode).toList();
    }
}
