package com.dashcart.store;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Pincode -> the one store that delivers there.
@Entity
@Table(name = "serviceable_pincodes")
public class ServiceablePincode {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 6)
    private String pincode;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    protected ServiceablePincode() {
    }

    public ServiceablePincode(String pincode, Long storeId) {
        this.pincode = pincode;
        this.storeId = storeId;
    }

    public String getPincode() {
        return pincode;
    }

    public Long getStoreId() {
        return storeId;
    }

    public void setStoreId(Long storeId) {
        this.storeId = storeId;
    }
}
