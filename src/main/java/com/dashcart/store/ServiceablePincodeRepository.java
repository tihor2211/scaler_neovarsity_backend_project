package com.dashcart.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceablePincodeRepository extends JpaRepository<ServiceablePincode, String> {

    List<ServiceablePincode> findByStoreIdOrderByPincode(Long storeId);
}
