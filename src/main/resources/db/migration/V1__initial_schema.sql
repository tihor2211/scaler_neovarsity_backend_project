-- DashCart initial schema.
-- Money is DECIMAL(10,2) in rupees; Razorpay amounts are in paise (BIGINT).
-- Timestamps are UTC.

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    phone         VARCHAR(15),
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
);

CREATE TABLE stores (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    address    VARCHAR(255) NOT NULL,
    latitude   DECIMAL(9,6) NOT NULL,
    longitude  DECIMAL(9,6) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);

-- Each pincode is served by exactly one dark store.
CREATE TABLE serviceable_pincodes (
    pincode  CHAR(6) NOT NULL,
    store_id BIGINT  NOT NULL,
    PRIMARY KEY (pincode),
    KEY idx_pincodes_store (store_id),
    CONSTRAINT fk_pincodes_store FOREIGN KEY (store_id) REFERENCES stores (id)
);

CREATE TABLE categories (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_categories_name (name)
);

CREATE TABLE products (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    category_id BIGINT        NOT NULL,
    name        VARCHAR(200)  NOT NULL,
    description TEXT,
    unit        VARCHAR(30)   NOT NULL,
    mrp         DECIMAL(10,2) NOT NULL,
    price       DECIMAL(10,2) NOT NULL,
    image_url   VARCHAR(500),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_products_category (category_id),
    CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT chk_products_price CHECK (price > 0 AND price <= mrp)
);

-- Stock of one product in one store.
-- available_qty: can be sold now. reserved_qty: held by orders waiting for payment.
-- version: used by the optimistic-locking checkout strategy.
CREATE TABLE store_inventory (
    store_id      BIGINT      NOT NULL,
    product_id    BIGINT      NOT NULL,
    available_qty INT         NOT NULL,
    reserved_qty  INT         NOT NULL DEFAULT 0,
    version       BIGINT      NOT NULL DEFAULT 0,
    updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (store_id, product_id),
    KEY idx_inventory_product (product_id),
    CONSTRAINT fk_inventory_store FOREIGN KEY (store_id) REFERENCES stores (id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_inventory_qty CHECK (available_qty >= 0 AND reserved_qty >= 0)
);

-- One active cart per user, tied to the store that serves their pincode.
CREATE TABLE carts (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    store_id   BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_carts_user (user_id),
    CONSTRAINT fk_carts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_carts_store FOREIGN KEY (store_id) REFERENCES stores (id)
);

CREATE TABLE cart_items (
    cart_id    BIGINT      NOT NULL,
    product_id BIGINT      NOT NULL,
    quantity   INT         NOT NULL,
    added_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (cart_id, product_id),
    CONSTRAINT fk_cart_items_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE,
    CONSTRAINT fk_cart_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_cart_items_qty CHECK (quantity > 0)
);

-- An order in CREATED / PAYMENT_PENDING status holds its items' stock as reserved
-- until expires_at; the expiry job releases it if payment hasn't arrived.
CREATE TABLE orders (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    user_id              BIGINT        NOT NULL,
    store_id             BIGINT        NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    total_amount         DECIMAL(10,2) NOT NULL,
    delivery_address     VARCHAR(500)  NOT NULL,
    delivery_pincode     CHAR(6)       NOT NULL,
    reservation_strategy VARCHAR(20)   NOT NULL,
    expires_at           DATETIME(6)   NOT NULL,
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_orders_user_created (user_id, created_at),
    KEY idx_orders_status_expires (status, expires_at),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_store FOREIGN KEY (store_id) REFERENCES stores (id)
);

CREATE TABLE order_items (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    order_id   BIGINT        NOT NULL,
    product_id BIGINT        NOT NULL,
    quantity   INT           NOT NULL,
    unit_price DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_items_order_product (order_id, product_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_order_items_qty CHECK (quantity > 0)
);

CREATE TABLE payments (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    order_id            BIGINT      NOT NULL,
    razorpay_order_id   VARCHAR(64) NOT NULL,
    razorpay_payment_id VARCHAR(64),
    amount_paise        BIGINT      NOT NULL,
    currency            CHAR(3)     NOT NULL DEFAULT 'INR',
    status              VARCHAR(20) NOT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payments_order (order_id),
    UNIQUE KEY uk_payments_razorpay_order (razorpay_order_id),
    CONSTRAINT fk_payments_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

-- Every webhook delivery is stored once; a repeat delivery of the same event_id is ignored.
CREATE TABLE webhook_events (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    provider     VARCHAR(20)  NOT NULL,
    event_id     VARCHAR(100) NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    payload      JSON         NOT NULL,
    received_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhook_events_provider_event (provider, event_id)
);
